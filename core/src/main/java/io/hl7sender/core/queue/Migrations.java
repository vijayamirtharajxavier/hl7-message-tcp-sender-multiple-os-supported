package io.hl7sender.core.queue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies versioned SQL scripts in order and tracks the schema version. Each script runs in its own transaction. A
 * database written by a newer version of the application is refused rather than guessed at.
 *
 * <p>SQLite databases run {@code db/V<n>__*.sql} and track the version in {@code PRAGMA user_version}. PostgreSQL
 * databases start from a baseline ({@code db/postgres/V8__baseline.sql}, the whole schema at version 8), run any
 * later {@code db/postgres/V<n>__*.sql}, and track the version in a {@code schema_version} table.
 */
final class Migrations {

    private static final Logger LOG = LoggerFactory.getLogger(Migrations.class);

    /** Scripts in version order; index + 1 is the schema version the script produces. */
    static final List<String> SCRIPTS = List.of("V1__queue.sql", "V2__bulk_and_validation.sql",
            "V3__tls_and_notes.sql", "V4__metrics.sql", "V5__schedules.sql", "V6__scripts.sql", "V7__transports.sql",
            "V8__users.sql", "V9__application_acks.sql");

    /** PostgreSQL scripts by the version they produce; the first is the baseline for an empty database. */
    static final java.util.SortedMap<Integer, String> POSTGRES = new java.util.TreeMap<>(java.util.Map.of(
            8, "postgres/V8__baseline.sql",
            9, "postgres/V9__application_acks.sql"));

    private Migrations() {
    }

    static int latestVersion() {
        return SCRIPTS.size();
    }

    static void migrate(Connection connection) throws SQLException {
        migrate(connection, false);
    }

    static void migrate(Connection connection, boolean postgres) throws SQLException {
        if (postgres) {
            migratePostgres(connection);
            return;
        }
        int current = userVersion(connection);
        if (current > latestVersion()) {
            throw new QueueException("Queue database schema version " + current
                    + " is newer than this application supports (" + latestVersion() + "). Please upgrade.");
        }
        for (int v = current + 1; v <= latestVersion(); v++) {
            String script = SCRIPTS.get(v - 1);
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String sql : statements(load(script))) {
                    st.execute(sql);
                }
                st.execute("PRAGMA user_version = " + v);
                connection.commit();
                LOG.info("Queue database migrated to schema version {} ({})", v, script);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Advisory lock held while migrating, so two processes starting together do not both apply a script. */
    private static final String MIGRATION_LOCK = "hashtext('hl7sender.migration'), hashtext(current_schema())";

    private static void migratePostgres(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK + ")");
        }
        try {
            migratePostgresLocked(connection);
        } finally {
            try (Statement st = connection.createStatement()) {
                st.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK + ")");
            }
        }
    }

    private static void migratePostgresLocked(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version BIGINT NOT NULL)");
        }
        int current;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            current = rs.next() ? rs.getInt(1) : 0;
        }
        if (current > latestVersion()) {
            throw new QueueException("Queue database schema version " + current
                    + " is newer than this application supports (" + latestVersion() + "). Please upgrade.");
        }
        for (java.util.Map.Entry<Integer, String> e : POSTGRES.entrySet()) {
            int v = e.getKey();
            // The baseline applies only to an empty database; later scripts apply in turn.
            boolean baseline = v == POSTGRES.firstKey();
            if ((baseline && current != 0) || (!baseline && v <= current)) {
                continue;
            }
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String sql : statements(load(e.getValue()))) {
                    st.execute(sql);
                }
                st.execute("DELETE FROM schema_version");
                st.execute("INSERT INTO schema_version (version) VALUES (" + v + ")");
                connection.commit();
                current = v;
                LOG.info("Queue database (PostgreSQL) migrated to schema version {} ({})", v, e.getValue());
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Splits a script into statements on {@code ;} at the end of a line, dropping {@code --} comments. */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().strip();
                out.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        return out;
    }

    private static String load(String script) {
        try (InputStream in = Migrations.class.getResourceAsStream("db/" + script)) {
            if (in == null) {
                throw new QueueException("Missing migration script " + script);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new QueueException("Cannot read migration script " + script, e);
        }
    }
}
