package io.hl7sender.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {

    @TempDir
    Path dir;

    @Test
    void localFileStoreEncryptsAndPersists() throws Exception {
        SecretStore store = SecretStores.localFile(dir);
        assertThat(store.get("a")).isEmpty();
        store.put("destination/x/keystore", "s3cret-P@ss");
        store.put("other", "value");
        assertThat(store.get("destination/x/keystore")).contains("s3cret-P@ss");

        String onDisk = Files.readString(dir.resolve("secrets.json"));
        assertThat(onDisk).contains("destination/x/keystore").doesNotContain("s3cret-P@ss");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secrets.key"))))
                    .isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secrets.json"))))
                    .isEqualTo("rw-------");
        }

        SecretStore reopened = SecretStores.localFile(dir);
        assertThat(reopened.get("destination/x/keystore")).contains("s3cret-P@ss");
        reopened.delete("destination/x/keystore");
        assertThat(reopened.get("destination/x/keystore")).isEmpty();
        assertThat(reopened.get("other")).contains("value");
        assertThat(reopened.isOsKeychain()).isFalse();
    }

    @Test
    void wrongKeyFileIsDetected() throws Exception {
        SecretStore store = SecretStores.localFile(dir);
        store.put("k", "v");
        Files.writeString(dir.resolve("secrets.key"), java.util.Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> SecretStores.localFile(dir).get("k")).isInstanceOf(SecretStoreException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    void detectFallsBackToFileWhenForced() {
        String old = System.getProperty("hl7sender.secrets");
        System.setProperty("hl7sender.secrets", "file");
        try {
            assertThat(SecretStores.detect(dir).description()).startsWith("local encrypted file");
        } finally {
            if (old == null) {
                System.clearProperty("hl7sender.secrets");
            } else {
                System.setProperty("hl7sender.secrets", old);
            }
        }
    }

    @Test
    void inMemoryStore() {
        SecretStore s = new InMemorySecretStore();
        s.put("a", "b");
        assertThat(s.get("a")).contains("b");
        s.delete("a");
        assertThat(s.get("a")).isEmpty();
    }
}
