package io.hl7sender.core.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.ack.AckBuilder;
import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** HTTP and file transports through the delivery engine, and plugins loaded from jars. */
@Timeout(30)
class TransportTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\r"
            + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE\rPV1|1|I\r";

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    /** What the server answers: status and body; "ACK:AA" answers with an HL7 ACK for the request. */
    private final AtomicReference<Object[]> reply = new AtomicReference<>(new Object[] {200, ""});

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"), Clock.systemUTC());
        engine = new DeliveryEngine(store, new Hl7Sender());
        engine.start();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hl7", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            Object[] r = reply.get();
            String out = (String) r[1];
            if (out.startsWith("ACK:")) {
                out = new AckBuilder().build(ParsedMessage.parse(body), AckCode.valueOf(out.substring(4)), "",
                        null);
            }
            if (out.equals("SLOW")) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out = "";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((int) r[0], b.length == 0 ? -1 : b.length);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hl7";
    }

    private DestinationConfig http(int ackTimeoutMs) {
        return engine.saveDestination(DestinationConfig.of("Web", "localhost", 1)
                .withTimeouts(2_000, ackTimeoutMs)
                .withRetry(new io.hl7sender.core.queue.RetryPolicy(2, 20, 50, 0))
                .withTransport("http", Map.of("url", url(), "headers", "Authorization: Bearer abc\nX-Test: 1")));
    }

    private QueuedMessage deliver(DestinationConfig d, MessageStatus... done) throws InterruptedException {
        QueuedMessage m = engine.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS).message().orElseThrow();
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
    void httpStatusAndAcksDecideTheOutcome() throws Exception {
        DestinationConfig d = http(1_000);
        assertThat(d.address()).isEqualTo(url());
        reply.set(new Object[] {200, ""});
        QueuedMessage ok = deliver(d, MessageStatus.ACKNOWLEDGED);
        assertThat(ok.status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
        assertThat(bodies.get(0)).startsWith("MSH|").contains("\rPID|");
        assertThat(auth.get(0)).isEqualTo("Bearer abc");

        reply.set(new Object[] {200, "ACK:AE"});
        QueuedMessage ae = deliver(d, MessageStatus.DEAD_LETTER);
        assertThat(ae.status()).isEqualTo(MessageStatus.DEAD_LETTER);
        assertThat(ae.lastOutcome()).contains("APPLICATION_ERROR");
        assertThat(store.attempts(ae.id()).get(0).ackCode()).contains("AE");

        reply.set(new Object[] {400, "bad request"});
        QueuedMessage bad = deliver(d, MessageStatus.DEAD_LETTER);
        assertThat(bad.lastError()).get().asString().contains("HTTP 400: bad request");

        reply.set(new Object[] {503, ""});
        QueuedMessage busy = deliver(d, MessageStatus.DEAD_LETTER);
        assertThat(busy.attempts()).isEqualTo(2);       // retried, as for AR
        assertThat(busy.lastOutcome()).contains("APPLICATION_REJECT");

        reply.set(new Object[] {200, "SLOW"});
        QueuedMessage slow = deliver(d, MessageStatus.DEAD_LETTER);
        assertThat(slow.lastOutcome()).contains("ACK_TIMEOUT");
    }

    @Test
    void httpReceiverThatIsDownIsRetried() throws Exception {
        server.stop(0);
        QueuedMessage m = deliver(http(1_000), MessageStatus.DEAD_LETTER);
        assertThat(m.lastOutcome()).contains("CONNECTION_FAILED");
        assertThat(m.attempts()).isEqualTo(2);
    }

    @Test
    void fileTransportWritesOneFilePerMessage() throws Exception {
        Path out = dir.resolve("out");
        DestinationConfig d = engine.saveDestination(DestinationConfig.of("Drop", "localhost", 1)
                .withTransport("file", Map.of("folder", out.toString(), "fileName", "{TYPE}-{CONTROL_ID}.hl7",
                        "lineEnding", "CRLF")));
        QueuedMessage a = deliver(d, MessageStatus.SENT_UNCONFIRMED);
        QueuedMessage b = deliver(d, MessageStatus.SENT_UNCONFIRMED);
        assertThat(a.status()).isEqualTo(MessageStatus.SENT_UNCONFIRMED);
        List<Path> files;
        try (Stream<Path> s = Files.list(out)) {
            files = s.sorted().toList();
        }
        assertThat(files).hasSize(2);
        assertThat(files.get(0).getFileName().toString()).startsWith("ADT_A01-").endsWith(".hl7");
        assertThat(Files.readString(files.get(0))).startsWith("MSH|").contains("\r\nPID|");
        assertThat(files.stream().map(f -> f.getFileName().toString()))
                .anyMatch(n -> n.contains(b.controlId()));
        assertThat(d.displayAddress()).isEqualTo(out.toString());
    }

    @Test
    void invalidSettingsAreRefusedWhenSaving() {
        assertThatThrownBy(() -> engine.saveDestination(DestinationConfig.of("X", "localhost", 1)
                .withTransport("http", Map.of()))).hasMessageContaining("URL is required");
        assertThatThrownBy(() -> engine.saveDestination(DestinationConfig.of("X", "localhost", 1)
                .withTransport("http", Map.of("url", "ftp://x")))).hasMessageContaining("http://");
        assertThatThrownBy(() -> engine.saveDestination(DestinationConfig.of("X", "localhost", 1)
                .withTransport("http", Map.of("url", url(), "headers", "Host: evil")))).hasMessageContaining(
                "cannot be set");
        assertThatThrownBy(() -> engine.saveDestination(DestinationConfig.of("X", "localhost", 1)
                .withTransport("file", Map.of("folder", "/tmp", "lineEnding", "CRCR")))).hasMessageContaining("CR");
        assertThatThrownBy(() -> engine.saveDestination(DestinationConfig.of("X", "localhost", 1)
                .withTransport("sftp", Map.of()))).hasMessageContaining("Unknown transport 'sftp'");
        assertThatThrownBy(() -> DestinationConfig.of("X", "localhost", 1).withTransport("file",
                Map.of("folder", "/tmp")).clientConfig()).hasMessageContaining("not MLLP");
    }

    @Test
    void pluginsAreLoadedFromJarsInThePluginsFolder() throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        buildPluginJar(plugins.resolve("memory-transport.jar"));
        Files.writeString(plugins.resolve("notes.txt"), "ignored");

        // Closed at the end so the jar is released (on Windows the temporary folder cannot be deleted otherwise).
        try (Transports t = Transports.load(plugins)) {
            assertThat(t.all()).extracting(TransportFactory::id).containsExactly("http", "file", "fhir", "memory");
            assertThat(t.problems()).isEmpty();
            assertThat(Transports.builtIn().get("memory")).isEmpty();

            engine.setTransports(t);
            MemoryTransportFactory.SENT.clear();
            DestinationConfig d = engine.saveDestination(DestinationConfig.of("Mem", "localhost", 1)
                    .withTransport("memory", Map.of("bucket", "b1")));
            QueuedMessage m = deliver(d, MessageStatus.ACKNOWLEDGED);
            assertThat(MemoryTransportFactory.SENT).containsExactly("b1:" + m.controlId());

            // Profiles keep the transport and its settings.
            Path file = dir.resolve("profiles.json");
            DestinationProfiles.write(List.of(d), file);
            DestinationConfig read = DestinationProfiles.read(file).get(0);
            assertThat(read.transport()).isEqualTo("memory");
            assertThat(read.transportOptions()).containsEntry("bucket", "b1");
            assertThat(read.host()).isEqualTo("localhost");
        }
    }

    /** A plugin jar: the test's memory transport plus its service file. */
    private static void buildPluginJar(Path jar) throws IOException {
        String name = MemoryTransportFactory.class.getName().replace('.', '/');
        Path classes = Path.of(MemoryTransportFactory.class.getProtectionDomain().getCodeSource().getLocation()
                .getPath().replaceFirst("^/([A-Za-z]:)", "$1"));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/" + TransportFactory.class.getName()));
            out.write((MemoryTransportFactory.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            try (Stream<Path> files = Files.list(classes.resolve(name).getParent())) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("MemoryTransportFactory"))
                        .toList()) {
                    out.putNextEntry(new JarEntry(name.substring(0, name.lastIndexOf('/') + 1) + f.getFileName()));
                    out.write(Files.readAllBytes(f));
                    out.closeEntry();
                }
            }
        }
    }
}
