package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.schedule.CronExpression;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.schedule.Scheduler;
import io.hl7sender.core.template.TemplateEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send schedule ...}: messages queued on a cron schedule. */
@Command(name = "schedule", mixinStandardHelpOptions = true,
        description = {"Queue messages to a destination on a schedule, e.g. a test ADT every weekday at 07:00.",
            "Schedules run in the app or 'hl7send serve' (whichever delivers from the queue); runs missed while "
                + "neither is running are not made up. Cron: minute hour day-of-month month day-of-week, e.g. "
                + "'0 7 * * 1-5', '*/15 * * * *', or @hourly / @daily / @weekly."},
        subcommands = {ScheduleCommand.ListCmd.class, ScheduleCommand.Add.class, ScheduleCommand.Remove.class,
            ScheduleCommand.Enable.class, ScheduleCommand.Disable.class, ScheduleCommand.Run.class,
            ScheduleCommand.Preview.class})
final class ScheduleCommand {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private ScheduleCommand() {
    }

    static Schedule find(Workspace ws, String idOrName) {
        Optional<Schedule> s = idOrName.matches("\\d{1,18}") ? ws.store().schedule(Long.parseLong(idOrName))
                : ws.store().schedules().stream().filter(x -> x.name().equalsIgnoreCase(idOrName)).findFirst();
        return s.orElseThrow(() -> new Workspace.UsageException("No schedule named '" + idOrName
                + "'. List them with: hl7send schedule list"));
    }

    private static String destinationName(Workspace ws, long id) {
        return ws.store().destination(id).map(DestinationConfig::name).orElse("#" + id);
    }

    static String when(Instant t, String zone) {
        return t == null ? "-" : WHEN.format(t.atZone(ZoneId.of(zone)));
    }

