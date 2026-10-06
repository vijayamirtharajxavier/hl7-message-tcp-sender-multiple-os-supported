package io.hl7sender.core.secrets;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the best available {@link SecretStore}. */
public final class SecretStores {

    private static final Logger LOG = LoggerFactory.getLogger(SecretStores.class);

    private SecretStores() {
    }

    /**
     * The OS keychain if it works on this machine, otherwise an encrypted file in {@code configDir}.
     * Setting the system property {@code hl7sender.secrets=file} forces the file store.
     */
    public static SecretStore detect(Path configDir) {
        if (!"file".equalsIgnoreCase(System.getProperty("hl7sender.secrets"))) {
            var keyring = KeyringSecretStore.open();
            if (keyring.isPresent()) {
                LOG.info("Secrets are stored in the {}", keyring.get().description());
                return keyring.get();
            }
        }
        SecretStore file = new LocalFileSecretStore(configDir);
        LOG.warn("No OS keychain available; secrets are stored in a {}", file.description());
        return file;
    }

    /** The encrypted-file store in {@code dir}, regardless of keychain availability. */
    public static SecretStore localFile(Path dir) {
        return new LocalFileSecretStore(dir);
    }
}
