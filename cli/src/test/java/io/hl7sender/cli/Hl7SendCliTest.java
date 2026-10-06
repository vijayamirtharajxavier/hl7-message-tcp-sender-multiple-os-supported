package io.hl7sender.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Timeout(30)
class Hl7SendCliTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\n"
            + "EVN|A01|20260101\nPID|1||MRN1^^^H^MR||DOE^JANE\nPV1|1|I\n";

    @TempDir
    Path dir;

    private TestListener listener;
    private Path file;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void setUp() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, m -> { });
        listener.start();
        file = Files.writeString(dir.resolve("msg.hl7"), MESSAGE);
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

    private int send(ResponseMode mode, String... extra) {
        listener.updateSettings(listener.settings().withMode(mode));
        String[] base = {"send", "-H", "127.0.0.1", "-p", String.valueOf(listener.port()), "--ack-timeout", "1000",
            file.toString()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return run(args);
    }

    @Test
    void acceptedExitsZero() {
        assertThat(send(ResponseMode.ACCEPT)).isZero();
        assertThat(out.toString()).contains("ACCEPTED (AA): ADT^A01");
    }

    @Test
    void exitCodesReflectOutcome() {
        assertThat(send(ResponseMode.ERROR)).isEqualTo(3);
        assertThat(send(ResponseMode.REJECT)).isEqualTo(4);
        assertThat(send(ResponseMode.NO_RESPONSE)).isEqualTo(5);
        assertThat(send(ResponseMode.MALFORMED)).isEqualTo(5);
    }

    @Test
    void noAckAndKeepControlId() {
        assertThat(send(ResponseMode.NO_RESPONSE, "--no-ack", "--keep-control-id")).isZero();
        assertThat(out.toString()).contains("SENT_NO_ACK").contains("[C1]");
    }

    @Test
    void verbosePrintsBothMessages() {
        assertThat(send(ResponseMode.ACCEPT, "-v")).isZero();
        assertThat(out.toString()).contains("--- Sent ---").contains("--- Received ---").contains("MSA|AA|");
    }

    @Test
    void missingFileIsInvalidInput() {
        assertThat(run("send", "-H", "127.0.0.1", "-p", "1", dir.resolve("nope.hl7").toString())).isEqualTo(1);
        assertThat(err.toString()).contains("Cannot read");
    }

    @Test
    void badUsageExitsTwo() {
        assertThat(run("send", "-p", "1")).isEqualTo(2);
        assertThat(run("send", "-H", "h", "-p", "99999", file.toString())).isEqualTo(2);
    }

    @Test
    void validateCommand() throws IOException {
        assertThat(run("validate", file.toString())).isZero();
        assertThat(out.toString()).contains("ADT^A01 v2.5.1, 4 segments, 0 errors");
        Path bad = Files.writeString(dir.resolve("bad.hl7"), "PID|1");
        assertThat(run("validate", bad.toString())).isEqualTo(1);
    }

    @Test
    void versionAndHelp() {
        assertThat(run("--version")).isZero();
        assertThat(out.toString()).startsWith("hl7send ");
        assertThat(run("--help")).isZero();
        assertThat(out.toString()).contains("Exit codes:").contains("listen");
    }

    @Test
    void fhirConvertAndSend() throws Exception {
        assertThat(run("fhir", "convert", file.toString())).isZero();
        assertThat(out.toString()).contains("\"resourceType\" : \"Bundle\"").contains("\"family\" : \"DOE\"");
        java.nio.file.Path bundle = dir.resolve("b.json");
        assertThat(run("fhir", "convert", file.toString(), "-o", bundle.toString())).isZero();
        assertThat(java.nio.file.Files.readString(bundle)).contains("Patient");
        assertThat(run("fhir", "convert", dir.resolve("none.hl7").toString())).isNotZero();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/fhir", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = "{\"resourceType\":\"Bundle\",\"entry\":[{\"response\":{\"status\":\"201\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
            assertThat(run("fhir", "send", "--url", base, file.toString())).isZero();
            assertThat(out.toString()).contains("ACCEPTED: HTTP 200, 1 resource(s) stored");
            assertThat(run("fhir", "send", "--url", "ftp://x", file.toString())).isEqualTo(ExitCodes.USAGE);
        } finally {
            server.stop(0);
        }
    }
}
