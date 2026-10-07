package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.QueueFollowUps;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.ResponseRule;
import io.hl7sender.core.listener.ResponseRules;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.Mllp;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "listen", mixinStandardHelpOptions = true,
        description = {"Run a mock MLLP receiver that acknowledges messages in the chosen way.",
            "With --rules it is a responder: rules choose the response by message type and field values, and can "
                + "queue a follow-up message (such as a result for each order) to a saved destination. If no app "
                + "or service is running, this command delivers the follow-ups itself.",
            "Stop it with Ctrl+C."})
final class ListenCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Spec
    private CommandSpec spec;

    @Option(names = {"-p", "--port"}, defaultValue = "2575",
            description = "Port to listen on (default: ${DEFAULT-VALUE}).")
    private int port;

    @Option(names = "--bind", defaultValue = "0.0.0.0",
            description = "Interface to bind (default: ${DEFAULT-VALUE}; use 127.0.0.1 for local only).")
    private String bind;

    @Option(names = {"-m", "--mode"}, defaultValue = "ACCEPT",
            description = "Response: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private ResponseMode mode;

    @Option(names = "--delay", defaultValue = "0", paramLabel = "MS", description = "Delay before responding.")
    private int delayMs;

    @Option(names = "--commit-codes", description = "Use enhanced-mode CA/CE/CR instead of AA/AE/AR.")
    private boolean commitCodes;

    @Option(names = "--app-ack-port", defaultValue = "0", paramLabel = "PORT",
            description = "With --commit-codes: after each CA, send an application ACK to this port on the sender, as "
                    + "the message's MSH-16 asks (AL always, ER only errors, SU only success). 0 = off.")
    private int appAckPort;

    @Option(names = "--app-ack-code", defaultValue = "AA", paramLabel = "CODE",
            description = "Application ACK to send: AA, AE or AR (default: ${DEFAULT-VALUE}).")
    private io.hl7sender.core.ack.AckCode appAckCode;

    @Option(names = "--app-ack-delay", defaultValue = "1000", paramLabel = "MS",
            description = "Wait this long after the CA before sending the application ACK (default: ${DEFAULT-VALUE}).")
    private int appAckDelayMs;

    @Option(names = "--text", defaultValue = "", description = "MSA-3 text to include in responses.")
    private String text;

    @Option(names = "--charset", defaultValue = "UTF-8", description = "Character set (default: ${DEFAULT-VALUE}).")
    private Charset charset;

    @Option(names = "--rules", paramLabel = "FILE",
            description = "Responder rules (JSON), checked before the default response. Export them from the Test "
                    + "Listener tab, or see the user guide.")
    private Path rulesFile;

    @Option(names = "--save-dir", paramLabel = "DIR", description = "Save every received message to DIR, one file "
            + "each.")
    private Path saveDir;

    @Override
    public Integer call() throws InterruptedException, IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (mode == ResponseMode.CUSTOM) {
            err.println("CUSTOM responses are set per rule; use --rules");
            return ExitCodes.USAGE;
        }
        List<ResponseRule> rules = List.of();
        if (rulesFile != null) {
            try {
                rules = ResponseRules.read(rulesFile);
            } catch (IOException e) {
                err.println("Cannot use " + rulesFile + ": " + e.getMessage());
                return ExitCodes.INVALID_INPUT;
            }
        }
        ListenerSettings.AppAck appAck = null;
        if (appAckPort != 0) {
            if (!commitCodes) {
                err.println("--app-ack-port needs --commit-codes: application ACKs follow a commit ACK (CA)");
                return ExitCodes.USAGE;
            }
            try {
                appAck = new ListenerSettings.AppAck(appAckPort, appAckCode, appAckDelayMs);
            } catch (IllegalArgumentException e) {
                err.println(e.getMessage());
                return ExitCodes.USAGE;
            }
        }
        ListenerSettings settings = new ListenerSettings(mode, delayMs, commitCodes, text, charset,
                Mllp.DEFAULT_MAX_FRAME_BYTES, rules, saveDir, appAck);
        Workspace ws = null;
        if (rules.stream().anyMatch(r -> r.followUp() != null)) {
            ws = Workspace.open(Permission.SEND, "queue follow-up messages");
            List<String> missing = QueueFollowUps.missingDestinations(rules, ws.engine().destinations());
            if (!missing.isEmpty()) {
                ws.close();
                err.println("Follow-up destination(s) not found: " + String.join(", ", missing)
                        + ". List them with: hl7send destination list");
                return ExitCodes.USAGE;
            }
        }
        TestListener listener = new TestListener(bind, port, settings, m -> print(out, m));
        Workspace workspace = ws;
        if (workspace != null) {
            if (workspace.owner()) {
                workspace.startDelivery();
            }
            listener.setFollowUpHandler(new QueueFollowUps(workspace.engine(), m -> {
                printFollowUp(out, m.messageType(), m.controlId(), m.id());
                if (!workspace.owner()) {
                    workspace.notifyOwner();
                }
            }));
        }
        try {
            listener.start();
        } catch (IOException | IllegalArgumentException e) {
            err.println("Cannot listen on " + bind + ":" + port + ": " + e.getMessage());
            if (workspace != null) {
                workspace.close();
            }
            return ExitCodes.CONNECTION_FAILURE;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            listener.close();
            if (workspace != null) {
                workspace.close();
            }
            stopped.countDown();
        }));
        out.printf("Listening on %s:%d, responding with %s%s. Press Ctrl+C to stop.%n", bind, listener.port(), mode,
                rules.isEmpty() ? "" : " unless one of " + rules.size() + " rule(s) matches");
        if (workspace != null) {
            out.println(workspace.owner() ? "Follow-up messages are queued and delivered by this command."
                    : "Follow-up messages are queued for the running app or service to deliver.");
        }
        out.flush();
        stopped.await();
        return ExitCodes.OK;
    }

    private static synchronized void printFollowUp(PrintWriter out, String type, String controlId, long id) {
        out.printf("%s  follow-up %s [%s] queued as message %d%n",
                TIME.format(LocalTime.now()), type, controlId, id);
        out.flush();
    }

    private static synchronized void print(PrintWriter out, ReceivedMessage m) {
        out.printf("%s  %-21s  %-10s  %-20s  -> %s%s%n",
                TIME.format(LocalTime.ofInstant(m.receivedAt(), ZoneId.systemDefault())),
                m.remote(),
                m.messageType().isEmpty() ? "?" : m.messageType(),
                m.controlId(),
                m.responseCode(),
                m.rule().isEmpty() ? "" : "  (rule: " + m.rule() + ")");
        out.flush();
    }
}
