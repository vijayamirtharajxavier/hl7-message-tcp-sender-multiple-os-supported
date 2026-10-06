package io.hl7sender.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The FHIR transport against a mock FHIR server. */
@Timeout(30)
class FhirTransportTest {

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Object[]> reply = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"), Clock.systemUTC());
        engine = new DeliveryEngine(store, new Hl7Sender());
        engine.start();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/r4", ex -> {
            requests.add(ex.getRequestHeaders().getFirst("Content-Type") + "\n"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Object[] r = reply.get();
            byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/fhir+json");
            ex.sendResponseHeaders((int) r[0], b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        server.stop(0);
    }

    private QueuedMessage deliver(MessageStatus... done) throws InterruptedException {
        DestinationConfig d = engine.destinations().stream().filter(x -> x.name().equals("FHIR")).findFirst()
                .orElseGet(() -> engine.saveDestination(DestinationConfig.of("FHIR", "localhost", 1)
                        .withRetry(new io.hl7sender.core.queue.RetryPolicy(1, 10, 10, 0))
                        .withTransport("fhir", Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()
                                + "/r4"))));
        String adt = SampleMessages.all().stream().filter(s -> s.text().contains("ADT^A01")).findFirst()
                .orElseThrow().text();
        QueuedMessage m = engine.enqueue(d.id(), adt, SendOptions.DEFAULTS).message().orElseThrow();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            QueuedMessage now = store.message(m.id()).orElseThrow();
            if (List.of(done).contains(now.status())) {
                return now;
            }
            Thread.sleep(20);
        }
        return store.message(m.id()).orElseThrow();
    }

    @Test
    void bundleIsPostedAndTheTransactionResponseDecides() throws Exception {
        reply.set(new Object[] {200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\",\"entry\":["
                + "{\"response\":{\"status\":\"201 Created\"}},{\"response\":{\"status\":\"201 Created\"}}]}"});
        QueuedMessage ok = deliver(MessageStatus.ACKNOWLEDGED);
        assertThat(ok.status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
        assertThat(requests.get(0)).startsWith("application/fhir+json\n").contains("\"resourceType\" : \"Bundle\"")
                .contains("\"family\" : \"DOE\"");
        assertThat(store.attempts(ok.id()).get(0).detail()).get().asString().contains("2 resource(s) stored");

        reply.set(new Object[] {200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\",\"entry\":["
                + "{\"response\":{\"status\":\"400 Bad Request\",\"outcome\":{\"resourceType\":\"OperationOutcome\","
                + "\"issue\":[{\"severity\":\"error\",\"diagnostics\":\"Invalid gender\"}]}}}]}"});
        QueuedMessage entryFailed = deliver(MessageStatus.DEAD_LETTER);
        assertThat(entryFailed.lastError()).get().asString().contains("Entry 1 failed: 400 Bad Request")
                .contains("Invalid gender");

        reply.set(new Object[] {422, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"diagnostics\":\"Unknown code system\"}]}"});
        QueuedMessage rejected = deliver(MessageStatus.DEAD_LETTER);
        assertThat(rejected.lastOutcome()).contains("APPLICATION_ERROR");
        assertThat(rejected.lastError()).get().asString().isEqualTo("HTTP 422: error: Unknown code system");

        reply.set(new Object[] {503, "down"});
        QueuedMessage busy = deliver(MessageStatus.DEAD_LETTER);
        assertThat(busy.lastOutcome()).contains("APPLICATION_REJECT");
    }
}
