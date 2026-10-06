package io.hl7sender.cli;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.secrets.SecretStores;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send database ...}: where the queue database is (a local SQLite file, or a shared PostgreSQL server). */
@Command(name = "database", mixinStandardHelpOptions = true,
        description = {"Choose where the queue database is: a SQLite file on this computer (the default), or a "
            + "PostgreSQL server shared by several computers.",
            "With PostgreSQL, every app window, service and CLI that uses the same server sees the same destinations, "
                + "queue and history; one of them delivers at a time. The password is kept in the OS keychain.",
            "Restart the app and services after changing the database."},
        subcommands = {DatabaseCommand.Show.class, DatabaseCommand.UsePostgres.class, DatabaseCommand.UseSqlite.class,
            DatabaseCommand.Test.class})
final class DatabaseCommand {

    private DatabaseCommand() {
    }

    private static SettingsStore settingsStore(AppPaths paths) {
        return new SettingsStore(paths.settingsFile());
    }

    private static Map<String, Object> describe(AppPaths paths, AppSettings.Database db, SecretStore secrets) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", db.postgres() ? "postgres" : "sqlite");
        if (db.postgres()) {
            doc.put("url", db.url());
            doc.put("username", db.username());
            doc.put("passwordStored", secrets.get(QueueOpener.POSTGRES_PASSWORD).isPresent());
        } else {
            doc.put("file", QueueOpener.database(paths).toString());
        }
        return doc;
    }

    @Command(name = "show", mixinStandardHelpOptions = true, description = "Show which queue database is used.")
    static final class Show implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            AppPaths paths = AppPaths.detect();
            AppSettings.Database db = settingsStore(paths).load().database();
            Map<String, Object> doc = describe(paths, db, SecretStores.detect(paths.configDir()));
            if (output.json) {
                Output.printJson(out, doc);
            } else if (db.postgres()) {
                out.println("Shared PostgreSQL database: " + db.url() + (db.username().isEmpty() ? ""
                        : " (user " + db.username() + ")"));
            } else {
                out.println("Local SQLite database: " + doc.get("file"));
            }
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "use-postgres", mixinStandardHelpOptions = true,
            description = {"Use a shared PostgreSQL database. The connection is tested first; the tables are created "
                + "if the database is empty.",
                "The existing local queue is not copied: export destinations first ('hl7send destination export') and "
                    + "import them afterwards if you need them."})
    static final class UsePostgres implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "URL",
                description = "e.g. jdbc:postgresql://db.example.org:5432/hl7sender?sslmode=verify-full")
        String url;
        @Option(names = "--db-user", paramLabel = "NAME", description = "PostgreSQL user name.")
        String user = "";
        @Option(names = "--password-stdin", description = "Read the password from the first line of standard input.")
        boolean passwordStdin;
        @Option(names = "--password-env", paramLabel = "VAR",
                description = "Read the password from this environment variable.")
        String passwordEnv;
        @Option(names = "--no-test", description = "Save without connecting first.")
        boolean noTest;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            if (url == null || !url.startsWith("jdbc:postgresql://")) {
                err.println("A PostgreSQL URL starts with jdbc:postgresql://, e.g. "
                        + "jdbc:postgresql://db.example.org:5432/hl7sender");
                return ExitCodes.USAGE;
            }
            String password;
            if (passwordStdin) {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                password = in.readLine();
            } else if (passwordEnv != null) {
                password = System.getenv(passwordEnv);
                if (password == null) {
                    err.println("Environment variable " + passwordEnv + " is not set");
                    return ExitCodes.USAGE;
                }
            } else if (System.console() != null) {
                char[] typed = System.console().readPassword("Password for %s: ", user.isEmpty() ? url : user);
                password = typed == null ? null : new String(typed);
            } else {
                password = null;
            }
            if (!noTest) {
                try (QueueStore store = QueueStore.openPostgres(url, user, password, Clock.systemUTC())) {
                    out.println("Connected to " + store.location() + ": " + store.destinations().size()
                            + " destination(s).");
                } catch (QueueException e) {
                    err.println(e.getMessage());
                    return ExitCodes.QUEUE_UNAVAILABLE;
                }
            }
            AppPaths paths = AppPaths.detect();
            SecretStore secrets = SecretStores.detect(paths.configDir());
            if (password == null || password.isEmpty()) {
                secrets.delete(QueueOpener.POSTGRES_PASSWORD);
            } else {
                secrets.put(QueueOpener.POSTGRES_PASSWORD, password);
            }
            SettingsStore store = settingsStore(paths);
            store.save(store.load().withDatabase(AppSettings.Database.postgres(url, user)));
            out.println("The queue database is now " + url.replaceAll("[?].*$", "")
                    + ". Restart the app and any running service to use it.");
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "use-sqlite", mixinStandardHelpOptions = true,
            description = "Go back to the local SQLite database on this computer. The stored PostgreSQL password is "
                + "deleted.")
    static final class UseSqlite implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            AppPaths paths = AppPaths.detect();
            SecretStores.detect(paths.configDir()).delete(QueueOpener.POSTGRES_PASSWORD);
            SettingsStore store = settingsStore(paths);
            store.save(store.load().withDatabase(AppSettings.Database.defaults()));
            PrintWriter out = spec.commandLine().getOut();
            out.println("The queue database is now the local file " + QueueOpener.database(paths)
                    + ". Restart the app and any running service to use it.");
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "test", mixinStandardHelpOptions = true,
            description = "Open the configured queue database and report what is in it (without delivering).")
    static final class Test implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            AppPaths paths = AppPaths.detect();
            AppSettings settings = settingsStore(paths).load();
            SecretStore secrets = SecretStores.detect(paths.configDir());
            Map<String, Object> doc = describe(paths, settings.database(), secrets);
            try (QueueOpener.Opened opened = QueueOpener.share(paths, settings.database(), secrets,
                    Clock.systemUTC())) {
                doc.put("ok", true);
                doc.put("destinations", opened.store().destinations().size());
            } catch (QueueException e) {
                doc.put("ok", false);
                doc.put("error", e.getMessage());
            }
            boolean ok = (Boolean) doc.get("ok");
            if (output.json) {
                Output.printJson(out, doc);
            } else if (ok) {
                out.println("OK: " + (settings.database().postgres() ? settings.database().url() : doc.get("file"))
                        + ", " + doc.get("destinations") + " destination(s)");
            } else {
                spec.commandLine().getErr().println("Cannot open the queue database: " + doc.get("error"));
            }
            out.flush();
            return ok ? ExitCodes.OK : ExitCodes.QUEUE_UNAVAILABLE;
        }
    }
}
