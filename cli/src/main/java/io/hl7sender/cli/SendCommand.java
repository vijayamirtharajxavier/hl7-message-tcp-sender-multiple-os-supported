package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.ack.AckError;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.tls.TlsOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "send", mixinStandardHelpOptions = true,
        description = {"Send one HL7 message now and wait for its acknowledgment (not queued, no retries).",
            "Give --host and --port, or --destination to use a destination saved in the app, including its TLS "
                + "settings. To queue with retries instead, use 'hl7send queue send'."})
final class SendCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-H", "--host"}, description = "Receiver host name or IP address.")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Receiver TCP port.")
    private Integer port;

    @Option(names = {"-d", "--destination"}, description = "Use a saved destination (address, timeouts, charset, "
            + "ACK mode and TLS) instead of --host/--port.")
    private String destinationName;

    @Mixin
    private Output output;

    @Parameters(index = "0", paramLabel = "FILE", description = "Message file, or '-' to read standard input.")
    private Path file;

    @Option(names = "--connect-timeout", paramLabel = "MS",
            description = "TCP connect timeout in milliseconds (default: 5000, or the destination's).")
    private Integer connectTimeoutMs;

    @Option(names = "--ack-timeout", paramLabel = "MS",
            description = "How long to wait for the ACK, in milliseconds (default: 30000, or the destination's).")
    private Integer ackTimeoutMs;

    @Option(names = "--charset",
            description = "Character set for the file and the wire (default: UTF-8, or the destination's).")
    private Charset charset;

    @Option(names = "--no-ack", description = "Do not wait for an acknowledgment (receiver never sends one).")
    private boolean noAck;

    @Option(names = "--keep-control-id", description = "Send MSH-10 as written instead of generating a new one.")
    private boolean keepControlId;

    @Option(names = "--keep-timestamp", description = "Send MSH-7 as written instead of the current time.")
    private boolean keepTimestamp;

    @Option(names = {"-v", "--verbose"}, description = "Print the sent message and the raw acknowledgment.")
    private boolean verbose;

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (destinationName == null && (host == null || port == null)) {
            err.println("Give --host and --port, or --destination");
            return ExitCodes.USAGE;
        }
        MllpClientConfig destination;
        TlsOptions tls = null;
        AckMode ackMode = noAck ? AckMode.NO_ACK : AckMode.EXPECT_ACK;
        try {
            if (destinationName != null) {
                try (Workspace ws = Workspace.open(Permission.SEND, "send messages")) {
                    DestinationConfig d = ws.destination(destinationName);
                    if (!d.isMllp()) {
                        err.println("'" + d.name() + "' sends by " + d.transport() + "; 'send' is for MLLP. Use "
                                + "'hl7send queue send -d " + d.name() + "' instead.");
                        return ExitCodes.USAGE;
                    }
                    destination = d.clientConfig();
                    tls = ws.engine().tlsOptions(d).orElse(null);
                    if (!noAck) {
                        ackMode = d.ackMode();
                    }
                }
            } else {
                destination = MllpClientConfig.of(host, port);
            }
            destination = destination.withTimeouts(
                    connectTimeoutMs != null ? connectTimeoutMs : destination.connectTimeoutMs(),
                    ackTimeoutMs != null ? ackTimeoutMs : destination.responseTimeoutMs());
            if (charset != null) {
                destination = destination.withCharset(charset);
            }
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return ExitCodes.USAGE;
        }
        String text;
        try {
            text = MessageInput.read(file, destination.charset());
        } catch (IOException e) {
            err.println("Cannot read " + file + ": " + e.getMessage());
            return ExitCodes.INVALID_INPUT;
        }
        SendOptions options = new SendOptions(!keepControlId, !keepTimestamp, ackMode);

        SendResult result = new Hl7Sender().send(destination, tls, text, options);
        int code = ExitCodes.of(result.outcome());
        if (output.json) {
            Output.printJson(out, json(result, code));
            return code;
        }

        for (ValidationIssue issue : result.validation().issues()) {
            (issue.severity() == ValidationIssue.Severity.ERROR ? err : out).println(issue);
        }
        if (verbose) {
            out.println("--- Sent ---");
            out.println(Hl7Text.toDisplay(result.sentMessage()));
            result.rawResponse().ifPresent(r -> {
                out.println("--- Received ---");
                out.println(Hl7Text.toDisplay(r).isEmpty() ? r : Hl7Text.toDisplay(r));
            });
            out.println("------------");
        }
        out.println(result.summary());
        result.ack().ifPresent(ack -> {
            for (AckError e : ack.errors()) {
                out.println("  ERR " + e.describe());
            }
        });
        out.flush();
        return code;
    }

    private static Map<String, Object> json(SendResult r, int code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", r.outcome().name());
        m.put("description", r.outcome().description());
        m.put("detail", r.detail());
        m.put("controlId", r.controlId());
        m.put("ackCode", r.ack().map(a -> a.code().name()).orElse(null));
        m.put("ackControlId", r.ack().map(a -> a.controlId()).orElse(null));
        m.put("ackText", r.ack().map(a -> a.text()).orElse(null));
        m.put("errors", r.ack().map(a -> a.errors().stream().map(AckError::describe).toList()).orElse(List.of()));
        m.put("validation", JsonViews.issues(r.validation()));
        m.put("exitCode", code);
        return m;
    }
}
