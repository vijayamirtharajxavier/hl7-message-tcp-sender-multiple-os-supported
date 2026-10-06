package io.hl7sender.core.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs every {@link QueueStoreTest} against a PostgreSQL server, plus the checks that only apply to a shared queue.
 * Enabled when {@code HL7SENDER_TEST_POSTGRES_URL} is set (with {@code HL7SENDER_TEST_POSTGRES_USER} and
 * {@code HL7SENDER_TEST_POSTGRES_PASSWORD}); the database's tables are dropped before each test.
 */
@EnabledIfEnvironmentVariable(named = "HL7SENDER_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PostgresQueueStoreTest extends QueueStoreTest {

    static final String SCHEMA = "hl7s_core_store";

    static String baseUrl() {
        return System.getenv("HL7SENDER_TEST_POSTGRES_URL");
    }

    /** The test server's URL, with this class's schema. */
    static String url() {
        return baseUrl() + (baseUrl().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    static String user() {
        return System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_USER", "");
    }

    static String password() {
        return System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_PASSWORD", "");
    }

    /** Drops and creates this class's schema, so each test starts from an empty database. */
    static void resetDatabase() throws Exception {
        // Each test class has its own schema, so the modules' tests can run at the same time.
        try (Connection c = DriverManager.getConnection(baseUrl(), user(), password());
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS hl7s_core_store CASCADE");
            st.execute("CREATE SCHEMA hl7s_core_store");
        }
    }

    static QueueStore openPostgres() {
        return QueueStore.openPostgres(url(), user(), password(), Clock.systemUTC());
    }

    @Override
    QueueStore openStore() {
        try {
            resetDatabase();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        QueueStore s = openPostgres();
        assertThat(s.isPostgres()).isTrue();
        assertThat(s.location()).startsWith("jdbc:postgresql:").doesNotContain("password");
        return s;
    }

    private static int schemaVersion() throws Exception {
        try (Connection c = DriverManager.getConnection(url(), user(), password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    @Override
    @Test
    void migratesOnceAndReopens() throws Exception {
        store.close();
        store = openPostgres();
        assertThat(store.destinations()).extracting(DestinationConfig::name).containsExactly("Mirth");
        assertThat(schemaVersion()).isEqualTo(Migrations.latestVersion());
    }

    @Override
    @Test
    void refusesNewerSchema() throws Exception {
        store.close();
        try (Connection c = DriverManager.getConnection(url(), user(), password());
             Statement st = c.createStatement()) {
            st.execute("UPDATE schema_version SET version = 99");
        }
        assertThatThrownBy(PostgresQueueStoreTest::openPostgres)
                .isInstanceOf(QueueException.class).hasMessageContaining("newer");
        store = openStore();
    }

    @Override
    void migratesAVersion1DatabaseInPlace() {
        // SQLite only: PostgreSQL databases start at the version 8 baseline.
    }

    @Test
    void refusesBadUrlsAndCredentials() {
        assertThatThrownBy(() -> QueueStore.openPostgres("jdbc:sqlite:x.db", "", "", Clock.systemUTC()))
                .isInstanceOf(QueueException.class).hasMessageContaining("jdbc:postgresql://");
        String missing = url().replaceFirst("/[^/?]+(\\?|$)", "/hl7sender_no_such_db$1");
        assertThatThrownBy(() -> QueueStore.openPostgres(missing, user(), password(), Clock.systemUTC()))
                .isInstanceOf(QueueException.class).hasMessageContaining("Cannot open the PostgreSQL queue");
    }

    @Test
    void onlyOneStoreHoldsTheDeliveryLock() {
        try (QueueStore other = openPostgres()) {
            assertThat(store.tryLockDelivery()).isTrue();
            assertThat(other.tryLockDelivery()).isFalse();
            store.close();
            // The lock goes with the connection, so another process can take over at once.
            assertThat(other.tryLockDelivery()).isTrue();
        } finally {
            store = openPostgres();
        }
    }

    @Test
    void storesOpenedTogetherMigrateOnceAndShareOneQueue() throws Exception {
        store.close();
        resetDatabase();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<QueueStore> stores = new ArrayList<>();
        try {
            List<Future<QueueStore>> opened = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                opened.add(pool.submit((Callable<QueueStore>) PostgresQueueStoreTest::openPostgres));
            }
            for (Future<QueueStore> f : opened) {
                stores.add(f.get());
            }
            assertThat(schemaVersion()).isEqualTo(Migrations.latestVersion());
            DestinationConfig shared = stores.get(0).saveDestination(DestinationConfig.of("Shared", "localhost", 1));
            List<Future<?>> writes = new ArrayList<>();
            for (QueueStore s : stores) {
                writes.add(pool.submit(() -> {
                    for (int i = 0; i < 25; i++) {
                        s.enqueue(shared.id(), "C" + i, "ADT^A01", "MSH|" + i);
                    }
                }));
            }
            for (Future<?> f : writes) {
                f.get();
            }
            assertThat(stores.get(3).counts(shared.id())).containsEntry(MessageStatus.QUEUED, 100);
            stores.get(1).setUserActor("alice");
            stores.get(1).setPaused(shared.id(), true);
            assertThat(stores.get(2).destination(shared.id())).get().extracting(DestinationConfig::paused)
                    .isEqualTo(true);
        } finally {
            stores.forEach(QueueStore::close);
            pool.shutdownNow();
            store = openStore();
            dest = store.saveDestination(DestinationConfig.of("Mirth", "localhost", 6661));
        }
    }
}
