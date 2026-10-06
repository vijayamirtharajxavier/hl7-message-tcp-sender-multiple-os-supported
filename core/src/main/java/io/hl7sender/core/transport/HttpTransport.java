package io.hl7sender.core.transport;

import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.tls.TlsOptions;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLParameters;

/**
 * Sends each message as the body of an HTTP(S) request, for receivers such as a Mirth HTTP Listener, NiFi's
 * ListenHTTP or an integration platform's REST endpoint.
 *
 * <p>If the response body is an HL7 acknowledgment it decides the outcome, as for MLLP. Otherwise the status code
 * does: 2xx accepted; 408, 429 and 5xx rejected for now (retried by default); other 4xx an error in the message
 * (dead-lettered by default). Timeouts and connection failures are retried.
 */
final class HttpTransport implements Transport {

    static final String ID = "http";

    private final HttpClient client;
    private final URI uri;
    private final String method;
    private final String contentType;
    private final List<String[]> headers;
    private final Duration timeout;
    private final Charset charset;
    private volatile CompletableFuture<HttpResponse<String>> inFlight;

    private HttpTransport(HttpClient client, URI uri, String method, String contentType, List<String[]> headers,
                          Duration timeout, Charset charset) {
        this.client = client;
        this.uri = uri;
        this.method = method;
        this.contentType = contentType;
        this.headers = headers;
        this.timeout = timeout;
        this.charset = charset;
    }

    @Override
    public SendResult send(PreparedMessage message, AckMode ackMode) {
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        String where = uri.toString();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(message.wire(), charset));
        for (String[] h : headers) {
            b.header(h[0], h[1]);
        }
        HttpResponse<String> response;
        try {
            CompletableFuture<HttpResponse<String>> f = client.sendAsync(b.build(),
                    HttpResponse.BodyHandlers.ofString(charset));
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
            return failure(e.getCause() == null ? e : e.getCause(), where, message, start,
                    Duration.ofNanos(System.nanoTime() - t0), timeout);
        } finally {
            inFlight = null;
        }
        Duration rt = Duration.ofNanos(System.nanoTime() - t0);
        int status = response.statusCode();
        String body = response.body();
        if (status / 100 == 2 && ackMode == AckMode.EXPECT_ACK) {
            Optional<SendResult> fromAck = Transports.fromAck(body, where, message, start, rt);
            if (fromAck.isPresent()) {
                return fromAck.get();
            }
        } else if (status / 100 != 2) {
            Optional<SendResult> fromAck = Transports.fromAck(body, where, message, start, rt);
            if (fromAck.isPresent() && fromAck.get().outcome() != SendOutcome.ACCEPTED) {
                return fromAck.get();
            }
        }
        String detail = "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + abbreviate(body));
        SendOutcome outcome;
        if (status / 100 == 2) {
            outcome = ackMode == AckMode.NO_ACK ? SendOutcome.SENT_NO_ACK : SendOutcome.ACCEPTED;
            detail = "HTTP " + status;
        } else if (status == 408 || status == 429 || status / 100 == 5) {
            outcome = SendOutcome.APPLICATION_REJECT;
        } else {
            outcome = SendOutcome.APPLICATION_ERROR;
        }
        return Transports.result(outcome, where, message, null, body, detail, start, rt);
    }

    /** The outcome for an HTTP request that got no response: connection failures and timeouts are retried. */
    static SendResult failure(Throwable c, String where, PreparedMessage message, Instant start, Duration rt,
                              Duration timeout) {
        if (c instanceof HttpConnectTimeoutException || c instanceof ConnectException
                || c.getCause() instanceof ConnectException) {
            return Transports.result(SendOutcome.CONNECTION_FAILED, where, message, null, null,
                    c.getClass().getSimpleName() + ": " + c.getMessage(), start, Duration.ZERO);
        }
        if (c instanceof HttpTimeoutException) {
            return Transports.result(SendOutcome.ACK_TIMEOUT, where, message, null, null,
                    "No response within " + timeout.toMillis() + " ms", start, rt);
        }
        if (c instanceof javax.net.ssl.SSLException) {
            return Transports.result(SendOutcome.CONNECTION_FAILED, where, message, null, null,
                    "TLS handshake failed: " + c.getMessage(), start, Duration.ZERO);
        }
        return Transports.result(SendOutcome.SEND_FAILED, where, message, null, null,
                c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage()), start, rt);
    }

    /** A client with the destination's timeouts and TLS settings. */
    static HttpClient client(DestinationConfig d, TransportContext context) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(d.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (context.tls().isPresent()) {
            TlsOptions tls = context.tls().get();
            SSLParameters params = tls.context().getDefaultSSLParameters();
            params.setProtocols(tls.protocols().toArray(String[]::new));
            b.sslContext(tls.context()).sslParameters(params);
        }
        return b.build();
    }

    static String abbreviate(String body) {
        String one = body.strip().replaceAll("\\s+", " ");
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
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

    /** Creates HTTP transports. */
    static final class Factory implements TransportFactory {

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "HTTP(S)";
        }

        @Override
        public List<TransportOption> options() {
            return List.of(
                    TransportOption.required("url", "URL", "e.g. https://mirth.example.org:8443/hl7"),
                    TransportOption.optional("method", "Method", "POST or PUT", "POST"),
                    TransportOption.optional("contentType", "Content type", "sent as Content-Type",
                            "application/hl7-v2; charset=utf-8"),
                    TransportOption.optional("headers", "Headers", "one per line, e.g. Authorization: Bearer ...",
                            "").asMultiline());
        }

        @Override
        public void validate(Map<String, String> options) {
            TransportFactory.super.validate(options);
            checkUrl(options.getOrDefault("url", ""), "HTTP");
            String method = options.getOrDefault("method", "POST").trim().toUpperCase(Locale.ROOT);
            if (!method.isEmpty() && !method.equals("POST") && !method.equals("PUT")) {
                throw new IllegalArgumentException("HTTP: the method must be POST or PUT");
            }
            headers(options.getOrDefault("headers", ""));
        }

        static void checkUrl(String text, String what) {
            String url = text.trim();
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(what + ": '" + url + "' is not a valid URL", e);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                throw new IllegalArgumentException(what
                        + ": the URL must start with http:// or https:// and name a host");
            }
        }

        @Override
        public String describe(Map<String, String> options) {
            return options.getOrDefault("url", "http");
        }

        @Override
        public Transport open(DestinationConfig d, TransportContext context) {
            Map<String, String> o = d.transportOptions();
            String method = o.getOrDefault("method", "POST").trim().toUpperCase(Locale.ROOT);
            return new HttpTransport(client(d, context), URI.create(o.get("url").trim()),
                    method.isEmpty() ? "POST" : method,
                    o.getOrDefault("contentType", "").isBlank() ? "application/hl7-v2; charset=" + d.charset()
                            : o.get("contentType").trim(),
                    headers(o.getOrDefault("headers", "")), Duration.ofMillis(d.ackTimeoutMs()),
                    Charset.forName(d.charset()));
        }

        static List<String[]> headers(String text) {
            List<String[]> out = new java.util.ArrayList<>();
            for (String line : text.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("HTTP: header '" + line + "' must be 'Name: value'");
                }
                String name = line.substring(0, colon).trim();
                if (!name.matches("[A-Za-z0-9-]+") || name.equalsIgnoreCase("Content-Length")
                        || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Connection")) {
                    throw new IllegalArgumentException("HTTP: header '" + name + "' cannot be set");
                }
                out.add(new String[] {name, line.substring(colon + 1).trim()});
            }
            return out;
        }
    }
}
