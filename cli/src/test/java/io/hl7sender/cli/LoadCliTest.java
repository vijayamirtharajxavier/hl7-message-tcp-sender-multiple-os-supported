package io.hl7sender.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Timeout(60)
class LoadCliTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\n"
            + "EVN|A01|20260101\nPID|1||${RANDOM_MRN}^^^H^MR||DOE^JANE\nPV1|1|I\n";

    @TempDir
    Path dir;

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private TestListener listener;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        file = Files.writeString(dir.resolve("adt.hl7"), MESSAGE);
    }

    @AfterEach
    void tearDown() {
        listener.close();
    }

    private int run(String... args) {
        CommandLine cli = Hl7SendCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    private String port() {
        return String.valueOf(listener.port());
    }

    @Test
    void printsAReportAndProgress() {
        int code = run("load", "-H", "127.0.0.1", "-p", port(), "-c", "4", "-n", "100", file.toString());

        assertThat(code).isZero();
        assertThat(received).hasSize(100);
        assertThat(out.toString())
                .contains("Load test finished: 4 connections, unlimited rate, 100 messages")
                .contains("100 sent, 100 accepted, 0 not accepted")
                .contains("Throughput").contains("p95").contains("ACCEPTED");
        assertThat(err.toString()).startsWith("Load test: 4 connections");
    }

    @Test
    void jsonReportAndThresholdsForPipelines() throws IOException {
        int code = run("load", "-H", "127.0.0.1", "-p", port(), "-n", "20", "-r", "100", "--max-error-percent", "0",
                "--max-p95", "5000", "--json", "-q", file.toString());

        assertThat(code).isZero();
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.get("accepted").asLong()).isEqualTo(20);
        assertThat(json.get("latency").get("p95Ms").asDouble()).isPositive();
        assertThat(json.get("outcomes").get("ACCEPTED").asLong()).isEqualTo(20);
        assertThat(json.get("thresholds").get("passed").asBoolean()).isTrue();
        assertThat(json.get("exitCode").asInt()).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void missedThresholdExitsWithTen() throws IOException {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        int code = run("load", "-H", "127.0.0.1", "-p", port(), "-n", "5", "--max-error-percent", "0", "--json",
                file.toString());

        assertThat(code).isEqualTo(ExitCodes.THRESHOLD);
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.get("thresholds").get("passed").asBoolean()).isFalse();
        assertThat(json.get("thresholds").get("failures").get(0).asText()).contains("error rate 100.0%");
        assertThat(json.get("errors").get(0).asText()).startsWith("APPLICATION_REJECT");
    }

    @Test
    void sendsEveryMessageOfABatchFileInTurn() throws IOException {
        Path batch = Files.writeString(dir.resolve("two.hl7"), MESSAGE + MESSAGE.replace("ADT^A01^ADT_A01", "ADT^A08"));
        int code = run("load", "-H", "127.0.0.1", "-p", port(), "-n", "10", "-q", batch.toString());

        assertThat(code).isZero();
        assertThat(received.stream().filter(m -> m.messageType().startsWith("ADT^A08"))).hasSize(5);
    }

    @Test
    void usageErrors() {
        assertThat(run("load", file.toString())).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("--host and --port");
        assertThat(run("load", "-H", "127.0.0.1", "-p", port(), "-t", "soon", file.toString()))
                .isEqualTo(ExitCodes.USAGE);
        assertThat(run("load", "-H", "127.0.0.1", "-p", port(), "-c", "0", file.toString()))
                .isEqualTo(ExitCodes.USAGE);
        assertThat(run("load", "-H", "127.0.0.1", "-p", port(), dir.resolve("missing.hl7").toString()))
                .isEqualTo(ExitCodes.INVALID_INPUT);
    }

    @Test
    void durationConverter() {
        LoadCommand.DurationConverter c = new LoadCommand.DurationConverter();
        assertThat(c.convert("90")).isEqualTo(Duration.ofSeconds(90));
        assertThat(c.convert("90s")).isEqualTo(Duration.ofSeconds(90));
        assertThat(c.convert("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(c.convert("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(c.convert("1H")).isEqualTo(Duration.ofHours(1));
    }
}
