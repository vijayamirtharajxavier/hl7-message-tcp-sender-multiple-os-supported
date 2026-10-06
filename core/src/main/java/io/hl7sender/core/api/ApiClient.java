package io.hl7sender.core.api;

import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Calls the local API of the process that delivers from the queue. */
public final class ApiClient {

    /**
     * An HTTP response.
     *
     * @param status HTTP status code
     * @param body   response body (JSON)
     */
    public record Response(int status, String body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(HttpClient.Builder.NO_PROXY).build();

    public ApiClient(int port, String token) {
        this.base = "http://127.0.0.1:" + port + "/api/v1/";
        this.token = token;
    }

    /** A client for the running app or service, if its API is enabled and the token is readable. */
    public static Optional<ApiClient> discover(AppPaths paths) {
        Optional<ApiEndpoint> endpoint = ApiEndpoint.read(paths);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            return ApiToken.load(paths.configDir()).map(t -> new ApiClient(endpoint.get().port(), t));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Response get(String path) throws IOException {
        return send(request(path).GET());
    }

    public Response post(String path, String body) throws IOException {
        return send(request(path).header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8)));
    }

    public Response delete(String path) throws IOException {
        return send(request(path).DELETE());
    }

    /** Asks the server to pick up changes made to the database by this process. Returns false if it did not. */
    public boolean reload() {
        try {
            return post("reload", "").ok();
        } catch (IOException e) {
            return false;
        }
    }

    private HttpRequest.Builder request(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return HttpRequest.newBuilder(URI.create(base + p)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token);
    }

    private Response send(HttpRequest.Builder b) throws IOException {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
