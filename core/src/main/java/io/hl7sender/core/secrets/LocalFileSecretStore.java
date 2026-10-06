package io.hl7sender.core.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fallback used when no OS keychain is available (for example on headless Linux). Secrets are
 * encrypted with AES-256-GCM in {@code secrets.json}. The random key is kept in a separate
 * {@code secrets.key} file readable only by the owner (POSIX permissions where supported; on Windows
 * the user profile's ACL applies).
 *
 * <p>This keeps secrets out of settings files, the queue database, exports and casual backups. It does
 * not protect against someone who can read the user's files. The UI says so and recommends an OS
 * keychain.
 */
final class LocalFileSecretStore implements SecretStore {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final Path keyFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    LocalFileSecretStore(Path dir) {
        this.file = dir.resolve("secrets.json");
        this.keyFile = dir.resolve("secrets.key");
    }

    @Override
    public synchronized Optional<String> get(String key) {
        String sealed = load().get(key);
        return sealed == null ? Optional.empty() : Optional.of(open(sealed));
    }

    @Override
    public synchronized void put(String key, String value) {
        Map<String, String> all = load();
        all.put(key, seal(value));
        save(all);
    }

    @Override
    public synchronized void delete(String key) {
        Map<String, String> all = load();
        if (all.remove(key) != null) {
            save(all);
        }
    }

    @Override
    public String description() {
        return "local encrypted file (" + file + ")";
    }

    @Override
    public boolean isOsKeychain() {
        return false;
    }

    private Map<String, String> load() {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() { }));
        } catch (IOException e) {
            throw new SecretStoreException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private void save(Map<String, String> all) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir == null) {
                throw new IOException("No parent directory for " + file);
            }
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "secrets", ".tmp");
            try {
                restrict(tmp);
                mapper.writeValue(tmp.toFile(), all);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new SecretStoreException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private SecretKeySpec key() {
        try {
            if (!Files.exists(keyFile)) {
                Path dir = keyFile.toAbsolutePath().getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                byte[] k = new byte[KEY_BYTES];
                random.nextBytes(k);
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(k), StandardCharsets.US_ASCII);
                restrict(keyFile);
            }
            byte[] k = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            return new SecretKeySpec(k, "AES");
        } catch (IOException | IllegalArgumentException e) {
            throw new SecretStoreException("Cannot use key file " + keyFile + ": " + e.getMessage(), e);
        }
    }

    private String seal(String value) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new SecretStoreException("Encryption failed: " + e.getMessage(), e);
        }
    }

    private String open(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretStoreException("Cannot decrypt secret (was secrets.key replaced?)", e);
        }
    }

    private static void restrict(Path p) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }
}
