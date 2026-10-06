package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.auth.Role;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.queue.QueueException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send user ...}: who may sign in, and what they may do. */
@Command(name = "user", mixinStandardHelpOptions = true,
        description = {"Manage users and roles. Until the first user is added, anyone can use the app and the queue "
            + "commands; once there is a user, everyone signs in (the first user must be an admin).",
            "Roles: viewer (look only), operator (send, replay, work the queue), admin (everything, including "
                + "destinations, schedules and users).",
            "New passwords are read from standard input (--password-stdin), from HL7SENDER_NEW_PASSWORD, or typed at "
                + "the console."},
        subcommands = {UserCommand.ListUsers.class, UserCommand.Add.class, UserCommand.SetRole.class,
            UserCommand.Password.class, UserCommand.Enable.class, UserCommand.Disable.class, UserCommand.Remove.class})
final class UserCommand {

    /** Environment variable holding a new password (for {@code add} and {@code password}). */
    static final String NEW_PASSWORD_ENV = "HL7SENDER_NEW_PASSWORD";

    private UserCommand() {
    }

    /** Where a new password comes from. */
    static final class PasswordSource {
        @Option(names = "--password-stdin", description = "Read the new password from the first line of standard "
                + "input.")
        boolean stdin;

        String read(String user) throws IOException {
            if (stdin) {
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (line == null) {
                    throw new Workspace.UsageException("No password on standard input");
                }
                return line;
            }
            String env = Workspace.env(NEW_PASSWORD_ENV);
            if (env != null) {
                return env;
            }
            if (System.console() != null) {
                char[] a = System.console().readPassword("New password for %s: ", user);
                char[] b = System.console().readPassword("Repeat the password: ");
                if (a == null || b == null || !new String(a).equals(new String(b))) {
                    throw new Workspace.UsageException("The passwords do not match");
                }
                return new String(a);
            }
            throw new Workspace.UsageException("Give the new password with --password-stdin or " + NEW_PASSWORD_ENV);
        }
    }

    private static Map<String, Object> json(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username());
        m.put("displayName", u.displayName());
        m.put("role", u.role().name().toLowerCase(Locale.ROOT));
        m.put("enabled", u.enabled());
        m.put("createdAt", u.createdAt().toString());
        m.put("lastLoginAt", u.lastLoginAt().map(Object::toString).orElse(null));
        return m;
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List users and their roles.")
    static final class ListUsers implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            try (Workspace ws = Workspace.open(Permission.ADMIN_USERS, "manage users")) {
                List<User> users = ws.store().users();
                if (output.json) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    users.forEach(u -> list.add(json(u)));
                    Output.printJson(out, Map.of("accessControl", !users.isEmpty(), "users", list));
                } else if (users.isEmpty()) {
                    out.println("No users: anyone can use the app and the queue. Add an admin with: hl7send user add "
                            + "NAME --role admin");
                } else {
                    for (User u : users) {
                        out.printf("%-24s %-9s %-9s %s%n", u.username(), u.role().name().toLowerCase(Locale.ROOT),
                                u.enabled() ? "enabled" : "disabled", u.displayName());
                    }
                }
            }
            out.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true, description = "Add a user.")
    static final class Add implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "NAME", description = "User name (letters, digits, . _ @ -).")
        String name;
        @Option(names = {"-r", "--role"}, required = true, paramLabel = "ROLE",
                description = "admin, operator or viewer.")
        String role;
        @Option(names = "--display-name", paramLabel = "TEXT", description = "Name shown in the app.")
        String displayName = "";
        @Mixin
        PasswordSource password;

        @Override
        public Integer call() throws IOException {
            User u;
            try {
                u = User.of(name, displayName, Role.parse(role));
            } catch (IllegalArgumentException e) {
                throw new Workspace.UsageException(e.getMessage());
            }
            try (Workspace ws = Workspace.open(Permission.ADMIN_USERS, "manage users")) {
                boolean first = !ws.store().accessControlEnabled();
                User saved;
                try {
                    saved = ws.store().createUser(u, password.read(u.username()));
                } catch (QueueException | IllegalArgumentException e) {
                    throw new Workspace.UsageException(e.getMessage());
                }
                PrintWriter out = spec.commandLine().getOut();
                out.println("Added " + saved.username() + " (" + saved.role().name().toLowerCase(Locale.ROOT) + ").");
                if (first) {
                    out.println("Access control is now on: the app and the queue commands ask everyone to sign in "
                            + "(hl7send --user NAME, with the password in HL7SENDER_PASSWORD).");
                }
                out.flush();
            }
            return ExitCodes.OK;
        }
    }

    /** Base for commands that change one user. */
    abstract static class Change implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", paramLabel = "NAME", description = "User name.")
        String name;

        abstract String apply(Workspace ws, User user) throws IOException;

        @Override
        public Integer call() throws IOException {
            try (Workspace ws = Workspace.open(Permission.ADMIN_USERS, "manage users")) {
                User u = ws.store().user(name).orElseThrow(() -> new Workspace.UsageException("No user '" + name
                        + "'. List them with: hl7send user list"));
                String result;
                try {
                    result = apply(ws, u);
                } catch (QueueException | IllegalArgumentException e) {
                    // "last administrator", a short password: problems with the request, not the queue.
                    throw new Workspace.UsageException(e.getMessage());
                }
                spec.commandLine().getOut().println(result);
                spec.commandLine().getOut().flush();
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "set-role", mixinStandardHelpOptions = true, description = "Change a user's role.")
    static final class SetRole extends Change {
        @Parameters(index = "1", paramLabel = "ROLE", description = "admin, operator or viewer.")
        String role;

        @Override
        String apply(Workspace ws, User u) {
            Role r;
            try {
                r = Role.parse(role);
            } catch (IllegalArgumentException e) {
                throw new Workspace.UsageException(e.getMessage());
            }
            ws.store().updateUser(u.withRole(r));
            return u.username() + " is now " + r.name().toLowerCase(Locale.ROOT) + ".";
        }
    }

    @Command(name = "password", mixinStandardHelpOptions = true, description = "Set a user's password.")
    static final class Password extends Change {
        @Mixin
        PasswordSource password;

        @Override
        String apply(Workspace ws, User u) throws IOException {
            ws.store().setPassword(u.username(), password.read(u.username()));
            return "Password changed for " + u.username() + ".";
        }
    }

    @Command(name = "enable", mixinStandardHelpOptions = true, description = "Let a disabled user sign in again.")
    static final class Enable extends Change {
        @Override
        String apply(Workspace ws, User u) {
            ws.store().updateUser(u.withEnabled(true));
            return u.username() + " is enabled.";
        }
    }

    @Command(name = "disable", mixinStandardHelpOptions = true,
            description = "Stop a user from signing in, keeping their name in the history.")
    static final class Disable extends Change {
        @Override
        String apply(Workspace ws, User u) {
            ws.store().updateUser(u.withEnabled(false));
            return u.username() + " is disabled.";
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true,
            description = "Remove a user. Removing the only user turns access control off.")
    static final class Remove extends Change {
        @Override
        String apply(Workspace ws, User u) {
            ws.store().deleteUser(u.username());
            return "Removed " + u.username() + "." + (ws.store().accessControlEnabled() ? ""
                    : " There are no users left, so access control is off.");
        }
    }
}
