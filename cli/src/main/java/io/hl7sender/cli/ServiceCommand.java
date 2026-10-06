package io.hl7sender.cli;

import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code hl7send service ...}: run {@code serve} automatically with the computer or at login. */
@Command(name = "service", mixinStandardHelpOptions = true,
        description = {"Run 'hl7send serve' automatically: a systemd user service on Linux, a launchd agent on "
            + "macOS, or a Windows service through WinSW.",
            "The service uses the same settings, destinations, queue and saved passwords as the desktop app. Only "
                + "one of them delivers at a time, so close the app's queue while the service runs."},
        subcommands = {ServiceCommand.Print.class, ServiceCommand.Install.class, ServiceCommand.Uninstall.class})
final class ServiceCommand {

    private ServiceCommand() {
    }

    @Command(name = "print", mixinStandardHelpOptions = true, description = "Print the service definition.")
    static final class Print implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Option(names = "--platform", description = "${COMPLETION-CANDIDATES} (default: this computer).")
        ServiceFiles.Platform platform;

        @Override
        public Integer call() {
            ServiceFiles.Platform p = platform == null ? ServiceFiles.Platform.current() : platform;
            spec.commandLine().getOut().print(ServiceFiles.render(p, ServiceFiles.current(), AppPaths.detect()));
            spec.commandLine().getOut().flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "install", mixinStandardHelpOptions = true,
            description = {"Write the service definition and start the service.",
                "Linux: ~/.config/systemd/user/hl7send.service, then systemctl --user enable --now. Run "
                    + "'loginctl enable-linger' once to keep it running while you are logged out.",
                "macOS: ~/Library/LaunchAgents/io.hl7sender.hl7send.plist, then launchctl bootstrap.",
                "Windows: writes hl7send.xml for WinSW. Download WinSW-x64.exe, save it next to the XML as "
                    + "hl7send.exe, set <serviceaccount> to your account, then run 'hl7send.exe install' and "
                    + "'hl7send.exe start' as administrator."})
    static final class Install implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Option(names = "--file", paramLabel = "PATH", description = "Write the definition here instead.")
        Path file;
        @Option(names = "--no-start", description = "Only write the file; do not register or start the service.")
        boolean noStart;
        @Option(names = "--dry-run", description = "Show what would be written and run, and change nothing.")
        boolean dryRun;

        @Override
        public Integer call() throws IOException, InterruptedException {
            PrintWriter out = spec.commandLine().getOut();
            AppPaths paths = AppPaths.detect();
            ServiceFiles.Platform p = ServiceFiles.Platform.current();
            Path target = file != null ? file : ServiceFiles.defaultLocation(p, paths);
            String content = ServiceFiles.render(p, ServiceFiles.current(), paths);
            List<List<String>> commands = noStart ? List.of() : startCommands(p, target);
            if (dryRun) {
                out.println("Would write " + target + ":");
                out.println(content);
                commands.forEach(c -> out.println("Would run: " + String.join(" ", c)));
                out.flush();
                return ExitCodes.OK;
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createDirectories(paths.logDir());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            out.println("Wrote " + target);
            int code = run(out, commands);
            switch (p) {
                case LINUX -> out.println("Status: systemctl --user status " + ServiceFiles.NAME
                        + "   Logs: journalctl --user -u " + ServiceFiles.NAME + " -f"
                        + "\nTo keep it running while you are logged out: loginctl enable-linger");
                case MACOS -> out.println("Status: launchctl print gui/$(id -u)/" + ServiceFiles.LAUNCHD_LABEL
                        + "   Logs: " + paths.logDir());
                case WINDOWS -> out.println(String.join("\n",
                        "Next steps (as administrator):",
                        "  1. Download WinSW-x64.exe from https://github.com/winsw/winsw/releases and save it as",
                        "     " + target.resolveSibling(ServiceFiles.NAME + ".exe"),
                        "  2. Edit " + target.getFileName() + ": set <serviceaccount> to your Windows account, so",
                        "     the service uses your settings, queue and saved passwords.",
                        "  3. Run: " + ServiceFiles.NAME + ".exe install   then   " + ServiceFiles.NAME
                                + ".exe start"));
                default -> throw new IllegalStateException("Unknown platform " + p);
            }
            out.flush();
            return code;
        }
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true,
            description = "Stop the service and remove its definition (queued messages are kept).")
    static final class Uninstall implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Option(names = "--file", paramLabel = "PATH", description = "The definition to remove.")
        Path file;
        @Option(names = "--dry-run", description = "Show what would be run and removed, and change nothing.")
        boolean dryRun;

