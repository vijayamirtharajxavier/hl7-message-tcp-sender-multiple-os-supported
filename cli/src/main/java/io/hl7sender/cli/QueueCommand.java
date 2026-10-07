package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.ApiClient;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.queue.BulkItem;
import io.hl7sender.core.queue.BulkProgress;
import io.hl7sender.core.queue.BulkResult;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send queue ...}: the durable queue shared with the desktop app and the service. */
@Command(name = "queue", mixinStandardHelpOptions = true,
        description = "Queue messages and check delivery, using the same queue as the desktop app.",
        subcommands = {QueueCommand.Status.class, QueueCommand.Send.class, QueueCommand.ListCmd.class,
            QueueCommand.Show.class, QueueCommand.Retry.class, QueueCommand.Delete.class})
final class QueueCommand {

    static final Set<MessageStatus> PENDING =
            EnumSet.of(MessageStatus.QUEUED, MessageStatus.IN_FLIGHT, MessageStatus.RETRY_PENDING,
                    MessageStatus.AWAITING_APP_ACK);

    private QueueCommand() {
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show every destination with its delivery state and message counts.")
    static final class Status implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Option(names = "--fail-if-dead", description = "Exit with 9 if any destination has dead-lettered messages.")
        boolean failIfDead;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.VIEW, "see the queue")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                Map<Long, JsonNode> live = liveStates(ws);
                int dead = 0;
                for (DestinationConfig d : ws.store().destinations()) {
                    Map<MessageStatus, Integer> counts = ws.store().counts(d.id());
                    Map<String, Object> row = JsonViews.destination(d, null, counts);
                    JsonNode l = live.get(d.id());
                    if (l != null) {
                        row.put("state", l.path("state").asText());
                        row.put("stateDetail", l.path("stateDetail").asText());
                    } else {
                        row.put("state", ws.owner() ? DestinationState.Status.STOPPED.name() : "UNKNOWN");
                        row.put("stateDetail", ws.owner() ? "No app or service is delivering"
                                : "Delivered by another process");
                    }
                    dead += counts.getOrDefault(MessageStatus.DEAD_LETTER, 0);
                    rows.add(row);
                }
                if (output.json) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("deliveredByOtherProcess", !ws.owner());
                    doc.put("destinations", rows);
                    Output.printJson(out, doc);
                } else if (rows.isEmpty()) {
                    out.println("No destinations. Add one in the app, or: hl7send destination import FILE");
                } else {
                    out.printf("%-24s %-28s %-14s %8s %9s %6s %9s%n", "DESTINATION", "ADDRESS", "STATE", "PENDING",
                            "DELIVERED", "DEAD", "PAUSED");
                    for (Map<String, Object> r : rows) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) r.get("counts");
                        int delivered = (Integer) c.get("ACKNOWLEDGED") + (Integer) c.get("SENT_UNCONFIRMED");
                        out.printf("%-24s %-28s %-14s %8s %9d %6s %9s%n", trim((String) r.get("name"), 24),
                                trim((Boolean) r.get("tls") ? "tls://" + r.get("host") + ":" + r.get("port")
                                        : r.get("host") + ":" + r.get("port"), 28),
                                r.get("state"), r.get("pending"), delivered, r.get("deadLetter"),
                                (Boolean) r.get("paused") ? "yes" : "no");
                    }
                    if (!ws.owner() && live.isEmpty()) {
                        out.println("(Another process delivers from this queue; enable its local API to see live "
                                + "states.)");
                    }
                }
                out.flush();
                return failIfDead && dead > 0 ? ExitCodes.ATTENTION : ExitCodes.OK;
            }
        }

        private static Map<Long, JsonNode> liveStates(Workspace ws) {
            Map<Long, JsonNode> m = new LinkedHashMap<>();
            if (ws.owner()) {
                return m;
            }
            Optional<ApiClient> api = ApiClient.discover(ws.paths());
            if (api.isEmpty()) {
                return m;
            }
            try {
                ApiClient.Response r = api.get().get("destinations");
                if (r.ok()) {
                    for (JsonNode d : JsonViews.COMPACT.readTree(r.body()).path("destinations")) {
                        m.put(d.path("id").asLong(), d);
                    }
                }
            } catch (IOException e) {
                // Not reachable: show counts only.
            }
            return m;
        }
    }

    @Command(name = "send", mixinStandardHelpOptions = true,
            description = {"Add messages to a destination's queue. Each file may hold one message, several "
                + "messages, an HL7 batch (FHS/BHS) or an MLLP capture.",
                "Delivery is done by the running app or service; with --wait, this command waits for the "
                    + "acknowledgments (delivering itself if nothing else is running)."})
    static final class Send implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Option(names = {"-d", "--destination"}, required = true, description = "Destination name or ID.")
        String destination;
        @Parameters(arity = "1..*", paramLabel = "FILE", description = "Message files, or '-' for standard input.")
        List<Path> files;
        @Option(names = "--charset", defaultValue = "UTF-8", description = "File character set (default: "
                + "${DEFAULT-VALUE}).")
        Charset charset;
        @Option(names = "--keep-control-id", description = "Queue MSH-10 as written instead of generating one.")
        boolean keepControlId;
        @Option(names = "--keep-timestamp", description = "Queue MSH-7 as written instead of the current time.")
        boolean keepTimestamp;
        @Option(names = "--wait", arity = "0..1", fallbackValue = "60", paramLabel = "SECONDS",
                description = "Wait for delivery (default 60 s when given without a value). The exit code then "
                        + "reflects the ACK: 0 all accepted, 3 AE, 4 AR, 5/6 delivery failed, 8 still pending.")
        Integer waitSeconds;

        @Override
        public Integer call() throws IOException, InterruptedException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            List<BulkItem> items = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (Path f : files) {
                String text;
                try {
                    text = MessageInput.read(f, charset);
                } catch (IOException e) {
                    err.println("Cannot read " + f + ": " + e.getMessage());
                    return ExitCodes.INVALID_INPUT;
                }
                SplitResult split = MessageSplitter.split(text);
                split.warnings().forEach(w -> warnings.add(f + ": " + w));
                for (int i = 0; i < split.messages().size(); i++) {
                    items.add(new BulkItem(split.messages().get(i), split.messages().size() == 1 ? f.toString()
                            : f + "#" + (i + 1)));
                }
            }
            if (items.isEmpty()) {
                err.println("No HL7 messages found in the input");
                return ExitCodes.INVALID_INPUT;
            }
            try (Workspace ws = Workspace.open(Permission.SEND, "queue messages")) {
                DestinationConfig d = ws.destination(destination);
                SendOptions options = new SendOptions(!keepControlId, !keepTimestamp,
                        d.ackMode() == AckMode.NO_ACK ? AckMode.NO_ACK : AckMode.EXPECT_ACK);
                List<Long> ids = new ArrayList<>();
                List<Map<String, Object>> rejected = new ArrayList<>();
                String batchId = null;
                if (items.size() == 1) {
                    EnqueueResult r = ws.engine().enqueue(d.id(), items.get(0).text(), options, "cli:"
                            + items.get(0).source());
                    warnings.addAll(r.warnings());
                    if (r.accepted()) {
                        ids.add(r.message().orElseThrow().id());
                    } else {
                        rejected.add(Map.of("source", items.get(0).source(), "reason",
                                r.validation().errors().get(0).message()));
                    }
                } else {
                    BulkResult r = ws.engine().enqueueAll(d.id(), prefixed(items), options, null, BulkProgress.NONE);
                    batchId = r.batchId();
                    warnings.addAll(r.warnings());
                    for (BulkResult.Rejection rej : r.rejected()) {
                        rejected.add(Map.of("source", rej.source(), "reason", rej.reason()));
                    }
                    // Batch IDs are unique per import, so these are exactly the messages just queued.
                    ws.store().search(MessageQuery.all().withBatch(batchId).withLimit(Integer.MAX_VALUE)).stream()
                            .map(QueuedMessage::id).sorted().forEach(ids::add);
                }
                Optional<String> note = ids.isEmpty() ? Optional.empty() : ws.notifyOwner();
                List<QueuedMessage> result = waitSeconds != null && !ids.isEmpty()
                        ? Delivery.await(ws, ids, waitSeconds) : Delivery.load(ws, ids);
                int code = !rejected.isEmpty() ? ExitCodes.INVALID_INPUT
                        : waitSeconds != null ? ExitCodes.ofQueued(result) : ExitCodes.OK;
                if (output.json) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("destination", d.name());
                    doc.put("batchId", batchId);
                    doc.put("queued", result.stream().map(m -> JsonViews.message(m, false)).toList());
                    doc.put("rejected", rejected);
                    doc.put("warnings", warnings);
                    doc.put("waited", waitSeconds != null);
                    doc.put("exitCode", code);
                    Output.printJson(out, doc);
                } else {
                    for (QueuedMessage m : result) {
                        out.printf("%-8d %-22s %-12s %s%s%n", m.id(), m.controlId(), m.messageType(), m.status(),
                                m.lastError().map(e -> "  " + e).orElse(""));
                    }
                    for (Map<String, Object> r : rejected) {
                        err.println("Not queued: " + r.get("source") + ": " + r.get("reason"));
                    }
                    warnings.forEach(w -> err.println("Warning: " + w));
                    out.println(ids.size() + " message(s) queued for " + d.name()
                            + (batchId == null ? "" : " (batch " + batchId + ")")
                            + (rejected.isEmpty() ? "" : ", " + rejected.size() + " rejected"));
                    if (waitSeconds == null) {
                        note.ifPresent(out::println);
                    } else if (code == ExitCodes.TIMEOUT) {
                        out.println("Still waiting for delivery after " + waitSeconds + " s");
                    }
                }
                out.flush();
                return code;
            }
        }

        private static List<BulkItem> prefixed(List<BulkItem> items) {
            return items.stream().map(i -> new BulkItem(i.text(), "cli:" + i.source())).toList();
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List messages of a destination.")
    static final class ListCmd implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Option(names = {"-d", "--destination"}, description = "Destination name or ID (default: all).")
        String destination;
        @Option(names = {"-s", "--status"}, split = ",", description = "Only these statuses: ${COMPLETION-CANDIDATES}"
                + " (default: pending ones).")
        List<MessageStatus> statuses;
        @Option(names = "--batch", description = "Only messages of this import batch.")
        String batch;
        @Option(names = {"-n", "--limit"}, defaultValue = "100", description = "Maximum rows (default: "
                + "${DEFAULT-VALUE}).")
        int limit;

        @Override
        public Integer call() throws IOException {
            try (Workspace ws = Workspace.open(Permission.VIEW, "list messages")) {
                MessageQuery q = MessageQuery.all().withLimit(limit)
                        .withStatuses(statuses == null ? PENDING : EnumSet.copyOf(statuses)).withBatch(batch);
                if (destination != null) {
                    q = q.withDestination(ws.destination(destination).id());
                }
                print(spec.commandLine().getOut(), output.json, ws.store().search(q));
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true,
            description = "Show one message: status, every attempt and its ACK code.")
    static final class Show implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Parameters(index = "0", paramLabel = "ID", description = "Message ID.")
        long id;
        @Option(names = "--payload", description = "Include the message text (may contain patient data).")
        boolean payload;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.VIEW, "see messages")) {
                QueuedMessage m = ws.store().message(id)
                        .orElseThrow(() -> new Workspace.UsageException("No message " + id));
                Map<String, Object> view = new LinkedHashMap<>(JsonViews.message(m, payload));
                List<Map<String, Object>> history = ws.store().attempts(id).stream().map(JsonViews::attempt).toList();
                view.put("history", history);
                if (output.json) {
                    Output.printJson(out, view);
                } else {
                    view.forEach((k, v) -> {
                        if (v != null && !k.equals("history") && !k.equals("payload")) {
                            out.printf("%-18s %s%n", k, v);
                        }
                    });
                    for (Map<String, Object> a : history) {
                        out.printf("attempt %-10s %s %s%s%s%n", a.get("attempt"), a.get("startedAt"),
                                a.get("outcome"), a.get("ackCode") == null ? "" : " (" + a.get("ackCode") + ")",
                                a.get("detail") == null ? "" : ": " + a.get("detail"));
                    }
                    if (payload) {
                        out.println("--- Message ---");
                        out.println(io.hl7sender.core.hl7.Hl7Text.toDisplay(m.payload()));
                    }
                }
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "retry", mixinStandardHelpOptions = true,
            description = "Retry a message that is waiting for its next attempt now.")
    static final class Retry implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(arity = "1..*", paramLabel = "ID", description = "Message IDs.")
        List<Long> ids;

        @Override
        public Integer call() throws IOException {
            return each(spec, ids, (ws, id) -> ws.engine().retryNow(id), "Retrying",
                    "only messages waiting to retry can be retried now");
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete messages and their history.")
    static final class Delete implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(arity = "1..*", paramLabel = "ID", description = "Message IDs.")
        List<Long> ids;

        @Override
        public Integer call() throws IOException {
            return each(spec, ids, (ws, id) -> ws.engine().delete(id), "Deleted",
                    "a message that is being sent cannot be deleted");
        }
    }

    // ---------------------------------------------------------------------------------------------

    interface MessageAction {
        boolean apply(Workspace ws, long id);
    }

    /** Applies an action to each message; exits 1 if any could not be changed. */
    static int each(CommandSpec spec, List<Long> ids, MessageAction action, String done, String refused)
            throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        int failed = 0;
        try (Workspace ws = Workspace.open(Permission.MANAGE_QUEUE, "delete messages")) {
            for (long id : ids) {
                if (ws.store().message(id).isEmpty()) {
                    err.println("No message " + id);
                    failed++;
                } else if (action.apply(ws, id)) {
                    out.println(done + " " + id);
                } else {
                    err.println("Message " + id + ": " + refused);
                    failed++;
                }
            }
            if (failed < ids.size()) {
                ws.notifyOwner().ifPresent(out::println);
            }
        }
        out.flush();
        return failed == 0 ? ExitCodes.OK : ExitCodes.INVALID_INPUT;
    }

    static void print(PrintWriter out, boolean json, List<QueuedMessage> messages) {
        if (json) {
            Output.printJson(out, Map.of("messages", messages.stream().map(m -> JsonViews.message(m, false)).toList()));
            return;
        }
        if (messages.isEmpty()) {
            out.println("No messages.");
        } else {
            out.printf("%-8s %-22s %-14s %-16s %8s  %s%n", "ID", "CONTROL ID", "TYPE", "STATUS", "ATTEMPTS",
                    "LAST OUTCOME / ERROR");
            for (QueuedMessage m : messages) {
                out.printf("%-8d %-22s %-14s %-16s %8d  %s%n", m.id(), trim(m.controlId(), 22),
                        trim(m.messageType(), 14), m.status(), m.attempts(),
                        m.lastError().or(m::lastOutcome).orElse(""));
            }
        }
        out.flush();
    }

    static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
