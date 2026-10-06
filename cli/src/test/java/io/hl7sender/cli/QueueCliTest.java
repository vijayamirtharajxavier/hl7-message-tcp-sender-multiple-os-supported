package io.hl7sender.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.api.ApiEndpoint;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Queue, dead-letter, destination, serve and service commands, with JSON output and exit codes. */
@Timeout(120)
class QueueCliTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\n"
            + "EVN|A01|20260101\nPID|1||MRN1^^^H^MR||DOE^JANE\nPV1|1|I\n";

    @TempDir
    Path dir;

    private Path home;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private Path file;
    private StringWriter out = new StringWriter();
    private StringWriter err = new StringWriter();

    @BeforeEach
    void setUp() throws IOException {
        home = dir.resolve("home");
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        file = Files.writeString(dir.resolve("msg.hl7"), MESSAGE);
        Path profile = dir.resolve("profiles.json");
        DestinationProfiles.write(List.of(DestinationConfig.of("Lab", "127.0.0.1", listener.port())
                .withTimeouts(1_000, 1_000)), profile);
        assertThat(run("destination", "import", profile.toString())).isZero();
        assertThat(out.toString()).contains("Imported Lab (127.0.0.1:" + listener.port() + ")");
    }

    @AfterEach
    void tearDown() {
        listener.close();
        ServeCommand.requestStop();
        System.clearProperty(AppPaths.HOME_PROPERTY);
    }

    private int run(String... args) {
        out = new StringWriter();
        err = new StringWriter();
        CommandLine cli = Hl7SendCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        return cli.execute(all.toArray(String[]::new));
    }

    private JsonNode json() throws IOException {
        return JsonViews.COMPACT.readTree(out.toString());
    }

    private void mode(ResponseMode mode) {
        listener.updateSettings(listener.settings().withMode(mode));
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void queueSendWaitsForTheAckAndExitCodesFollowIt() throws Exception {
        assertThat(run("queue", "send", "-d", "lab", file.toString(), "--wait", "20", "--json")).isZero();
        JsonNode r = json();
        assertThat(r.path("queued").get(0).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(r.path("exitCode").asInt()).isZero();
        assertThat(received).hasSize(1);

        mode(ResponseMode.ERROR);
        assertThat(run("queue", "send", "-d", "Lab", file.toString(), "--wait", "20")).isEqualTo(3);
        assertThat(out.toString()).contains("DEAD_LETTER");
        assertThat(run("queue", "status", "--fail-if-dead")).isEqualTo(9);
        assertThat(out.toString()).contains("Lab").contains("127.0.0.1:" + listener.port());
        assertThat(run("dlq", "list", "--json")).isZero();
        assertThat(json().path("messages")).hasSize(1);

        mode(ResponseMode.ACCEPT);
        assertThat(run("dlq", "requeue", "--all", "--wait", "20", "--json")).isZero();
        assertThat(json().path("requeued").get(0).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(run("dlq", "list")).isZero();
        assertThat(out.toString()).contains("No messages.");

        assertThat(run("queue", "status", "--json")).isZero();
        JsonNode status = json();
        assertThat(status.path("deliveredByOtherProcess").asBoolean()).isFalse();
        assertThat(status.path("destinations").get(0).path("counts").path("ACKNOWLEDGED").asInt()).isEqualTo(2);
        assertThat(run("queue", "status", "--fail-if-dead")).isZero();
    }

    @Test
    void schedulesAreManagedAndRunFromTheCommandLine() throws Exception {
        assertThat(run("schedule", "add", "Morning", file.toString(), "--cron", "0 7 * * 1-5", "-d", "lab",
                "--count", "2", "--zone", "UTC", "--json")).isZero();
        JsonNode added = json();
        assertThat(added.path("name").asText()).isEqualTo("Morning");
        assertThat(added.path("destination").asText()).isEqualTo("Lab");
        assertThat(added.path("nextRunAt").asText()).endsWith("T07:00:00Z");

        assertThat(run("schedule", "list")).isZero();
        assertThat(out.toString()).contains("Morning").contains("0 7 * * 1-5").contains("next ");

        // Run now: two copies, each with its own control ID, delivered and acknowledged.
        assertThat(run("schedule", "run", "morning", "--wait", "20", "--json")).isZero();
        JsonNode ran = json();
        assertThat(ran.path("result").asText()).startsWith("2 queued (batch B");
        assertThat(ran.path("messages")).hasSize(2);
        assertThat(ran.path("messages").get(0).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(received).extracting(ReceivedMessage::controlId).doesNotHaveDuplicates().hasSize(2);

        assertThat(run("schedule", "disable", "Morning")).isZero();
        assertThat(run("schedule", "list", "--json")).isZero();
        JsonNode listed = json().path("schedules").get(0);
        assertThat(listed.path("enabled").asBoolean()).isFalse();
        assertThat(listed.path("nextRunAt").isNull()).isTrue();
        assertThat(listed.path("lastResult").asText()).startsWith("2 queued");

        assertThat(run("schedule", "add", "Bad", file.toString(), "--cron", "every day", "-d", "Lab"))
                .isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("five fields");
        assertThat(run("schedule", "preview", "0 9 * * MON", "--zone", "UTC", "-n", "2")).isZero();
        assertThat(out.toString().lines()).hasSize(2).allMatch(l -> l.contains("09:00") && l.contains("Monday"));
        assertThat(run("schedule", "remove", "Morning")).isZero();
        assertThat(run("schedule", "run", "Morning")).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void replayQueuesCopiesOfEarlierMessages() throws Exception {
        assertThat(run("queue", "send", "-d", "Lab", file.toString(), "--wait", "20", "--json")).isZero();
        long id = json().path("queued").get(0).path("id").asLong();
        String controlId = json().path("queued").get(0).path("controlId").asText();

        assertThat(run("replay", String.valueOf(id), "--wait", "20", "--json")).isZero();
        JsonNode same = json();
        assertThat(same.path("replayed").get(0).path("controlId").asText()).isEqualTo(controlId);
        assertThat(same.path("replayed").get(0).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        String batch = same.path("batchId").asText();
        assertThat(batch).startsWith("R");

        assertThat(run("replay", "--batch", batch, "--new-control-ids", "--wait", "20", "--json")).isZero();
        assertThat(json().path("replayed").get(0).path("controlId").asText()).isNotEqualTo(controlId);
        assertThat(received).hasSize(3);

        assertThat(run("replay", "999999")).isEqualTo(ExitCodes.INVALID_INPUT);
        assertThat(err.toString()).contains("does not exist");
        assertThat(run("replay", String.valueOf(id), "-d", "Nowhere")).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void scriptTestRunsAFileOrADestinationsScript() throws Exception {
        Path script = Files.writeString(dir.resolve("s.js"), "msg.set('MSH-6', 'TESTFAC'); log(msg.type());");
        assertThat(run("script", "test", "--script", script.toString(), file.toString())).isZero();
        assertThat(out.toString()).contains("|TESTFAC|");
        assertThat(err.toString()).contains("log: ADT^A01");

        Path filter = Files.writeString(dir.resolve("f.js"), "filter('not today')");
        assertThat(run("script", "test", "--script", filter.toString(), file.toString(), "--json"))
                .isEqualTo(ExitCodes.INVALID_INPUT);
        assertThat(json().path("filterReason").asText()).isEqualTo("not today");

        Path bad = Files.writeString(dir.resolve("b.js"), "while (true) {}");
        assertThat(run("script", "test", "--script", bad.toString(), file.toString()))
                .isEqualTo(ExitCodes.INVALID_INPUT);
        assertThat(err.toString()).contains("was stopped");
        assertThat(run("script", "test", "-d", "Lab", file.toString())).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("has no script");
    }

    @Test
    void listenChecksResponderOptionsBeforeListening() throws Exception {
        assertThat(run("listen", "--mode", "CUSTOM")).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("use --rules");
        assertThat(run("listen", "--rules", dir.resolve("missing.json").toString()))
                .isEqualTo(ExitCodes.INVALID_INPUT);
        Path bad = Files.writeString(dir.resolve("bad.json"), "[{\"name\":\"x\",\"field\":\"PID3\"}]");
        assertThat(run("listen", "--rules", bad.toString())).isEqualTo(ExitCodes.INVALID_INPUT);
        assertThat(err.toString()).contains("Invalid rule").contains("not a field");
        Path rules = Files.writeString(dir.resolve("rules.json"), "[{\"name\":\"Results\",\"messageType\":\"ORM\","
                + "\"followUp\":{\"destination\":\"Nowhere\",\"template\":\"MSH|^~\\\\&|LAB\"}}]");
        assertThat(run("listen", "--rules", rules.toString())).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("Follow-up destination(s) not found: Nowhere");
    }

    @Test
    void batchFilesAreSplitAndQueuedWithoutWaiting() throws Exception {
        Path batch = Files.writeString(dir.resolve("batch.hl7"), MessageSplitter.toBatchFile(
                List.of(MESSAGE, MESSAGE, MESSAGE)));
        assertThat(run("queue", "send", "-d", "Lab", batch.toString())).isZero();
        assertThat(out.toString()).contains("3 message(s) queued for Lab (batch B")
                .contains("No app or service is running");
        assertThat(run("queue", "list", "-d", "Lab", "--json")).isZero();
        JsonNode list = json().path("messages");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("status").asText()).isEqualTo("QUEUED");
        assertThat(received).isEmpty();

        long id = list.get(0).path("id").asLong();
        assertThat(run("queue", "show", String.valueOf(id), "--payload", "--json")).isZero();
        assertThat(json().path("payload").asText()).contains("DOE^JANE");
        assertThat(run("queue", "delete", String.valueOf(id))).isZero();
        assertThat(run("queue", "retry", String.valueOf(id))).isEqualTo(1);

        Path bad = Files.writeString(dir.resolve("bad.hl7"), "MSH|^~\\&|A|B|C|D|20260101||||P|2.5.1\n");
        assertThat(run("queue", "send", "-d", "Lab", bad.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("Not queued:");
        assertThat(run("queue", "send", "-d", "Nowhere", file.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("No destination named 'Nowhere'");
    }

    @Test
    void directSendAndValidateSupportJson() throws Exception {
        assertThat(run("send", "-d", "Lab", file.toString(), "--json")).isZero();
        JsonNode r = json();
        assertThat(r.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(r.path("ackCode").asText()).isEqualTo("AA");
        mode(ResponseMode.REJECT);
        assertThat(run("send", "-d", "Lab", file.toString(), "--json")).isEqualTo(4);
        assertThat(json().path("ackCode").asText()).isEqualTo("AR");
        assertThat(run("send", file.toString())).isEqualTo(2);

        Path bad = Files.writeString(dir.resolve("bad.hl7"), "PID|1");
        assertThat(run("validate", bad.toString(), "--json")).isEqualTo(1);
        assertThat(json().path("valid").asBoolean()).isFalse();
        assertThat(json().path("issues").get(0).path("severity").asText()).isEqualTo("ERROR");
    }

    @Test
    void destinationsCanBeListedPausedAndExported() throws Exception {
        assertThat(run("destination", "list", "--json")).isZero();
        assertThat(json().path("destinations").get(0).path("name").asText()).isEqualTo("Lab");
        assertThat(run("dest", "pause", "Lab")).isZero();
        assertThat(run("destination", "list")).isZero();
        assertThat(out.toString()).contains("yes");
        assertThat(run("destination", "resume", "Lab")).isZero();
        Path exported = dir.resolve("out.json");
        assertThat(run("destination", "export", exported.toString())).isZero();
        assertThat(Files.readString(exported)).contains("\"Lab\"");
        assertThat(run("destination", "import", exported.toString())).isZero();
        assertThat(out.toString()).contains("Imported Lab (2)");
    }

    @Test
    void serveDeliversWhatOtherProcessesQueueAndServesTheApi() throws Exception {
        AtomicInteger serveExit = new AtomicInteger(-1);
        StringWriter serveOut = new StringWriter();
        Thread server = Thread.ofPlatform().start(() -> {
            CommandLine cli = Hl7SendCli.commandLine();
            cli.setOut(new PrintWriter(serveOut, true));
            cli.setErr(new PrintWriter(serveOut, true));
            serveExit.set(cli.execute("serve", "--api", "--api-port", "0", "--home", home.toString()));
        });
        AppPaths paths = new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        await("serve to start", () -> serveOut.toString().contains("Press Ctrl+C"));
        assertThat(ApiEndpoint.read(paths)).isPresent();
        assertThat(serveOut.toString()).contains("delivering for 1 destination(s)").contains("Local API:");

        // Another process queues while serve delivers: serve is told through the API and delivers at once.
        long start = System.currentTimeMillis();
        assertThat(run("queue", "send", "-d", "Lab", file.toString(), "--wait", "20")).isZero();
        assertThat(System.currentTimeMillis() - start).isLessThan(15_000);
        assertThat(received).hasSize(1);
        assertThat(run("queue", "status", "--json")).isZero();
        JsonNode status = json();
        assertThat(status.path("deliveredByOtherProcess").asBoolean()).isTrue();
        assertThat(status.path("destinations").get(0).path("state").asText()).isIn("IDLE", "SENDING");
        assertThat(run("api", "status")).isZero();
        assertThat(run("api", "token")).isZero();
        assertThat(out.toString().trim()).hasSize(64);

        // Only one process delivers from a queue.
        assertThat(run("serve")).isEqualTo(7);
        assertThat(err.toString()).contains("already delivering");

        ServeCommand.requestStop();
        server.join(20_000);
        assertThat(serveExit.get()).isZero();
        assertThat(serveOut.toString()).contains("Stopped.");
        assertThat(ApiEndpoint.read(paths)).isEmpty();
        assertThat(run("api", "status")).isEqualTo(7);
    }

    @Test
    void serviceDefinitionsForEachPlatform() throws Exception {
        assertThat(run("service", "print", "--platform", "linux")).isZero();
        String unit = out.toString();
        // systemd escapes backslashes, which matters when this runs on Windows.
        assertThat(unit).contains("[Service]", "Restart=on-failure", "io.hl7sender.cli.Hl7SendCli\" \"serve\"",
                "-Dhl7sender.home=" + home.toAbsolutePath().toString().replace("\\", "\\\\"));
        assertThat(unit.lines().filter(l -> l.startsWith("ExecStart="))).singleElement()
                .satisfies(l -> assertThat(l).startsWith("ExecStart=\"").contains("\"-cp\""));

        assertThat(run("service", "print", "--platform", "macos")).isZero();
        assertThat(parsesAsXml(out.toString())).isTrue();
        assertThat(out.toString()).contains("<string>io.hl7sender.hl7send</string>", "<string>serve</string>");
        assertThat(run("service", "print", "--platform", "windows")).isZero();
        assertThat(parsesAsXml(out.toString())).isTrue();
        assertThat(out.toString()).contains("<id>hl7send</id>", "io.hl7sender.cli.Hl7SendCli serve</arguments>");

        Path target = dir.resolve("svc").resolve("hl7send.def");
        assertThat(run("service", "install", "--file", target.toString(), "--dry-run")).isZero();
        assertThat(target).doesNotExist();
        assertThat(run("service", "install", "--file", target.toString(), "--no-start")).isZero();
        assertThat(target).exists();
        assertThat(run("service", "uninstall", "--file", target.toString(), "--dry-run")).isZero();
        assertThat(target).exists();
    }

    @Test
    void updateCheckReportsNewerVersions() throws Exception {
        com.sun.net.httpserver.HttpServer releases = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        releases.createContext("/latest", ex -> {
            byte[] b = "{\"tag_name\":\"v99.0.0\",\"html_url\":\"https://example.org/99\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        releases.start();
        System.setProperty("hl7sender.updateUrl", "http://127.0.0.1:" + releases.getAddress().getPort() + "/latest");
        try {
            assertThat(run("update", "--json")).isZero();
            assertThat(json().path("updateAvailable").asBoolean()).isTrue();
            assertThat(json().path("latest").asText()).isEqualTo("99.0.0");
            assertThat(run("update")).isZero();
            assertThat(out.toString()).contains("Version 99.0.0 is available", "https://example.org/99");
        } finally {
            System.clearProperty("hl7sender.updateUrl");
            releases.stop(0);
        }
    }

    private static boolean parsesAsXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (Exception e) {
            throw new AssertionError("Invalid XML: " + e.getMessage() + "\n" + xml, e);
        }
    }
}
