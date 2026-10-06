package io.hl7sender.core.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.alert.Alert;
import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.alert.AlertSettings;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.diagnostics.DiagnosticsBundle;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.CircuitBreakerSettings;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.RetryPolicy;
import io.hl7sender.core.secrets.InMemorySecretStore;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.TestCertificates;
import io.hl7sender.core.tls.TlsSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Dashboard statistics, alerts (webhook and e-mail) and the diagnostics bundle. */
@Timeout(90)
class MonitoringTest {

    static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|ORIG|P|2.5.1\n"
            + "EVN|A01|20260101\nPID|1||MRN-PHI-123^^^H^MR||DOE^JANE\nPV1|1|I";
    static final RetryPolicy FAST = new RetryPolicy(3, 20, 50, 0);

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener listener;
    private final AtomicReference<AlertSettings> alertSettings = new AtomicReference<>(AlertSettings.defaults());
    private final InMemorySecretStore secrets = new InMemorySecretStore();
    private AlertMonitor monitor;
    private HttpServer webhook;
    private final List<String> webhookBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger webhookStatus = new AtomicInteger(200);
    private FakeSmtp smtp;

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender(), Clock.systemUTC(), new Random(3), secrets);
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, m -> { });
        listener.start();
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.createContext("/hook", ex -> {
            webhookBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(webhookStatus.get(), -1);
            ex.close();
        });
        webhook.start();
        smtp = new FakeSmtp();
        engine.start();
        monitor = new AlertMonitor(engine, alertSettings::get, secrets, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        monitor.close();
        engine.close();
        store.close();
        listener.close();
        webhook.stop(0);
        smtp.close();
    }

    private String webhookUrl() {
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
    }

    /** An unsaved test destination; tests that change it save it once, so no worker sees a half-set-up version. */
    private static DestinationConfig config(String name, int port) {
        return DestinationConfig.of(name, "127.0.0.1", port).withTimeouts(1_000, 500).withRetry(FAST);
    }

    private DestinationConfig destination(String name, int port) {
        return engine.saveDestination(config(name, port));
    }

    private int count(DestinationConfig d, MessageStatus s) {
        return store.counts(d.id()).getOrDefault(s, 0);
    }

    private void enqueue(DestinationConfig d) {
        assertThat(engine.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS).accepted()).isTrue();
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Statistics

    @Test
    void statsCountOutcomesLatencyAndThroughput() throws Exception {
        DestinationConfig d = destination("Stats", listener.port());
        for (int i = 0; i < 3; i++) {
            enqueue(d);
        }
        await("3 acknowledged", () -> count(d, MessageStatus.ACKNOWLEDGED) == 3);
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        enqueue(d);
        await("dead letter", () -> count(d, MessageStatus.DEAD_LETTER) == 1);

        DestinationStats s = engine.stats(d.id(), Duration.ofMinutes(10));
        assertThat(s.accepted()).isEqualTo(3);
        assertThat(s.nacks()).isEqualTo(1);
        assertThat(s.count(SendOutcome.APPLICATION_ERROR)).isEqualTo(1);
        assertThat(s.attempts()).isEqualTo(4);
        assertThat(s.failures()).isZero();
        assertThat(s.acceptRate()).isEqualTo(0.75);
        assertThat(s.nackRate()).isEqualTo(0.25);
        assertThat(s.avgLatencyMs()).isBetween(0L, 500L);
        assertThat(s.maxLatencyMs()).isGreaterThanOrEqualTo(s.avgLatencyMs());
        assertThat(s.acceptedPerMinute()).hasSize(10);
        assertThat(s.acceptedPerMinute().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        assertThat(s.acceptedPerMinute().get(9)).isEqualTo(3);
        assertThat(s.throughputPerMinute()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.01));

        DestinationStats empty = engine.stats(destination("Idle", 1).id(), Duration.ofMinutes(5));
        assertThat(empty.attempts()).isZero();
        assertThat(empty.acceptRate()).isEqualTo(-1);
        assertThat(empty.avgLatencyMs()).isEqualTo(-1);
    }

    // ---------------------------------------------------------------------------------------------
    // Alerts

    @Test
    void newDeadLettersAndOpenCircuitsAlertThroughWebhookAndEmail() throws Exception {
        secrets.put(AlertSettings.SMTP_PASSWORD_KEY, "unused");
        alertSettings.set(new AlertSettings(true, true, 1, true, true, webhookUrl(),
                new AlertSettings.EmailSettings(true, "127.0.0.1", smtp.port(),
                        AlertSettings.EmailSettings.Security.NONE, "", "hl7@example.org", "ops@example.org")));
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        DestinationConfig d = destination("Lab", listener.port());
        enqueue(d);
        await("existing dead letter", () -> count(d, MessageStatus.DEAD_LETTER) == 1);

        List<AlertMonitor.Raised> seen = new CopyOnWriteArrayList<>();
        monitor.addListener(seen::add);
        monitor.start(Duration.ofMinutes(10));
        monitor.flush();
        assertThat(seen).as("messages dead-lettered before start do not alert").isEmpty();

        enqueue(d);
        await("second dead letter", () -> count(d, MessageStatus.DEAD_LETTER) == 2);
        await("dead-letter alert", () -> !seen.isEmpty());
        monitor.flush();
        assertThat(seen).singleElement().satisfies(r -> {
            assertThat(r.alert().kind()).isEqualTo(Alert.Kind.DEAD_LETTER);
            assertThat(r.alert().title()).isEqualTo("1 message(s) dead-lettered for Lab");
            assertThat(r.errors()).isEmpty();
        });
        assertThat(webhookBodies).singleElement().satisfies(b -> assertThat(b)
                .contains("\"text\":\"HL7 Sender [CRITICAL] 1 message(s) dead-lettered for Lab")
                .contains("\"kind\":\"DEAD_LETTER\"").doesNotContain("MRN-PHI-123"));
        assertThat(smtp.messages()).singleElement().satisfies(m -> assertThat(m)
                .contains("Subject: HL7 Sender [CRITICAL] 1 message(s) dead-lettered for Lab")
                .contains("To: ops@example.org").doesNotContain("MRN-PHI-123"));

        // A receiver that is down opens the circuit: one alert, not one per check.
        DestinationConfig down = engine.saveDestination(config("Down", freePort())
                .withRetry(new RetryPolicy(0, 10, 10, 0)).withCircuitBreaker(new CircuitBreakerSettings(2, 60_000)));
        enqueue(down);
        await("circuit alert", () -> seen.stream().anyMatch(r -> r.alert().kind() == Alert.Kind.CIRCUIT_OPEN));
        monitor.checkNow();
        monitor.checkNow();
        assertThat(seen).filteredOn(r -> r.alert().kind() == Alert.Kind.CIRCUIT_OPEN).singleElement()
                .satisfies(r -> assertThat(r.alert().destinationName()).isEqualTo("Down"));
        assertThat(engine.state(down.id()).status()).isEqualTo(DestinationState.Status.CIRCUIT_OPEN);
        assertThat(monitor.recent()).hasSize(2);
    }

    @Test
    void deadLetterThresholdAndDisabledRules() throws Exception {
        alertSettings.set(new AlertSettings(true, true, 2, false, false, "", null));
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        DestinationConfig d = destination("Batch", listener.port());
        monitor.start(Duration.ofMinutes(10));
        enqueue(d);
        await("first dead letter", () -> count(d, MessageStatus.DEAD_LETTER) == 1);
        monitor.checkNow();
        assertThat(monitor.recent()).isEmpty();
        enqueue(d);
        await("second dead letter", () -> count(d, MessageStatus.DEAD_LETTER) == 2);
        monitor.checkNow();
        assertThat(monitor.recent()).singleElement()
                .satisfies(r -> assertThat(r.alert().title()).startsWith("2 message(s)"));
    }

    @Test
    void expiringCertificatesAlertOncePerDay() throws Exception {
        TestCertificates certs = new TestCertificates(Files.createDirectories(dir.resolve("certs")));
        engine.saveDestination(DestinationConfig.of("TLS EHR", "localhost", 2575).withPaused(true)
                .withTls(new TlsSettings(true, certs.expiringCert.toString(), "", true, null)));
        monitor.start(Duration.ofMinutes(10));
        monitor.checkNow();
        monitor.checkNow();
        assertThat(monitor.recent()).singleElement().satisfies(r -> {
            assertThat(r.alert().kind()).isEqualTo(Alert.Kind.CERTIFICATE);
            assertThat(r.alert().severity()).isEqualTo(Alert.Severity.WARNING);
            assertThat(r.alert().message()).contains("CN=soon-expiring").contains("expires in");
        });
    }

    @Test
    void channelTestReportsEachChannel() {
        webhookStatus.set(500);
        AlertSettings s = new AlertSettings(true, true, 1, true, true, webhookUrl(),
                new AlertSettings.EmailSettings(true, "127.0.0.1", smtp.port(),
                        AlertSettings.EmailSettings.Security.NONE, "", "hl7@example.org", "ops@example.org"));
        assertThat(AlertMonitor.test(s, null, secrets, Clock.systemUTC()))
                .containsExactly("Webhook: failed - Webhook returned HTTP 500", "E-mail: sent");
        assertThat(smtp.messages()).singleElement().satisfies(m -> assertThat(m).contains("Test alert"));
        assertThat(AlertMonitor.test(AlertSettings.defaults(), null, secrets, Clock.systemUTC()))
                .containsExactly("No webhook or e-mail channel is configured");
        assertThat(AlertMonitor.test(new AlertSettings(true, true, 1, true, true, "ftp://x", null), null, secrets,
                Clock.systemUTC())).singleElement().asString().contains("must start with https://");
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics

    @Test
    void diagnosticsBundleHasNoSecrets() throws Exception {
        AppPaths paths = new AppPaths(dir.resolve("config"), dir.resolve("data"), dir.resolve("logs"));
        Files.createDirectories(paths.logDir());
        Files.writeString(paths.logDir().resolve("hl7-sender.log"), String.join("\n",
                "INFO started",
                "DEBUG connecting with password=hunter2 and token: abc123",
                "WARN proxy https://alice:s3cret@proxy.example.org:8080 failed",
                "WARN Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x.y"));
        Files.writeString(paths.logDir().resolve("not-a-log.bin"), "ignored");
        AppSettings settings = AppSettings.defaults().withAlerts(new AlertSettings(true, true, 1, true, true,
                "https://hooks.slack.com/services/T000/B000/SECRETTOKEN", null));
        DestinationConfig d = destination("Mirth", 6661).withNotes("Contact: interface team");
        Path zip = dir.resolve("diag.zip");

        List<String> names = DiagnosticsBundle.write(new DiagnosticsBundle.Input(paths, settings, List.of(d),
                Map.of(d.id(), engine.state(d.id())), Map.of(d.id(), store.counts(d.id())), "test store", true,
                List.of("2026-09-25T10:00:00Z [CRITICAL] 3 message(s) dead-lettered for Mirth")), zip,
                Clock.systemUTC());

        assertThat(names).containsExactly("README.txt", "system.txt", "settings.json", "destinations.json",
                "queue.txt", "alerts.txt", "logs/hl7-sender.log");
        Map<String, String> entries = new HashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().forEach(e -> {
                try {
                    entries.put(e.getName(), new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
        assertThat(entries.get("settings.json")).contains("https://hooks.slack.com/...")
                .doesNotContain("SECRETTOKEN");
        assertThat(entries.get("logs/hl7-sender.log")).contains("password=***", "token: ***",
                "https://***@proxy.example.org", "Bearer ***").doesNotContain("hunter2", "abc123", "s3cret", "eyJ");
        assertThat(entries.get("system.txt")).contains("Secret store: test store", "Queue encrypted: true");
        assertThat(entries.get("queue.txt")).contains("Mirth", "127.0.0.1:6661", "DEAD_LETTER=0");
        assertThat(entries.get("destinations.json")).contains("interface team");
        assertThat(entries.get("alerts.txt")).contains("dead-lettered for Mirth");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Minimal SMTP server: no TLS, no authentication, records each message's DATA. */
    private static final class FakeSmtp implements AutoCloseable {
        private final ServerSocket server;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Thread thread;

        FakeSmtp() throws IOException {
            server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            thread = Thread.ofPlatform().daemon().start(this::run);
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> messages() {
            return messages;
        }

        private void run() {
            while (!server.isClosed()) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                             StandardCharsets.UTF_8));
                     OutputStream raw = s.getOutputStream();
                     PrintWriter out = new PrintWriter(raw, true, StandardCharsets.UTF_8)) {
                    reply(out, "220 fake ESMTP");
                    String line;
                    while ((line = in.readLine()) != null) {
                        String cmd = line.toUpperCase(java.util.Locale.ROOT);
                        if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
                            reply(out, "250 fake");
                        } else if (cmd.startsWith("DATA")) {
                            reply(out, "354 go ahead");
                            StringBuilder data = new StringBuilder();
                            while ((line = in.readLine()) != null && !line.equals(".")) {
                                data.append(line).append('\n');
                            }
                            messages.add(data.toString());
                            reply(out, "250 queued");
                        } else if (cmd.startsWith("QUIT")) {
                            reply(out, "221 bye");
                            break;
                        } else {
                            reply(out, "250 OK");
                        }
                    }
                } catch (IOException e) {
                    // Closed.
                }
            }
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
