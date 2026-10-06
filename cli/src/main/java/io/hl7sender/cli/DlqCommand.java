package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueuedMessage;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumSet;
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

/** {@code hl7send dlq ...}: the dead-letter queue. */
@Command(name = "dlq", mixinStandardHelpOptions = true,
        description = "List, requeue or delete dead-lettered messages (rejected or undeliverable).",
        subcommands = {DlqCommand.ListCmd.class, DlqCommand.Requeue.class, DlqCommand.Delete.class})
final class DlqCommand {

    private DlqCommand() {
    }

    static MessageQuery dead(Workspace ws, String destination) {
        MessageQuery q = MessageQuery.all().withStatuses(EnumSet.of(MessageStatus.DEAD_LETTER))
                .withLimit(Integer.MAX_VALUE);
        return destination == null ? q : q.withDestination(ws.destination(destination).id());
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List dead-lettered messages.")
    static final class ListCmd implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Option(names = {"-d", "--destination"}, description = "Destination name or ID (default: all).")
        String destination;

        @Override
        public Integer call() throws IOException {
            try (Workspace ws = Workspace.open(Permission.VIEW, "list dead letters")) {
                QueueCommand.print(spec.commandLine().getOut(), output.json, ws.store().search(dead(ws, destination)));
                return ExitCodes.OK;
            }
        }
    }

    /** Either explicit IDs or all dead letters. */
    static final class Selection {
        @Parameters(arity = "1..*", paramLabel = "ID", description = "Message IDs.")
        List<Long> ids;
        @Option(names = "--all", required = true, description = "Every dead-lettered message (of --destination).")
        boolean all;
    }

    @Command(name = "requeue", mixinStandardHelpOptions = true,
            description = {"Put dead-lettered messages back at the end of their queue with a fresh attempt budget.",
                "Fix the cause first (receiver configuration, or the message in the app), or they fail again."})
    static final class Requeue implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @ArgGroup(multiplicity = "1")
        Selection selection;
        @Option(names = {"-d", "--destination"}, description = "With --all: only this destination.")
        String destination;
        @Option(names = "--wait", arity = "0..1", fallbackValue = "60", paramLabel = "SECONDS",
                description = "Wait for delivery; the exit code then reflects the ACK (see 'queue send').")
        Integer waitSeconds;

        @Override
        public Integer call() throws IOException, InterruptedException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            try (Workspace ws = Workspace.open(Permission.MANAGE_QUEUE, "requeue dead letters")) {
                List<Long> candidates = selection.all
                        ? ws.store().search(dead(ws, destination)).stream().map(QueuedMessage::id).toList()
                        : selection.ids;
                List<Long> requeued = new ArrayList<>();
                List<Long> refused = new ArrayList<>();
                for (long id : candidates) {
                    boolean isDead = ws.store().message(id).map(m -> m.status() == MessageStatus.DEAD_LETTER)
                            .orElse(false);
                    if (isDead && ws.engine().requeue(id)) {
                        requeued.add(id);
                    } else {
                        refused.add(id);
                    }
                }
                if (!requeued.isEmpty()) {
                    ws.notifyOwner().filter(n -> waitSeconds == null && !output.json).ifPresent(out::println);
                }
                List<QueuedMessage> after = waitSeconds != null && !requeued.isEmpty()
                        ? Delivery.await(ws, requeued, waitSeconds) : Delivery.load(ws, requeued);
                int code = !refused.isEmpty() ? ExitCodes.INVALID_INPUT
                        : waitSeconds != null ? ExitCodes.ofQueued(after) : ExitCodes.OK;
                if (output.json) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("requeued", after.stream().map(m -> JsonViews.message(m, false)).toList());
                    doc.put("notDeadLettered", refused);
                    doc.put("exitCode", code);
                    Output.printJson(out, doc);
                } else {
                    refused.forEach(id -> err.println("Message " + id + " is not in the dead-letter queue"));
                    if (waitSeconds != null) {
                        QueueCommand.print(out, false, after);
                    }
                    out.println(requeued.size() + " message(s) requeued");
                }
                out.flush();
                return code;
            }
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Permanently delete dead-lettered messages and their history.")
    static final class Delete implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @ArgGroup(multiplicity = "1")
        Selection selection;
        @Option(names = {"-d", "--destination"}, description = "With --all: only this destination.")
        String destination;

        @Override
        public Integer call() throws IOException {
            List<Long> ids;
            try (Workspace ws = Workspace.open(Permission.MANAGE_QUEUE, "delete dead letters")) {
                ids = selection.all ? ws.store().search(dead(ws, destination)).stream().map(QueuedMessage::id).toList()
                        : selection.ids;
                for (long id : ids) {
                    if (ws.store().message(id).filter(m -> m.status() != MessageStatus.DEAD_LETTER).isPresent()) {
                        spec.commandLine().getErr().println("Message " + id + " is not dead-lettered; use "
                                + "'queue delete' to delete other messages");
                        return ExitCodes.INVALID_INPUT;
                    }
                }
            }
            if (ids.isEmpty()) {
                spec.commandLine().getOut().println("The dead-letter queue is empty");
                return ExitCodes.OK;
            }
            return QueueCommand.each(spec, ids, (ws, id) -> ws.engine().delete(id), "Deleted", "could not be deleted");
        }
    }
}
