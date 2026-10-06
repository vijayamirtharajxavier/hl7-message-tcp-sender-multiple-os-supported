package io.hl7sender.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.secrets.InMemorySecretStore;
import io.hl7sender.core.send.Hl7Sender;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two "computers" (separate settings folders) sharing one PostgreSQL queue: one delivers, the other adds work, and
 * the other takes over delivery when the first stops. Enabled like {@code PostgresQueueStoreTest}.
 */
@Timeout(90)
@EnabledIfEnvironmentVariable(named = "HL7SENDER_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PostgresSharedQueueTest {

    static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|%s|P|2.5.1\r"
            + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE\rPV1|1|I";

    @TempDir
    Path home;

    private final String baseUrl = System.getenv("HL7SENDER_TEST_POSTGRES_URL");
    private final String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=hl7s_core_shared";
    private final String user = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_USER", "");
    private final String password = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_PASSWORD", "");
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private TestListener listener;
    private AppSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        // Each test class has its own schema, so the modules' tests can run at the same time.
        try (Connection c = DriverManager.getConnection(baseUrl, user, password);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS hl7s_core_shared CASCADE");
            st.execute("CREATE SCHEMA hl7s_core_shared");
        }
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        settings = AppSettings.defaults().withDatabase(AppSettings.Database.postgres(url, user));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : open.reversed()) {
            c.close();
        }
        listener.close();
    }

    private AppPaths computer(String name) {
        Path dir = home.resolve(name);
        return new AppPaths(dir.resolve("config"), dir.resolve("data"), dir.resolve("logs"));
    }

    private InMemorySecretStore secrets() {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put(QueueOpener.POSTGRES_PASSWORD, password);
        return s;
    }

    private Optional<QueueRuntime> start(AppPaths paths) throws Exception {
        Optional<QueueRuntime> r = QueueRuntime.start(paths, () -> settings, secrets(), new Hl7Sender(),
                Clock.systemUTC());
        r.ifPresent(open::add);
        return r;
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void oneComputerDeliversWorkAddedByAnotherAndTheOtherTakesOver() throws Exception {
        AppPaths first = computer("first");
        AppPaths second = computer("second");
        QueueRuntime delivering = start(first).orElseThrow();
        assertThat(delivering.store().isPostgres()).isTrue();
        assertThat(start(second)).as("only one computer delivers").isEmpty();

        QueueOpener.Opened shared = QueueOpener.ownOrShare(second, settings.database(), false, secrets(),
                Clock.systemUTC());
        open.add(shared);
        assertThat(shared.owner()).isFalse();
        QueueStore store = shared.store();
        store.setUserActor("bob");
        DestinationConfig lab = store.saveDestination(DestinationConfig.of("Lab", "127.0.0.1", listener.port())
                .withTimeouts(1_000, 1_000));
        store.enqueue(lab.id(), "S1", "ADT^A01", MESSAGE.formatted("S1"));
        // The delivering computer is not told; it finds the new destination and message on its next re-read.
        await("delivery of work added on the other computer", () -> received.size() == 1);
        assertThat(received.getFirst().controlId()).isEqualTo("S1");
        assertThat(store.destinationAudit(lab.id(), 50)).anySatisfy(e -> assertThat(e.actor()).isEqualTo("bob"));

        delivering.close();
        open.remove(delivering);
        QueueRuntime takeover = start(second).orElseThrow();
        takeover.store().enqueue(lab.id(), "S2", "ADT^A01", MESSAGE.formatted("S2"));
        takeover.engine().reload();
        await("delivery after the takeover", () -> received.size() == 2);
    }
}
