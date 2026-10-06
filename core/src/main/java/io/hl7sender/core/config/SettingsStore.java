package io.hl7sender.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves {@link AppSettings} as JSON. Writes go to a temporary file that is then moved
 * over the old one, so a crash cannot leave a half-written settings file. An unreadable file is
 * kept as {@code settings.json.corrupt} and defaults are used.
 */
public final class SettingsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public AppSettings load() {
        if (!Files.exists(file)) {
            return AppSettings.defaults();
        }
        try {
            AppSettings s = mapper.readValue(file.toFile(), AppSettings.class);
            return s == null ? AppSettings.defaults() : s.normalized();
        } catch (IOException e) {
            LOG.warn("Settings file {} is unreadable ({}); using defaults", file, e.getMessage());
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                LOG.warn("Could not back up unreadable settings file: {}", moveFailed.getMessage());
            }
            return AppSettings.defaults();
        }
    }

    public void save(AppSettings settings) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("Settings file has no parent directory: " + file);
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "settings", ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), settings);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
