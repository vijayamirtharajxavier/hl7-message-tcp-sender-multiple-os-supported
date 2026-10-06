package io.hl7sender.core.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The bearer token for the local REST API, kept in {@code api-token} in the settings folder, readable only by
 * the owner on POSIX systems (like an SSH key). Scripts on the same machine and account read it from there.
 */
public final class ApiToken {

    static final String FILE = "api-token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiToken() {
    }

    public static Path file(Path configDir) {
        return configDir.resolve(FILE);
    }

    public static Optional<String> load(Path configDir) throws IOException {
        Path f = file(configDir);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        String token = Files.readString(f, StandardCharsets.UTF_8).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** The existing token, or a new one if there is none yet. */
    public static String loadOrCreate(Path configDir) throws IOException {
        Optional<String> existing = load(configDir);
        return existing.isPresent() ? existing.get() : regenerate(configDir);
    }

    /** Replaces the token; clients using the old one are refused from now on. */
    public static String regenerate(Path configDir) throws IOException {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        Files.createDirectories(configDir);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path tmp = posix
                ? Files.createTempFile(configDir, FILE, ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(configDir, FILE, ".tmp");
        try {
            Files.writeString(tmp, token + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file(configDir), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file(configDir), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return token;
    }

    /**
     * The current token, read from the file on every call (it is tiny), so a regenerated token takes effect at once
     * in a running server. If the file is unreadable or deleted, no token matches.
     */
    public static Supplier<String> watching(Path configDir) {
        return () -> {
            try {
                return load(configDir).orElse(null);
            } catch (IOException e) {
                return null;
            }
        };
    }

    /** Constant-time comparison, so response timing reveals nothing about the token. */
    public static boolean matches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