        @Override
        public Integer call() throws IOException, InterruptedException {
            PrintWriter out = spec.commandLine().getOut();
            ServiceFiles.Platform p = ServiceFiles.Platform.current();
            Path target = file != null ? file : ServiceFiles.defaultLocation(p, AppPaths.detect());
            List<List<String>> commands = new ArrayList<>(stopCommands(p, target));
            if (dryRun) {
                commands.forEach(c -> out.println("Would run: " + String.join(" ", c)));
                out.println("Would remove " + target);
                out.flush();
                return ExitCodes.OK;
            }
            if (p == ServiceFiles.Platform.WINDOWS) {
                out.println("As administrator, run: " + target.resolveSibling(ServiceFiles.NAME + ".exe")
                        + " stop   then   " + ServiceFiles.NAME + ".exe uninstall");
            }
            int code = run(out, commands);
            if (Files.deleteIfExists(target)) {
                out.println("Removed " + target);
            }
            if (p == ServiceFiles.Platform.LINUX && !commands.isEmpty()) {
                code = Math.max(code, run(out, List.of(List.of("systemctl", "--user", "daemon-reload"))));
            }
            out.flush();
            return code;
        }
    }

    static List<List<String>> startCommands(ServiceFiles.Platform p, Path file) throws IOException,
            InterruptedException {
        return switch (p) {
            case LINUX -> List.of(List.of("systemctl", "--user", "daemon-reload"),
                    List.of("systemctl", "--user", "enable", "--now", ServiceFiles.NAME + ".service"));
            case MACOS -> List.of(List.of("launchctl", "bootstrap", "gui/" + uid(), file.toString()));
            case WINDOWS -> List.of();
        };
    }

    static List<List<String>> stopCommands(ServiceFiles.Platform p, Path file) throws IOException,
            InterruptedException {
        return switch (p) {
            case LINUX -> List.of(List.of("systemctl", "--user", "disable", "--now", ServiceFiles.NAME + ".service"));
            case MACOS -> List.of(List.of("launchctl", "bootout", "gui/" + uid(), file.toString()));
            case WINDOWS -> List.of();
        };
    }

    private static String uid() throws IOException, InterruptedException {
        Process proc = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        proc.waitFor(10, TimeUnit.SECONDS);
        return out;
    }

    /** Runs each command, echoing it and its output; returns 0, or 1 if any failed. */
    private static int run(PrintWriter out, List<List<String>> commands) throws IOException, InterruptedException {
        int code = ExitCodes.OK;
        for (List<String> c : commands) {
            out.println("$ " + String.join(" ", c));
            out.flush();
            Process proc;
            try {
                proc = new ProcessBuilder(c).redirectErrorStream(true).start();
            } catch (IOException e) {
                out.println("  could not run " + c.get(0) + ": " + e.getMessage());
                code = ExitCodes.INVALID_INPUT;
                continue;
            }
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!output.isEmpty()) {
                out.println("  " + output.replace("\n", "\n  "));
            }
            if (!finished || proc.exitValue() != 0) {
                out.println("  failed" + (finished ? " (exit " + proc.exitValue() + ")" : " (timed out)"));
                code = ExitCodes.INVALID_INPUT;
            }
        }
        return code;
    }
}
