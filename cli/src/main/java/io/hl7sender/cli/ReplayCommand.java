package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.QueuedMessage;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "replay", mixinStandardHelpOptions = true,
        description = {"Queue copies of earlier messages again, in any state, to their destination or another one. "
            + "The originals are not changed.",
            "Copies keep their MSH-10 unless --new-control-ids is given; a receiver that de-duplicates on MSH-10 then "
                + "ignores them."})
final class ReplayCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Mixin
    private Output output;

    /** Explicit IDs or a whole batch. */
    static final class Selection {
        @Parameters(arity = "1..*", paramLabel = "ID", description = "Message IDs, replayed in this order.")
        List<Long> ids;
        @Option(names = "--batch", required = true, paramLabel = "BATCH",
                description = "Every message of an import, fan-out, schedule run or earlier replay, in queue order.")
        String batch;
    }

    @ArgGroup(multiplicity = "1")
    private Selection selection;

    @Option(names = {"-d", "--destination"}, description = "Send the copies here instead of each original's "
            + "destination.")
    private String destination;

    @Option(names = "--new-control-ids", description = "Give each copy a new MSH-10 and MSH-7.")
    private boolean newControlIds;

    @Option(names = "--wait", arity = "0..1", fallbackValue = "60", paramLabel = "SECONDS",
            description = "Wait for delivery; the exit code then reflects the ACKs (see 'queue send').")
    private Integer waitSeconds;

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try (Workspace ws = Workspace.open(Permission.SEND, "replay messages")) {
            Long target = destination == null ? null : ws.destination(destination).id();
            List<Long> ids = selection.ids != null ? selection.ids
                    : ws.store().search(MessageQuery.all().withBatch(selection.batch).withLimit(Integer.MAX_VALUE))
                            .stream().sorted(Comparator.comparingLong(QueuedMessage::id)).map(QueuedMessage::id)
                            .toList();
            if (ids.isEmpty()) {
                err.println("No messages in batch " + selection.batch);
                return ExitCodes.INVALID_INPUT;
            }
            List<DeliveryEngine.Replayed> replayed = ws.engine().replay(ids, target, newControlIds);
            List<Long> copies = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (DeliveryEngine.Replayed r : replayed) {
                r.copy().ifPresentOrElse(c -> copies.add(c.id()),
                        () -> problems.add("Message " + r.sourceId() + ": " + r.problem()));
            }
            if (!copies.isEmpty()) {
                ws.notifyOwner().filter(n -> waitSeconds == null && !output.json).ifPresent(out::println);
            }
            List<QueuedMessage> after = waitSeconds != null && !copies.isEmpty()
                    ? Delivery.await(ws, copies, waitSeconds) : Delivery.load(ws, copies);
            int code = !problems.isEmpty() ? ExitCodes.INVALID_INPUT
                    : waitSeconds != null ? ExitCodes.ofQueued(after) : ExitCodes.OK;
            if (output.json) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("replayed", after.stream().map(m -> JsonViews.message(m, false)).toList());
                doc.put("batchId", after.isEmpty() ? null : after.get(0).batchId().orElse(null));
                doc.put("problems", problems);
                doc.put("exitCode", code);
                Output.printJson(out, doc);
            } else {
                problems.forEach(err::println);
                if (waitSeconds != null) {
                    QueueCommand.print(out, false, after);
                }
                out.println(copies.size() + " message(s) queued again"
                        + (after.isEmpty() ? "" : after.get(0).batchId().map(b -> " (batch " + b + ")").orElse("")));
            }
            out.flush();
            return code;
        }
    }
}
