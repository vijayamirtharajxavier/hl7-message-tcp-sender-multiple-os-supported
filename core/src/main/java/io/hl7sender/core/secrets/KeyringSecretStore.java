package io.hl7sender.core.secrets;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import java.util.Optional;

/**
 * Secrets in the operating system's credential store: Windows Credential Manager, macOS Keychain, or the
 * Secret Service (GNOME Keyring / KWallet) on Linux.
 */
final class KeyringSecretStore implements SecretStore {

    static final String SERVICE = "HL7 Sender";

    private final Keyring keyring;

    private KeyringSecretStore(Keyring keyring) {
        this.keyring = keyring;
    }

    /** Opens the OS keychain and checks it with a write/read/delete round trip. */
    static Optional<SecretStore> open() {
        try {
            Keyring k = Keyring.create();
            String probe = "probe-" + System.nanoTime();
            k.setPassword(SERVICE, probe, "ok");
            boolean works = "ok".equals(k.getPassword(SERVICE, probe));
            k.deletePassword(SERVICE, probe);
            return works ? Optional.of(new KeyringSecretStore(k)) : Optional.empty();
        } catch (BackendNotSupportedException | PasswordAccessException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(keyring.getPassword(SERVICE, key));
        } catch (PasswordAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            keyring.setPassword(SERVICE, key, value);
        } catch (PasswordAccessException e) {
            throw new SecretStoreException("Could not store secret in " + description() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            keyring.deletePassword(SERVICE, key);
        } catch (PasswordAccessException e) {
            // Already absent.
        }
    }

    @Override
    public String description() {
        return switch (keyring.getKeyringStorageType()) {
            case OSX_KEYCHAIN, LEGACY_OSX_KEYCHAIN -> "macOS Keychain";
            case WINDOWS_CREDENTIAL_STORE -> "Windows Credential Manager";
            case GNOME_KEYRING -> "GNOME Keyring";
            case KWALLET -> "KWallet";
        };
    }

    @Override
    public boolean isOsKeychain() {
        return true;
    }
}
