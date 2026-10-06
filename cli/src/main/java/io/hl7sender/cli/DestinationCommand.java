package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send destination ...}: the receivers messages are queued for. */
@Command(name = "destination", aliases = "dest", mixinStandardHelpOptions = true,
        description = "List, pause, resume, export and import destinations.",
        subcommands = {DestinationCommand.ListCmd.class, DestinationCommand.TransportsCmd.class,
            DestinationCommand.Pause.class,
            DestinationCommand.Resume.class, DestinationCommand.Export.class, DestinationCommand.Import.class})
final class DestinationCommand {

    private DestinationCommand() {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List destinations and their settings.")
    static final class ListCmd implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.VIEW, "list destinations")) {
                List<DestinationConfig> all = ws.store().destinations();
                if (output.json) {
                    Output.printJson(out, Map.of("destinations",
                            all.stream().map(d -> JsonViews.destination(d, null, ws.store().counts(d.id()))).toList()));
                } else if (all.isEmpty()) {
                    out.println("No destinations.");
                } else {
                    out.printf("%-5s %-24s %-30s %-10s %-11s %s%n", "ID", "NAME", "ADDRESS", "ACK", "VALIDATION",
                            "PAUSED");
                    for (DestinationConfig d : all) {
                        out.printf("%-5d %-24s %-30s %-10s %-11s %s%n", d.id(), QueueCommand.trim(d.name(), 24),
                                QueueCommand.trim(d.displayAddress(), 30), d.ackMode(), d.validationLevel(),
                                d.paused() ? "yes" : "no");
                    }
                }
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "pause", mixinStandardHelpOptions = true,
            description = "Stop sending to a destination; messages keep queuing.")
    static final class Pause implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "NAME", description = "Destination name or ID.")
        String name;

        @Override
        public Integer call() throws IOException {
            return setPaused(spec, name, true);
        }
    }

    @Command(name = "resume", mixinStandardHelpOptions = true, description = "Resume sending to a destination.")
    static final class Resume implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "NAME", description = "Destination name or ID.")
        String name;

        @Override
        public Integer call() throws IOException {
            return setPaused(spec, name, false);
        }
    }

    private static int setPaused(CommandSpec spec, String name, boolean paused) throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        try (Workspace ws = Workspace.open(Permission.MANAGE_QUEUE, "pause or resume destinations")) {
            DestinationConfig d = ws.destination(name);
            if (paused) {
                ws.engine().pause(d.id());
            } else {
                ws.engine().resume(d.id());
            }
            out.println((paused ? "Paused " : "Resumed ") + d.name());
            ws.notifyOwner().filter(n -> !paused).ifPresent(out::println);
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "Write destination settings and notes to a JSON profile file (no passwords or messages).")
    static final class Export implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "FILE", description = "Profile file to write.")
        Path file;
        @Option(names = {"-d", "--destination"}, description = "Only these destinations (default: all).")
        List<String> names;

        @Override
        public Integer call() throws IOException {
            try (Workspace ws = Workspace.open(Permission.VIEW, "export destinations")) {
                List<DestinationConfig> selected = new ArrayList<>();
                if (names == null) {
                    selected.addAll(ws.store().destinations());
                } else {
                    for (String n : names) {
                        selected.add(ws.destination(n));
                    }
                }
                DestinationProfiles.write(selected, file);
                spec.commandLine().getOut().println("Exported " + selected.size() + " destination(s) to " + file);
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "import", mixinStandardHelpOptions = true,
            description = {"Add the destinations in a JSON profile file. Names that are taken get a suffix, e.g. "
                + "'Mirth (2)'.", "TLS passwords are not in profile files: set them in the app afterwards."})
    static final class Import implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "FILE", description = "Profile file to read.")
        Path file;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            List<DestinationConfig> imported;
            try {
                imported = DestinationProfiles.read(file);
            } catch (IOException | IllegalArgumentException e) {
                spec.commandLine().getErr().println("Cannot import " + file + ": " + e.getMessage());
                return ExitCodes.INVALID_INPUT;
            }
            try (Workspace ws = Workspace.open(Permission.CONFIGURE, "import destinations")) {
                Set<String> taken = new HashSet<>();
                ws.store().destinations().forEach(d -> taken.add(d.name()));
                for (DestinationConfig d : imported) {
                    String name = DestinationProfiles.uniqueName(d.name(), taken);
                    taken.add(name);
                    DestinationConfig saved = ws.engine().saveDestination(d.withName(name));
                    out.println("Imported " + saved.name() + " (" + saved.displayAddress() + ")");
                }
                ws.notifyOwner().filter(n -> !ws.owner()).ifPresent(out::println);
                out.flush();
                return ExitCodes.OK;
            }
        }
    }

    @Command(name = "transports", mixinStandardHelpOptions = true,
            description = "List the transports destinations can use, including plugins from the plugins folder.")
    static final class TransportsCmd implements java.util.concurrent.Callable<Integer> {
        @picocli.CommandLine.Spec
        picocli.CommandLine.Model.CommandSpec spec;
        @picocli.CommandLine.Mixin
        Output output;

        @Override
        public Integer call() {
            java.io.PrintWriter out = spec.commandLine().getOut();
            io.hl7sender.core.config.AppPaths paths = io.hl7sender.core.config.AppPaths.detect();
            io.hl7sender.core.transport.Transports t = io.hl7sender.core.transport.Transports.load(paths.pluginsDir());
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            list.add(java.util.Map.of("id", "mllp", "name", "MLLP over TCP", "options", java.util.List.of()));
            for (io.hl7sender.core.transport.TransportFactory f : t.all()) {
                list.add(java.util.Map.of("id", f.id(), "name", f.displayName(), "options",
                        f.options().stream().map(o -> o.key() + (o.required() ? " (required)" : "")).toList()));
            }
            if (output.json) {
                Output.printJson(out, java.util.Map.of("transports", list, "pluginsFolder",
                        paths.pluginsDir().toString(), "problems", t.problems()));
            } else {
                for (java.util.Map<String, Object> m : list) {
                    out.printf("%-8s %-28s %s%n", m.get("id"), m.get("name"),
                            m.get("options").toString().replaceAll("^\\[|]$", ""));
                }
                out.println("Plugins folder: " + paths.pluginsDir());
                t.problems().forEach(p -> out.println("Problem: " + p));
            }
            out.flush();
            return t.problems().isEmpty() ? ExitCodes.OK : ExitCodes.ATTENTION;
        }
    }
}
