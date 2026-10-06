package io.hl7sender.core.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POSTs alerts as JSON. The {@code text} property makes the payload work with Slack and Microsoft Teams
 * incoming webhooks; the other properties are for custom receivers.
 */
public final class WebhookSink implements AlertSink {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI uri;
    private final HttpClient client;

    public WebhookSink(String url) {
        this.uri = validate(url);
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Checks that {@code url} is an absolute http or https URL. */
    public static URI validate(String url) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Webhook URL is not valid: " + e.getMessage());
        }
        if (u.getScheme() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))
                || u.getHost() == null) {
            throw new IllegalArgumentException("Webhook URL must start with https:// (or http://)");
        }
        return u;
    }

    /** The URL with its path and query hidden, since webhook URLs usually embed a secret token. */
    public static String redact(String url) {
        try {
            URI u = URI.create(url.trim());
            return u.getHost() == null ? "(invalid)" : u.getScheme() + "://" + u.getHost() + "/...";
        } catch (IllegalArgumentException e) {
            return "(invalid)";
        }
    }

    @Override
    public String name() {
        return "Webhook";
    }

    @Override
    public void send(Alert alert) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "HL7 Sender " + alert.summary() + "\n" + alert.message());
        body.put("kind", alert.kind().name());
        body.put("severity", alert.severity().name());
        body.put("title", alert.title());
        body.put("message", alert.message());
        body.put("destination", alert.destinationName());
        body.put("at", alert.at().toString());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Webhook returned HTTP " + response.statusCode());
        }
    }
}
