package io.hl7sender.core.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hl7sender.core.fhir.V2ToFhir;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Converts each queued HL7 v2 message to a FHIR R4 transaction Bundle ({@link V2ToFhir}) and posts it to a FHIR
 * server's base URL.
 *
 * <p>Outcome: a 2xx response whose transaction-response entries all succeeded is accepted; a failed entry, or a 4xx
 * with an OperationOutcome, is an error in the message (dead-lettered by default); 408, 429 and 5xx are retried, as
 * are timeouts and connection failures.
 */
final class FhirTransport implements Transport {

    static final String ID = "fhir";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final URI base;
    private final List<String[]> headers;
    private final Duration timeout;
    private volatile CompletableFuture<HttpResponse<String>> inFlight;

    private FhirTransport(HttpClient client, URI base, List<String[]> headers, Duration timeout) {
        this.client = client;
        this.base = base;
        this.headers = headers;
        this.timeout = timeout;
    }

    @Override
    public SendResult send(PreparedMessage message, AckMode ackMode) {
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        String where = base.toString();
        V2ToFhir.Result bundle;
        try {
            bundle = V2ToFhir.convert(message.wire());
        } catch (Hl7FormatException | IllegalArgumentException e) {
            return Transports.result(SendOutcome.APPLICATION_ERROR, where, message, null, null,
                    "Cannot convert to FHIR: " + e.getMessage(), start, Duration.ZERO);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(base).timeout(timeout)
                .header("Content-Type", "application/fhir+json")
                .header("Accept", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(bundle.json(), StandardCharsets.UTF_8));
        for (String[] h : headers) {
            b.header(h[0], h[1]);
        }
        HttpResponse<String> response;
        try {
            CompletableFuture<HttpResponse<String>> f = client.sendAsync(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            inFlight = f;
            response = f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Transports.result(SendOutcome.SEND_FAILED, where, message, null, null, "Interrupted", start,
                    Duration.ZERO);
        } catch (java.util.concurrent.CancellationException e) {
            return Transports.result(SendOutcome.SEND_FAILED, where, message, null, null, "Cancelled", start,
                    Duration.ZERO);
        } catch (ExecutionException e) {
            return HttpTransport.failure(e.getCause() == null ? e : e.getCause(), where, message, start,
                    Duration.ofNanos(System.nanoTime() - t0), timeout);
        } finally {
            inFlight = null;
        }
        Duration rt = Duration.ofNanos(System.nanoTime() - t0);
        int status = response.statusCode();
        String body = response.body();
        JsonNode json = parse(body);
        if (status / 100 == 2) {
            String failed = failedEntry(json);
            if (failed != null) {
                return Transports.result(SendOutcome.APPLICATION_ERROR, where, message, null, body, failed, start,
                        rt);
            }
            int n = json != null && json.path("entry").isArray() ? json.path("entry").size() : 0;
            return Transports.result(ackMode == AckMode.NO_ACK ? SendOutcome.SENT_NO_ACK : SendOutcome.ACCEPTED,
                    where, message, null, body, "HTTP " + status + (n > 0 ? ", " + n + " resource(s) stored" : ""),
                    start, rt);
        }
        String detail = "HTTP " + status + ": " + outcomeText(json, body);
        SendOutcome outcome = status == 408 || status == 429 || status / 100 == 5 ? SendOutcome.APPLICATION_REJECT
                : SendOutcome.APPLICATION_ERROR;
        return Transports.result(outcome, where, message, null, body, detail, start, rt);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The first entry of a transaction-response that did not succeed, described; or null. */
    static String failedEntry(JsonNode bundle) {
        if (bundle == null || !"Bundle".equals(bundle.path("resourceType").asText())) {
            return null;
        }
        int i = 0;
        for (JsonNode e : bundle.path("entry")) {
            i++;
            String status = e.path("response").path("status").asText("");
            if (!status.isEmpty() && !status.startsWith("2")) {
                return "Entry " + i + " failed: " + status + " " + outcomeText(e.path("response").path("outcome"),
                        "");
            }
        }
        return null;
    }

    /** The diagnostics of an OperationOutcome, or the start of the body. */
    static String outcomeText(JsonNode outcome, String body) {
        if (outcome != null && "OperationOutcome".equals(outcome.path("resourceType").asText())) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode issue : outcome.path("issue")) {
                String text = issue.path("diagnostics").asText(issue.path("details").path("text").asText(""));
                if (!text.isEmpty()) {
                    sb.append(sb.isEmpty() ? "" : "; ").append(issue.path("severity").asText("error")).append(": ")
                            .append(text);
                }
            }
            if (!sb.isEmpty()) {
                return HttpTransport.abbreviate(sb.toString());
            }
        }
        return body == null || body.isBlank() ? "" : HttpTransport.abbreviate(body);
    }

    @Override
    public void abort() {
        CompletableFuture<HttpResponse<String>> f = inFlight;
        if (f != null) {
            f.cancel(true);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** Creates FHIR transports. */
    static final class Factory implements TransportFactory {

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "FHIR R4 (converted from v2)";
        }

        @Override
        public List<TransportOption> options() {
            return List.of(
                    TransportOption.required("baseUrl", "FHIR base URL", "e.g. https://fhir.example.org/r4"),
                    TransportOption.optional("headers", "Headers", "one per line, e.g. Authorization: Bearer ...",
                            "").asMultiline());
        }

        @Override
        public void validate(Map<String, String> options) {
            TransportFactory.super.validate(options);
            HttpTransport.Factory.checkUrl(options.getOrDefault("baseUrl", ""), "FHIR");
            HttpTransport.Factory.headers(options.getOrDefault("headers", ""));
        }

        @Override
        public String describe(Map<String, String> options) {
            return options.getOrDefault("baseUrl", "fhir");
        }

        @Override
        public Transport open(DestinationConfig d, TransportContext context) {
            Map<String, String> o = d.transportOptions();
            return new FhirTransport(HttpTransport.client(d, context), URI.create(o.get("baseUrl").trim()),
                    HttpTransport.Factory.headers(o.getOrDefault("headers", "")), Duration.ofMillis(d.ackTimeoutMs()));
        }
    }
}
