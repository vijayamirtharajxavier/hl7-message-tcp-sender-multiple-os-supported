package io.hl7sender.core.secrets;

import java.util.Optional;

/**
 * Stores small secrets such as key-store passwords and the queue database key, outside the settings
 * file and the queue database. Keys are short names such as {@code destination/3f2a/keystore}.
 */
public interface SecretStore {

    Optional<String> get(String key);

    void put(String key, String value);

    void delete(String key);

    /** Human-readable backend name, for example "macOS Keychain" or "local encrypted file". */
    String description();

    /** True if secrets are held by the operating system's credential store. */
    boolean isOsKeychain();
}