    /** Tells the delivering process, so its scheduler picks up the change now. */
    private static void changed(Workspace ws, PrintWriter out, boolean json) {
        if (!ws.owner()) {
            ws.notifyOwner().filter(n -> !json).ifPresent(out::println);
        } else if (!json) {
            out.println("Schedules run while the app or 'hl7send serve' is running.");
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List schedules and their next runs.")
    static final class ListCmd implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.VIEW, "list schedules")) {
                List<Schedule> all = ws.store().schedules();
                Instant now = Instant.now();
                if (output.json) {
                    Output.printJson(out, Map.of("schedules", all.stream().map(s -> JsonViews.schedule(s,
                            destinationName(ws, s.destinationId()), s.nextRunAfter(now).orElse(null))).toList()));
                    return ExitCodes.OK;
                }
                if (all.isEmpty()) {
                    out.println("No schedules. Add one with: hl7send schedule add NAME --cron '0 7 * * 1-5' "
                            + "-d DESTINATION FILE");
                }
                for (Schedule s : all) {
                    out.printf("%-4d %-24s %-16s -> %-16s x%-4d %s%n", s.id(), QueueCommand.trim(s.name(), 24),
                            s.cron(), QueueCommand.trim(destinationName(ws, s.destinationId()), 16), s.count(),
                            s.enabled() ? "next " + when(s.nextRunAfter(now).orElse(null), s.zone()) : "disabled");
                    s.lastRunAt().ifPresent(t -> out.printf("     last run %s: %s%n", when(t, s.zone()),
                            s.lastResult()));
                }
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Add a schedule that queues the message(s) in FILE. The file may hold a template with "
                    + "$${...} variables, several messages, or a batch; every run gets new MSH-10/MSH-7 values.")
    static final class Add implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Parameters(index = "0", paramLabel = "NAME", description = "Name of the schedule.")
        String name;
        @Parameters(index = "1", paramLabel = "FILE", description = "Message file, or '-' for standard input.")
        Path file;
        @Option(names = "--cron", required = true, paramLabel = "EXPR",
                description = "When to run, e.g. '0 7 * * 1-5'.")
        String cron;
        @Option(names = {"-d", "--destination"}, required = true, description = "Destination name or ID.")
        String destination;
        @Option(names = "--count", defaultValue = "1", paramLabel = "N",
                description = "Copies to queue per run (default: ${DEFAULT-VALUE}).")
        int count;
        @Option(names = "--zone", paramLabel = "ZONE",
                description = "Time zone, e.g. Europe/London (default: this computer's).")
        String zone;
        @Option(names = "--disabled", description = "Save it without running it yet.")
        boolean disabled;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.CONFIGURE, "add schedules")) {
                DestinationConfig d = ws.destination(destination);
                String message;
                try {
                    message = MessageInput.read(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    spec.commandLine().getErr().println("Cannot read " + file + ": " + e.getMessage());
                    return ExitCodes.INVALID_INPUT;
                }
                Schedule s;
                try {
                    s = new Schedule(0, name, cron, zone, d.id(), message, count, !disabled, Optional.empty(), "");
                    s = ws.store().saveSchedule(s);
                } catch (IllegalArgumentException | QueueException e) {
                    throw new Workspace.UsageException(e.getMessage());
                }
                Instant next = s.nextRunAfter(Instant.now()).orElse(null);
                if (output.json) {
                    Output.printJson(out, JsonViews.schedule(s, d.name(), next));
                } else {
                    out.println("Schedule '" + s.name() + "' added (" + s.id() + ")"
                            + (next == null ? ", disabled" : ", next run " + when(next, s.zone())));
                }
                changed(ws, out, output.json);
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Delete a schedule.")
    static final class Remove implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(paramLabel = "NAME", description = "Schedule name or ID.")
        String name;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.CONFIGURE, "remove schedules")) {
                Schedule s = find(ws, name);
                ws.store().deleteSchedule(s.id());
                out.println("Schedule '" + s.name() + "' deleted");
                changed(ws, out, true);
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "enable", mixinStandardHelpOptions = true, description = "Resume a disabled schedule.")
    static final class Enable implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(paramLabel = "NAME", description = "Schedule name or ID.")
        String name;

        @Override
        public Integer call() throws IOException {
            return setEnabled(spec, name, true);
        }
    }

    @Command(name = "disable", mixinStandardHelpOptions = true, description = "Stop a schedule without deleting it.")
    static final class Disable implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(paramLabel = "NAME", description = "Schedule name or ID.")
        String name;

        @Override
        public Integer call() throws IOException {
            return setEnabled(spec, name, false);
        }
    }

    private static int setEnabled(CommandSpec spec, String name, boolean on) throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        try (Workspace ws = Workspace.open(Permission.CONFIGURE, "enable or disable schedules")) {
            Schedule s = ws.store().saveSchedule(find(ws, name).withEnabled(on));
            out.println("Schedule '" + s.name() + "' " + (on ? "enabled, next run "
                    + when(s.nextRunAfter(Instant.now()).orElse(null), s.zone()) : "disabled"));
            changed(ws, out, true);
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Run a schedule now, whatever its time (also when it is disabled).")
    static final class Run implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Parameters(paramLabel = "NAME", description = "Schedule name or ID.")
        String name;
        @Option(names = "--wait", arity = "0..1", fallbackValue = "60", paramLabel = "SECONDS",
                description = "Wait for delivery; the exit code then reflects the ACKs (see 'queue send').")
        Integer waitSeconds;

        @Override
        public Integer call() throws IOException, InterruptedException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.SEND, "run schedules")) {
                Schedule s = find(ws, name);
                Scheduler.Result r = Scheduler.run(ws.engine(), new TemplateEngine(), s, Instant.now());
                List<Long> ids = r.batchId().map(b -> ws.store().search(MessageQuery.all().withBatch(b)
                        .withLimit(Integer.MAX_VALUE)).stream().sorted(Comparator.comparingLong(QueuedMessage::id))
                        .map(QueuedMessage::id).toList()).orElse(List.of());
                if (!ids.isEmpty() && waitSeconds == null && !output.json) {
                    ws.notifyOwner().ifPresent(out::println);
                } else if (!ids.isEmpty()) {
                    ws.notifyOwner();
                }
                List<QueuedMessage> after = waitSeconds != null && !ids.isEmpty()
                        ? Delivery.await(ws, ids, waitSeconds) : Delivery.load(ws, ids);
                int code = r.queued() == 0 || r.rejected() > 0 ? ExitCodes.INVALID_INPUT
                        : waitSeconds != null ? ExitCodes.ofQueued(after) : ExitCodes.OK;
                if (output.json) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("schedule", s.name());
                    doc.put("result", r.summary());
                    doc.put("batchId", r.batchId().orElse(null));
                    doc.put("messages", after.stream().map(m -> JsonViews.message(m, false)).toList());
                    doc.put("exitCode", code);
                    Output.printJson(out, doc);
                } else {
                    if (waitSeconds != null) {
                        QueueCommand.print(out, false, after);
                    }
                    out.println("Schedule '" + s.name() + "': " + r.summary());
                }
                out.flush();
                return code;
            }
        }
    }

    @Command(name = "preview", mixinStandardHelpOptions = true,
            description = "Show when a cron expression fires, to check it before adding a schedule.")
    static final class Preview implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(paramLabel = "EXPR", description = "Cron expression, e.g. '0 7 * * 1-5'.")
        String cron;
        @Option(names = "--zone", paramLabel = "ZONE", description = "Time zone (default: this computer's).")
        String zone;
        @Option(names = {"-n", "--count"}, defaultValue = "5", description = "How many times to show.")
        int n;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            CronExpression c;
            ZoneId z;
            try {
                c = CronExpression.parse(cron);
                z = zone == null ? ZoneId.systemDefault() : ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw new Workspace.UsageException(e.getMessage());
            }
            for (ZonedDateTime t : c.nextTimes(ZonedDateTime.now(z), Math.max(1, Math.min(n, 100)))) {
                out.println(WHEN.format(t) + "  (" + t.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,
                        java.util.Locale.ENGLISH) + ")");
            }
            out.flush();
            return ExitCodes.OK;
        }
    }
}
