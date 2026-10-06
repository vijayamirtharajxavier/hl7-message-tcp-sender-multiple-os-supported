package io.hl7sender.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Users, roles and sign-in for the queue commands. */
@Timeout(120)
class UserCliTest {

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();
    private StringWriter out = new StringWriter();
    private StringWriter err = new StringWriter();

    @BeforeEach
    void setUp() {
        Workspace.ENV.set(env::get);
    }

    @AfterEach
    void tearDown() {
        Workspace.ENV.set(System::getenv);
        System.clearProperty(Workspace.USER_PROPERTY);
        System.clearProperty(AppPaths.HOME_PROPERTY);
    }

    private int run(String... args) {
        out = new StringWriter();
        err = new StringWriter();
        System.clearProperty(Workspace.USER_PROPERTY);
        CommandLine cli = Hl7SendCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        return cli.execute(all.toArray(String[]::new));
    }

    /** Runs as {@code user}, signing in with {@code password}. */
    private int as(String user, String password, String... args) {
        env.put(Workspace.PASSWORD_ENV, password);
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--user");
        all.add(user);
        try {
            return run(all.toArray(String[]::new));
        } finally {
            env.remove(Workspace.PASSWORD_ENV);
        }
    }

    private JsonNode json() throws IOException {
        return JsonViews.COMPACT.readTree(out.toString());
    }

    @Test
    void rolesDecideWhatEachUserMayDo() throws Exception {
        Path profile = home.resolve("lab.json");
        DestinationProfiles.write(List.of(DestinationConfig.of("Lab", "127.0.0.1", 1)), profile);
        Path message = Files.writeString(home.resolve("m.hl7"),
                "MSH|^~\\&|A|B|C|D|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\rPID|1||42\r");

        // No users: everything is allowed, and the first user must be an admin.
        assertThat(run("user", "list")).isZero();
        assertThat(out.toString()).contains("No users");
        env.put(UserCommand.NEW_PASSWORD_ENV, "operator-pass");
        assertThat(run("user", "add", "olga", "--role", "operator")).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("first user must be an administrator");
        env.put(UserCommand.NEW_PASSWORD_ENV, "admin-pass");
        assertThat(run("user", "add", "Ada", "--role", "admin", "--display-name", "Ada L")).isZero();
        assertThat(out.toString()).contains("Added ada (admin)").contains("Access control is now on");

        // From now on, everyone signs in.
        assertThat(run("queue", "status")).isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(err.toString()).contains("sign in with --user");
        assertThat(as("ada", "wrong-pass", "queue", "status")).isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(err.toString()).contains("Sign-in failed");
        System.clearProperty(Workspace.USER_PROPERTY);
        env.put("HL7SENDER_USER", "ada");
        assertThat(run("queue", "status")).as("password missing").isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(err.toString()).contains("set HL7SENDER_PASSWORD");
        env.remove("HL7SENDER_USER");

        env.put(UserCommand.NEW_PASSWORD_ENV, "operator-pass");
        assertThat(as("ada", "admin-pass", "user", "add", "olga", "--role", "operator")).isZero();
        env.put(UserCommand.NEW_PASSWORD_ENV, "viewer-pass");
        assertThat(as("ada", "admin-pass", "user", "add", "vic", "--role", "viewer")).isZero();
        assertThat(as("ada", "admin-pass", "destination", "import", profile.toString())).isZero();
        assertThat(as("ada", "admin-pass", "user", "list", "--json")).isZero();
        assertThat(json().path("users")).hasSize(3);
        assertThat(json().path("users").get(0).path("displayName").asText()).isEqualTo("Ada L");

        // A viewer looks; an operator sends and works the queue; only an admin configures.
        assertThat(as("vic", "viewer-pass", "queue", "status")).isZero();
        assertThat(as("vic", "viewer-pass", "queue", "send", "-d", "Lab", message.toString()))
                .isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(err.toString()).contains("vic (viewer) may not queue messages");
        assertThat(as("olga", "operator-pass", "queue", "send", "-d", "Lab", message.toString())).isZero();
        assertThat(as("olga", "operator-pass", "destination", "pause", "Lab")).isZero();
        assertThat(as("olga", "operator-pass", "destination", "import", profile.toString()))
                .isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(as("olga", "operator-pass", "user", "list")).isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(err.toString()).contains("olga (operator) may not manage users");

        // Changes are recorded under the user's name.
        assertThat(as("vic", "viewer-pass", "queue", "list", "--json")).isZero();
        long id = json().path("messages").get(0).path("id").asLong();
        assertThat(as("vic", "viewer-pass", "queue", "show", String.valueOf(id), "--json")).isZero();
        try (io.hl7sender.core.queue.QueueStore store = io.hl7sender.core.queue.QueueStore.open(
                home.resolve("data").resolve("queue.db"))) {
            assertThat(store.audit(id)).extracting(io.hl7sender.core.queue.AuditEvent::actor).containsOnly("olga");
        }

        // Admin chores: roles, passwords, disabling, and the last admin is protected.
        assertThat(as("ada", "admin-pass", "user", "set-role", "olga", "viewer")).isZero();
        assertThat(as("olga", "operator-pass", "queue", "send", "-d", "Lab", message.toString()))
                .isEqualTo(ExitCodes.ACCESS_DENIED);
        env.put(UserCommand.NEW_PASSWORD_ENV, "new-viewer-pass");
        assertThat(as("ada", "admin-pass", "user", "password", "vic")).isZero();
        assertThat(as("vic", "viewer-pass", "queue", "status")).isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(as("vic", "new-viewer-pass", "queue", "status")).isZero();
        assertThat(as("ada", "admin-pass", "user", "disable", "vic")).isZero();
        assertThat(as("vic", "new-viewer-pass", "queue", "status")).isEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(as("ada", "admin-pass", "user", "set-role", "ada", "viewer")).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("last administrator");
        assertThat(as("ada", "admin-pass", "user", "set-role", "nobody", "viewer")).isEqualTo(ExitCodes.USAGE);
        assertThat(as("ada", "admin-pass", "user", "add", "x", "--role", "root")).isEqualTo(ExitCodes.USAGE);

        // Commands that do not use the queue need no sign-in.
        assertThat(run("validate", message.toString())).isNotEqualTo(ExitCodes.ACCESS_DENIED);
        assertThat(run("database", "show")).isZero();
    }
}
