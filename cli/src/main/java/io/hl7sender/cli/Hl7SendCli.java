package io.hl7sender.cli;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.secrets.SecretStoreException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/** Entry point for the {@code hl7send} command-line tool. */
@Command(
        name = "hl7send",
        mixinStandardHelpOptions = true,
        versionProvider = Hl7SendCli.Version.class,
        description = {"Send HL7 v2 messages over MLLP/TCP, validate them, run a test listener, and work with the "
            + "delivery queue shared with the HL7 Sender desktop app.",
            "Queue commands use the app's settings, destinations and queue. Add --json for machine-readable output."},
        subcommands = {SendCommand.class, ValidateCommand.class, ListenCommand.class, QueueCommand.class,
            DlqCommand.class, DestinationCommand.class, ServeCommand.class, ServiceCommand.class, ApiCommand.class,
            UpdateCommand.class, LoadCommand.class, ScheduleCommand.class, ReplayCommand.class,
            ScriptCommand.class, FhirCommand.class, DatabaseCommand.class, UserCommand.class,
            CommandLine.HelpCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:Success (AA/CA, or sent with --no-ack)",
            "1:Invalid message, unreadable file, or some items could not be processed",
            "2:Invalid command-line usage (including an unknown destination)",
            "3:Application error (AE) or commit error (CE)",
            "4:Application reject (AR) or commit reject (CR)",
            "5:Delivery unknown: ACK timeout, connection closed, invalid or mismatched ACK",
            "6:Connection or protocol failure",
            "7:Queue unavailable (encrypted without its key, or already delivered by another process for 'serve')",
            "8:--wait timed out with messages still pending",
            "9:'queue status --fail-if-dead' found dead-lettered messages",
            "10:'load' missed a threshold (error rate, latency or throughput)",
            "11:Sign-in failed, or the user may not do this"})
public final class Hl7SendCli {

    static {
        // The installers put the app and the CLI on one classpath, so each uses its own Logback file name: the
        // CLI logs only errors to the console, keeping standard output clean for --json.
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
    }

    @Option(names = "--home", scope = ScopeType.INHERIT, paramLabel = "DIR",
            description = "Keep settings, queue and logs in DIR instead of the per-user folders "
                    + "(same as HL7SENDER_HOME).")
    void setHome(Path dir) {
        System.setProperty(AppPaths.HOME_PROPERTY, dir.toAbsolutePath().toString());
    }

    @Option(names = "--user", scope = ScopeType.INHERIT, paramLabel = "NAME",
            description = "Sign in as NAME when the queue has users (same as HL7SENDER_USER). The password is read "
                    + "from HL7SENDER_PASSWORD, or typed at the console.")
    void setUser(String name) {
        System.setProperty(Workspace.USER_PROPERTY, name);
    }

    private Hl7SendCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the CLI and returns the exit code, without calling {@link System#exit}. */
    public static int run(String... args) {
        // --home must apply before the log folder is chosen, which happens before picocli parses the arguments.
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--home") && i + 1 < args.length) {
                System.setProperty(AppPaths.HOME_PROPERTY, Path.of(args[i + 1]).toAbsolutePath().toString());
            } else if (args[i].startsWith("--home=")) {
                System.setProperty(AppPaths.HOME_PROPERTY, Path.of(args[i].substring(7)).toAbsolutePath().toString());
            }
        }
        AppPaths paths = AppPaths.detect();
        try {
            paths.ensureExist();
        } catch (IOException e) {
            System.err.println("Warning: cannot create " + paths.logDir() + ": " + e.getMessage());
        }
        paths.publishLogDir();
        return commandLine().execute(args);
    }

    /** Builds the configured command tree. */
    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new Hl7SendCli()).setCaseInsensitiveEnumValuesAllowed(true);
        cli.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            Throwable e = ex instanceof UncheckedIOException u ? u.getCause() : ex;
            cmd.getErr().println(e.getMessage() == null ? e.toString() : e.getMessage());
            cmd.getErr().flush();
            if (e instanceof io.hl7sender.core.auth.AccessDeniedException) {
                return ExitCodes.ACCESS_DENIED;
            }
            if (e instanceof Workspace.UsageException) {
                return ExitCodes.USAGE;
            }
            if (e instanceof QueueException || e instanceof SecretStoreException) {
                return ExitCodes.QUEUE_UNAVAILABLE;
            }
            if (e instanceof IOException || e instanceof IllegalArgumentException) {
                return ExitCodes.INVALID_INPUT;
            }
            throw ex;
        });
        return cli;
    }

    /** Supplies {@code --version} output. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {
                "hl7send " + AppInfo.version(),
                "Java " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + ")"};
        }
    }
}
