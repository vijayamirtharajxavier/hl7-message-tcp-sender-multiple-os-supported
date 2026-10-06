package io.hl7sender.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the running app or service serves its local API: {@code api.json} next to the queue database. The CLI
 * reads it to ask the process that delivers from the queue to act at once. It holds no secrets.
 *
 * @param port TCP port on the loopback interface
 * @param pid  process ID of the server
 */
public record ApiEndpoint(int port, long pid) {

    static final String FILE = "api.json";

    public static Path file(AppPaths paths) {
        return paths.dataDir().resolve(FILE);
    }

    public static Optional<ApiEndpoint> read(AppPaths paths) {
        Path f = file(paths);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            JsonNode n = JsonViews.COMPACT.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return Optional.of(new ApiEndpoint(n.path("port").asInt(), n.path("pid").asLong()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    void write(AppPaths paths) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", port);
        m.put("pid", pid);
        m.put("url", "http://127.0.0.1:" + port + "/api/v1/");
        Files.createDirectories(paths.dataDir());
        Files.writeString(file(paths), JsonViews.PRETTY.writeValueAsString(m), StandardCharsets.UTF_8);
    }

    /** Removes the file if it still describes this server. */
    void delete(AppPaths paths) {
        read(paths).filter(e -> e.pid() == pid && e.port() == port).ifPresent(e -> {
            try {
                Files.deleteIfExists(file(paths));
            } catch (IOException ignored) {
                // Stale files are harmless: clients check that the server answers.
            }
        });
    }
}
