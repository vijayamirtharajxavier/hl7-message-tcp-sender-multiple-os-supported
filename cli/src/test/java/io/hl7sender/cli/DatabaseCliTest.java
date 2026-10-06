package io.hl7sender.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.SettingsStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Timeout(60)
class DatabaseCliTest {

    @TempDir
    Path home;

    private StringWriter out = new StringWriter();
    private StringWriter err = new StringWriter();

    @AfterEach
    void tearDown() {
        System.clearProperty(AppPaths.HOME_PROPERTY);
    }

    private int run(String... args) {
        out = new StringWriter();
        err = new StringWriter();
        CommandLine cli = Hl7SendCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        return cli.execute(all.toArray(String[]::new));
    }

    private JsonNode json() throws IOException {
        return JsonViews.COMPACT.readTree(out.toString());
    }

    @Test
    void sqliteIsTheDefaultAndBadUrlsAreRefused() throws Exception {
        assertThat(run("database", "show", "--json")).isZero();
        assertThat(json().path("type").asText()).isEqualTo("sqlite");
        assertThat(json().path("file").asText()).endsWith("queue.db");
        assertThat(run("database", "test")).isZero();
        assertThat(out.toString()).startsWith("OK: ").contains("0 destination(s)");

        assertThat(run("database", "use-postgres", "postgres://nope")).isEqualTo(ExitCodes.USAGE);
        assertThat(err.toString()).contains("jdbc:postgresql://");
        // Nothing listens on port 1: the connection test fails and nothing is saved.
        assertThat(run("database", "use-postgres", "jdbc:postgresql://127.0.0.1:1/x", "--db-user", "u",
                "--password-env", "PATH")).isEqualTo(ExitCodes.QUEUE_UNAVAILABLE);
        assertThat(run("database", "show", "--json")).isZero();
        assertThat(json().path("type").asText()).isEqualTo("sqlite");

        // Saved without a test, the problem shows at the next command, with a clear message.
        assertThat(run("database", "use-postgres", "jdbc:postgresql://127.0.0.1:1/x", "--no-test")).isZero();
        assertThat(run("database", "test", "--json")).isEqualTo(ExitCodes.QUEUE_UNAVAILABLE);
        assertThat(json().path("ok").asBoolean()).isFalse();
        assertThat(json().path("error").asText()).contains("Cannot open the PostgreSQL queue");
        assertThat(run("destination", "list")).isNotZero();
        assertThat(run("database", "use-sqlite")).isZero();
        assertThat(new SettingsStore(home.resolve("config").resolve("settings.json")).load().database().postgres())
                .isFalse();
        assertThat(run("destination", "list")).isZero();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HL7SENDER_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
    void commandsUseTheSharedPostgresDatabase() throws Exception {
        String baseUrl = System.getenv("HL7SENDER_TEST_POSTGRES_URL");
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=hl7s_cli";
        String user = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_USER", "");
        String password = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_PASSWORD", "");
        // Each test class has its own schema, so the modules' tests can run at the same time.
        try (Connection c = DriverManager.getConnection(baseUrl, user, password);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS hl7s_cli CASCADE");
            st.execute("CREATE SCHEMA hl7s_cli");
        }
        java.io.InputStream stdin = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream((password + "\n").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(run("database", "use-postgres", url, "--db-user", user, "--password-stdin")).as(err.toString())
                    .isZero();
        } finally {
            System.setIn(stdin);
        }
        assertThat(out.toString()).contains("Connected to jdbc:postgresql:").contains("0 destination(s)");
        assertThat(run("database", "show", "--json")).isZero();
        assertThat(json().path("type").asText()).isEqualTo("postgres");
        assertThat(json().path("passwordStored").asBoolean()).isTrue();
        assertThat(out.toString()).doesNotContain(password.isEmpty() ? "\u0000" : password);

        Path profile = home.resolve("profiles.json");
        io.hl7sender.core.queue.DestinationProfiles.write(List.of(io.hl7sender.core.queue.DestinationConfig.of(
                "Shared lab", "127.0.0.1", 2575)), profile);
        assertThat(run("destination", "import", profile.toString())).as(err.toString()).isZero();
        assertThat(run("database", "test")).isZero();
        assertThat(out.toString()).contains("1 destination(s)");
        assertThat(run("database", "use-sqlite")).isZero();
        assertThat(run("destination", "list", "--json")).isZero();
        assertThat(json().path("destinations")).isEmpty();
    }
}
