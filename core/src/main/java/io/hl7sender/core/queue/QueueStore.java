package io.hl7sender.core.queue;

import io.hl7sender.core.ack.AckError;
import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.auth.PasswordHasher;
import io.hl7sender.core.auth.Role;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.monitor.DestinationStats;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.tls.TlsSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable storage for destinations, queued messages, attempts, ACKs and the audit trail, in a single
 * SQLite file (WAL journal, {@code synchronous=FULL}, so a committed change survives a crash or power
 * loss).
 *
 * <p>Every state transition, and its audit event, is written in one transaction. Transitions are
 * conditional on the current status (for example "re-queue only if dead-lettered"), so concurrent UI
 * actions and the delivery engine cannot corrupt a message's lifecycle.
 *
 * <p>Thread-safe: all access goes through one connection, serialized by this object's monitor.
 */
public final class QueueStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(QueueStore.class);
    private static final java.security.SecureRandom KEY_RANDOM = new java.security.SecureRandom();

    /** Actor names recorded in the audit trail. */
    public static final String ACTOR_ENGINE = "engine";
    public static final String ACTOR_USER = "user";
    public static final String ACTOR_RECOVERY = "recovery";

    private static final String MESSAGE_COLUMNS = "id, destination_id, queue_seq, control_id, message_type, payload, "
            + "status, attempts, next_attempt_at, possible_duplicate, last_outcome, last_error, created_at, "
            + "updated_at, completed_at, source, batch_id";

    private final Connection connection;
    private final Clock clock;
    private final Path file;
    private final boolean postgres;
    private final String location;
    private volatile String userActor = ACTOR_USER;

    private QueueStore(Connection connection, Path file, Clock clock) {
        this(connection, file, clock, false, file.toAbsolutePath().toString());
    }

    private QueueStore(Connection connection, Path file, Clock clock, boolean postgres, String location) {
        this.connection = connection;
        this.file = file;
        this.clock = clock;
        this.postgres = postgres;
        this.location = location;
    }

    /**
     * Advisory lock held by the process that delivers from a shared PostgreSQL queue. It is keyed by schema, so
     * separate queues in one database (see {@code currentSchema} in the URL) do not block each other.
     */
    static final String DELIVERY_LOCK = "hashtext('hl7sender.delivery'), hashtext(current_schema())";

    /**
     * Opens (creating the schema if needed) a queue database shared on a PostgreSQL server, so several app windows
     * and services on different computers can work on one queue. Only one of them delivers at a time: see
     * {@link #tryLockDelivery()}.
     *
     * @param jdbcUrl e.g. {@code jdbc:postgresql://db.example.org:5432/hl7sender}
     */
    public static QueueStore openPostgres(String jdbcUrl, String user, String password, Clock clock) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new QueueException("A PostgreSQL URL starts with jdbc:postgresql://");
        }
        try {
            java.util.Properties props = new java.util.Properties();
            if (user != null && !user.isEmpty()) {
                props.setProperty("user", user);
            }
            if (password != null) {
                props.setProperty("password", password);
            }
            props.setProperty("ApplicationName", "HL7 Sender");
            props.setProperty("connectTimeout", "10");
            Connection c = new org.postgresql.Driver().connect(jdbcUrl, props);
            if (c == null) {
                throw new QueueException("Not a PostgreSQL URL: " + jdbcUrl);
            }
            try {
                Migrations.migrate(c, true);
            } catch (SQLException | RuntimeException e) {
                try {
                    c.close();
                } catch (SQLException closeFailed) {
                    e.addSuppressed(closeFailed);
                }
                throw e;
            }
            String where = jdbcUrl.replaceAll("[?].*$", "");
            LOG.info("Queue database opened: {} (PostgreSQL)", where);
            return new QueueStore(c, null, clock, true, where);
        } catch (SQLException e) {
            throw new QueueException("Cannot open the PostgreSQL queue database: " + e.getMessage(), e);
        }
    }

    /** True for a shared PostgreSQL queue, false for a local SQLite file. */
    public boolean isPostgres() {
        return postgres;
    }

    /** Where the queue is: the SQLite file, or the PostgreSQL URL without parameters. */
    public String location() {
        return location;
    }

    /**
     * For a PostgreSQL queue: takes the delivery lock unless another process holds it. The lock is held until this
     * store is closed (or its connection drops). Always true for SQLite, which uses a lock file instead.
     */
    public synchronized boolean tryLockDelivery() {
        if (!postgres) {
            return true;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(" + DELIVERY_LOCK + ")")) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            throw new QueueException("Cannot take the delivery lock: " + e.getMessage(), e);
        }
    }

    /** Records user actions in the audit trail under {@code username} (the signed-in user). */
    public void setUserActor(String username) {
        this.userActor = username == null || username.isBlank() ? ACTOR_USER : username;
    }

    public String userActor() {
        return userActor;
    }

    /** Opens (creating if needed) and migrates the queue database at {@code file}. */
    public static QueueStore open(Path file, Clock clock) {
        return open(file, clock, null);
    }

    /**
     * Opens (creating if needed) and migrates the queue database. If {@code key} is not null, the
     * database is encrypted with it (SQLCipher 4 format, AES-256); an existing database must already be
     * encrypted with that key (see {@link #encrypt}).
     *
     * @throws QueueException with a clear message if the database is encrypted and the key is missing or wrong
     */
    public static QueueStore open(Path file, Clock clock, String key) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection c = connect(file, key);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode = WAL");
                    st.execute("PRAGMA synchronous = FULL");
                    st.execute("PRAGMA foreign_keys = ON");
                    st.execute("PRAGMA busy_timeout = 5000");
                }
                Migrations.migrate(c);
            } catch (SQLException | RuntimeException e) {
                // Never leak the handle: on Windows an open handle keeps the database file locked.
                try {
                    c.close();
                } catch (SQLException closeFailed) {
                    e.addSuppressed(closeFailed);
                }
                throw e;
            }
            LOG.info("Queue database opened: {}{}", file.toAbsolutePath(), key == null ? "" : " (encrypted)");
            return new QueueStore(c, file, clock);
        } catch (SQLException e) {
            if (isNotADatabase(e)) {
                throw new QueueException(key == null
                        ? "The queue database is encrypted and no key was provided: " + file
                        : "The queue database key is wrong, or the file is not a queue database: " + file, e);
            }
            throw new QueueException("Cannot open queue database " + file + ": " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new QueueException("Cannot open queue database " + file + ": " + e.getMessage(), e);
        }
    }

    /** True if {@code file} exists and cannot be read without a key. */
    public static boolean isEncrypted(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        try (Connection c = connect(file, null); Statement st = c.createStatement()) {
            st.executeQuery("SELECT count(*) FROM sqlite_master").close();
            return false;
        } catch (SQLException e) {
            return isNotADatabase(e);
        }
    }

    /**
     * Encrypts an unencrypted database in place with {@code key}. The store must be closed. The key must
     * be kept safe: without it the database cannot be opened.
     */
    public static void encrypt(Path file, String key) {
        rekey(file, null, requireHexKey(key));
    }

    /** Decrypts an encrypted database in place. The store must be closed. */
    public static void decrypt(Path file, String key) {
        rekey(file, key, "");
    }

    /** A new random 256-bit key as 64 hex characters. */
    public static String newKey() {
        byte[] b = new byte[32];
        KEY_RANDOM.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    private static void rekey(Path file, String currentKey, String newKey) {
        // Always use the SQLCipher settings, even for a plain database, so the new key applies SQLCipher 4
        // (the library's default cipher is different and would not open with the settings used by open()).
        try (Connection c = connectWithCipher(file, currentKey); Statement st = c.createStatement()) {
            // Encryption changes are not supported in WAL mode; switching to DELETE also checkpoints the WAL.
            st.execute("PRAGMA journal_mode = DELETE");
            st.execute(rekeyStatement(newKey));
            st.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException e) {
            throw new QueueException("Could not change the database encryption: " + e.getMessage(), e);
        }
        LOG.info("Queue database {} {}", file.toAbsolutePath(), newKey.isEmpty() ? "decrypted" : "encrypted");
    }

    /** PRAGMA rekey takes no bind parameters; the key is validated as hex (or empty) so it cannot inject SQL. */
    private static String rekeyStatement(String hexOrEmpty) {
        if (!hexOrEmpty.isEmpty()) {
            requireHexKey(hexOrEmpty);
        }
        return "PRAGMA rekey = '" + hexOrEmpty + "'";
    }

    private static String requireHexKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{32,128}")) {
            throw new IllegalArgumentException("Database key must be 32-128 hex characters");
        }
        return key;
    }

    private static Connection connect(Path file, String key) throws SQLException {
        if (key == null) {
            return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        }
        return connectWithCipher(file, key);
    }

    /** Connects with SQLCipher 4 settings; {@code key} may be null for a database that is not encrypted yet. */
    private static Connection connectWithCipher(Path file, String key) throws SQLException {
        org.sqlite.mc.SQLiteMCConfig.Builder config = org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults();
        if (key != null) {
            config.withKey(key);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.build().toProperties());
    }

    private static boolean isNotADatabase(SQLException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("SQLITE_NOTADB") || m.contains("not a database");
    }

    public static QueueStore open(Path file) {
        return open(file, Clock.systemUTC());
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("Error closing queue database: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Destinations

    public synchronized List<DestinationConfig> destinations() {
        return query("SELECT * FROM destination ORDER BY lower(name), id", ps -> { }, QueueStore::destination);
    }

    public synchronized Optional<DestinationConfig> destination(long id) {
        return query("SELECT * FROM destination WHERE id = ?", ps -> ps.setLong(1, id), QueueStore::destination)
                .stream().findFirst();
    }

    /** Inserts ({@code id == 0}) or updates a destination and returns it with its ID. */
    public synchronized DestinationConfig saveDestination(DestinationConfig d) {
        long now = clock.millis();
        return tx(() -> {
            if (d.id() == 0) {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO destination (name, host, port, "
                        + "connect_timeout_ms, ack_timeout_ms, charset, ack_mode, connection_mode, "
                        + "retry_max_attempts, retry_base_ms, retry_max_ms, retry_jitter, cb_failure_threshold, "
                        + "cb_cool_down_ms, ack_policy, paused, max_per_second, validation_level, profile_path, "
                        + "watch_folder, tls_enabled, tls_trust_path, tls_key_path, tls_verify_hostname, "
                        + "tls_protocols, secret_ref, notes, script, transport, transport_options, app_ack_port, "
                        + "app_ack_timeout_ms, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bindDestination(ps, d);
                    ps.setLong(33, now);
                    ps.setLong(34, now);
                    ps.executeUpdate();
                    long id = generatedKey(ps);
                    insertAudit(null, id, null, null, userActor, "Destination '" + d.name() + "' created ("
                            + d.address() + ")");
                    return d.withId(id);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE destination SET name=?, host=?, port=?, "
                    + "connect_timeout_ms=?, ack_timeout_ms=?, charset=?, ack_mode=?, connection_mode=?, "
                    + "retry_max_attempts=?, retry_base_ms=?, retry_max_ms=?, retry_jitter=?, cb_failure_threshold=?, "
                    + "cb_cool_down_ms=?, ack_policy=?, paused=?, max_per_second=?, validation_level=?, "
                    + "profile_path=?, watch_folder=?, tls_enabled=?, tls_trust_path=?, tls_key_path=?, "
                    + "tls_verify_hostname=?, tls_protocols=?, secret_ref=?, notes=?, script=?, transport=?, "
                    + "transport_options=?, app_ack_port=?, app_ack_timeout_ms=?, updated_at=? WHERE id=?")) {
                bindDestination(ps, d);
                ps.setLong(33, now);
                ps.setLong(34, d.id());
                if (ps.executeUpdate() == 0) {
                    throw new QueueException("Destination " + d.id() + " does not exist");
                }
                insertAudit(null, d.id(), null, null, userActor, "Destination '" + d.name() + "' updated ("
                        + d.address() + ")");
                return d;
            }
        });
    }

    /** Deletes a destination together with all of its messages and history. */
    public synchronized void deleteDestination(long id) {
        update("DELETE FROM destination WHERE id = ?", ps -> ps.setLong(1, id));
    }

    public synchronized void setPaused(long destinationId, boolean paused) {
        tx(() -> {
            execute("UPDATE destination SET paused = ?, updated_at = ? WHERE id = ?", ps -> {
                ps.setInt(1, paused ? 1 : 0);
                ps.setLong(2, clock.millis());
                ps.setLong(3, destinationId);
            });
            insertAudit(null, destinationId, null, null, userActor, paused ? "Delivery paused" : "Delivery resumed");
            return null;
        });
    }

    /** Records a destination-level event (circuit opened, connection lost, ...) in the audit trail. */
    public synchronized void auditDestination(long destinationId, String actor, String detail) {
        tx(() -> {
            insertAudit(null, destinationId, null, null, actor, detail);
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Messages

    /** Adds a message to the end of a destination's queue. */
    public synchronized QueuedMessage enqueue(long destinationId, String controlId, String messageType,
                                              String payload) {
        return enqueue(destinationId, controlId, messageType, payload, null, null);
    }

    /** Adds a message to the end of a destination's queue, recording where it came from. */
    public synchronized QueuedMessage enqueue(long destinationId, String controlId, String messageType,
                                              String payload, String source, String batchId) {
        long id = tx(() -> insertMessage(destinationId, new NewMessage(controlId, messageType, payload, source),
                batchId, clock.millis()));
        return message(id).orElseThrow();
    }

    /**
     * A message to insert with {@link #enqueueAll}.
     *
     * @param controlId   MSH-10
     * @param messageType MSH-9
     * @param payload     wire text
     * @param source      where it came from (may be null)
     */
    public record NewMessage(String controlId, String messageType, String payload, String source) {
    }

    /**
     * Adds many messages to the end of a queue in one transaction, which is much faster than one
     * transaction per message. Either all are stored or none are.
     *
     * @return the new message IDs, in order
     */
    public synchronized List<Long> enqueueAll(long destinationId, List<NewMessage> messages, String batchId) {
        long now = clock.millis();
        return tx(() -> {
            List<Long> ids = new ArrayList<>(messages.size());
            for (NewMessage m : messages) {
                ids.add(insertMessage(destinationId, m, batchId, now));
            }
            return ids;
        });
    }

    private long insertMessage(long destinationId, NewMessage m, String batchId, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO message (destination_id, queue_seq, "
                + "control_id, message_type, payload, status, attempts, next_attempt_at, created_at, updated_at, "
                + "source, batch_id) VALUES (?, (SELECT COALESCE(MAX(queue_seq), 0) + 1 FROM message), ?, ?, ?, ?, "
                + "0, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, destinationId);
            ps.setString(2, m.controlId());
            ps.setString(3, m.messageType());
            ps.setString(4, m.payload());
            ps.setString(5, MessageStatus.QUEUED.name());
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setString(9, m.source());
            ps.setString(10, batchId);
            ps.executeUpdate();
            long newId = generatedKey(ps);
            insertAudit(newId, destinationId, null, MessageStatus.QUEUED, userActor,
                    m.source() == null ? "Enqueued" : "Enqueued from " + m.source());
            return newId;
        }
    }

    /** True if a message with this MSH-10 is already stored for the destination. */
    public synchronized boolean controlIdExists(long destinationId, String controlId) {
        return !query("SELECT 1 FROM message WHERE destination_id = ? AND control_id = ? LIMIT 1", ps -> {
            ps.setLong(1, destinationId);
            ps.setString(2, controlId);
        }, rs -> 1).isEmpty();
    }

    public synchronized Optional<QueuedMessage> message(long id) {
        return query("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE id = ?", ps -> ps.setLong(1, id),
                QueueStore::message).stream().findFirst();
    }

    /**
     * The message at the head of a destination's queue: the lowest {@code queue_seq} that is QUEUED or
     * RETRY_PENDING. It may not be due yet. Strict FIFO means nothing behind it is sent until it is resolved.
     */
    public synchronized Optional<QueuedMessage> head(long destinationId) {
        return query("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE destination_id = ? AND status IN (?, ?) "
                + "ORDER BY queue_seq, id LIMIT 1", ps -> {
                    ps.setLong(1, destinationId);
                    ps.setString(2, MessageStatus.QUEUED.name());
                    ps.setString(3, MessageStatus.RETRY_PENDING.name());
                }, QueueStore::message).stream().findFirst();
    }

    /** Messages for a destination with any of {@code statuses}, in queue order or newest first. */
    public synchronized List<QueuedMessage> messages(long destinationId, Collection<MessageStatus> statuses,
                                                     int limit, boolean newestFirst) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String in = statuses.stream().map(s -> "?").collect(Collectors.joining(","));
        String order = newestFirst ? "updated_at DESC, id DESC" : "queue_seq";
        List<MessageStatus> list = List.copyOf(statuses);
        return query("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE destination_id = ? AND status IN (" + in
                + ") ORDER BY " + order + " LIMIT ?", ps -> {
                    ps.setLong(1, destinationId);
                    for (int i = 0; i < list.size(); i++) {
                        ps.setString(i + 2, list.get(i).name());
                    }
                    ps.setInt(list.size() + 2, limit);
                }, QueueStore::message);
    }

    /** Number of messages per status for a destination. Statuses with no messages are 0. */
    public synchronized Map<MessageStatus, Integer> counts(long destinationId) {
        Map<MessageStatus, Integer> counts = new EnumMap<>(MessageStatus.class);
        for (MessageStatus s : MessageStatus.values()) {
            counts.put(s, 0);
        }
        query("SELECT status, COUNT(*) FROM message WHERE destination_id = ? GROUP BY status",
                ps -> ps.setLong(1, destinationId), rs -> {
                    counts.put(MessageStatus.valueOf(rs.getString(1)), rs.getInt(2));
                    return null;
                });
        return counts;
    }

    /**
     * Delivery statistics for a destination from the attempts that finished in {@code [from, to)}.
     * Throughput is bucketed per minute, oldest first.
     */
    public synchronized DestinationStats stats(long destinationId, Instant from, Instant to) {
        Map<SendOutcome, Integer> outcomes = new EnumMap<>(SendOutcome.class);
        long[] latency = {-1, -1};
        long start = from.toEpochMilli();
        long end = to.toEpochMilli();
        query("SELECT a.outcome, COUNT(*) FROM attempt a JOIN message m ON m.id = a.message_id "
                + "WHERE m.destination_id = ? AND a.finished_at >= ? AND a.finished_at < ? AND a.outcome IS NOT NULL "
                + "GROUP BY a.outcome", ps -> {
                    ps.setLong(1, destinationId);
                    ps.setLong(2, start);
                    ps.setLong(3, end);
                }, rs -> {
                    try {
                        outcomes.put(SendOutcome.valueOf(rs.getString(1)), rs.getInt(2));
                    } catch (IllegalArgumentException unknown) {
                        LOG.debug("Ignoring unknown outcome {}", rs.getString(1));
                    }
                    return null;
                });
        query("SELECT AVG(a.round_trip_ms), MAX(a.round_trip_ms) FROM attempt a JOIN message m ON m.id = a.message_id "
                + "WHERE m.destination_id = ? AND a.finished_at >= ? AND a.finished_at < ? "
                + "AND a.round_trip_ms IS NOT NULL", ps -> {
                    ps.setLong(1, destinationId);
                    ps.setLong(2, start);
                    ps.setLong(3, end);
                }, rs -> {
                    double avg = rs.getDouble(1);
                    if (!rs.wasNull()) {
                        latency[0] = Math.round(avg);
                        latency[1] = rs.getLong(2);
                    }
                    return null;
                });
        int minutes = (int) Math.max(1, (end - start + 59_999) / 60_000);
        int[] buckets = new int[minutes];
        query("SELECT (a.finished_at - ?) / 60000, COUNT(*) FROM attempt a JOIN message m ON m.id = a.message_id "
                + "WHERE m.destination_id = ? AND a.finished_at >= ? AND a.finished_at < ? "
                + "AND a.outcome IN ('ACCEPTED', 'SENT_NO_ACK') GROUP BY 1", ps -> {
                    ps.setLong(1, start);
                    ps.setLong(2, destinationId);
                    ps.setLong(3, start);
                    ps.setLong(4, end);
                }, rs -> {
                    int i = rs.getInt(1);
                    if (i >= 0 && i < minutes) {
                        buckets[i] = rs.getInt(2);
                    }
                    return null;
                });
        List<Integer> perMinute = new ArrayList<>(minutes);
        for (int b : buckets) {
            perMinute.add(b);
        }
        return new DestinationStats(destinationId, from, to, outcomes, latency[0], latency[1], perMinute);
    }

    /**
     * Marks the message IN_FLIGHT and records a new attempt. This is committed before any bytes are
     * sent (outbox pattern).
     *
     * @return the attempt ID
     * @throws QueueException if the message is no longer QUEUED or RETRY_PENDING
     */
    public synchronized long beginAttempt(long messageId) {
        long now = clock.millis();
        return tx(() -> {
            QueuedMessage m = message(messageId).orElseThrow(() -> new QueueException("No message " + messageId));
            if (m.status() != MessageStatus.QUEUED && m.status() != MessageStatus.RETRY_PENDING) {
                throw new QueueException("Message " + messageId + " is " + m.status() + ", not dispatchable");
            }
            execute("UPDATE message SET status = ?, attempts = attempts + 1, updated_at = ? WHERE id = ?", ps -> {
                ps.setString(1, MessageStatus.IN_FLIGHT.name());
                ps.setLong(2, now);
                ps.setLong(3, messageId);
            });
            int attemptNo = query("SELECT COUNT(*) FROM attempt WHERE message_id = ?", ps -> ps.setLong(1, messageId),
                    rs -> rs.getInt(1)).get(0) + 1;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO attempt (message_id, attempt_no, started_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, messageId);
                ps.setInt(2, attemptNo);
                ps.setLong(3, now);
                ps.executeUpdate();
                long attemptId = generatedKey(ps);
                insertAudit(messageId, m.destinationId(), m.status(), MessageStatus.IN_FLIGHT, ACTOR_ENGINE,
                        "Attempt " + attemptNo);
                return attemptId;
            }
        });
    }

    /**
     * Records the result of an attempt and moves the message to {@code newStatus}, all in one transaction.
     *
     * @param nextAttemptAt     when to retry (only meaningful for RETRY_PENDING)
     * @param possibleDuplicate set the possible-duplicate flag (it is never cleared)
     * @param reason            audit detail, e.g. "Retry 3 in 8 s" or "Retries exhausted"
     */
    public synchronized void completeAttempt(long messageId, long attemptId, SendResult result,
                                             MessageStatus newStatus, Instant nextAttemptAt,
                                             boolean possibleDuplicate, String reason) {
        completeAttempt(messageId, attemptId, result, newStatus, nextAttemptAt, possibleDuplicate, reason, null);
    }

    /**
     * What a message committed with CA waits for: its MSH-16 ({@code AL}, {@code ER} or {@code SU}) and when the
     * wait ends.
     */
    public record AppAckWait(String mode, Instant dueAt) {
    }

    /** Last outcome of a message whose application ACK did not arrive in time (MSH-16 AL or SU). */
    public static final String APP_ACK_TIMEOUT = "APP_ACK_TIMEOUT";
    /** Last outcome of a message with MSH-16 ER whose wait ended without an error: no news is good news. */
    public static final String APP_ACK_NOT_SENT = "APP_ACK_NOT_SENT";

    /**
     * As {@link #completeAttempt(long, long, SendResult, MessageStatus, Instant, boolean, String)}, and, when
     * {@code newStatus} is {@link MessageStatus#AWAITING_APP_ACK}, records what the message waits for.
     */
    public synchronized void completeAttempt(long messageId, long attemptId, SendResult result,
                                             MessageStatus newStatus, Instant nextAttemptAt,
                                             boolean possibleDuplicate, String reason, AppAckWait wait) {
        long now = clock.millis();
        tx(() -> {
            execute("UPDATE attempt SET finished_at = ?, outcome = ?, detail = ?, connect_ms = ?, round_trip_ms = ? "
                    + "WHERE id = ?", ps -> {
                        ps.setLong(1, now);
                        ps.setString(2, result.outcome().name());
                        ps.setString(3, emptyToNull(result.detail()));
                        ps.setLong(4, result.connectTime().toMillis());
                        ps.setLong(5, result.roundTrip().toMillis());
                        ps.setLong(6, attemptId);
                    });
            if (result.rawResponse().isPresent()) {
                ParsedAck ack = result.ack().orElse(null);
                execute("INSERT INTO ack (message_id, attempt_id, ack_code, msa_control_id, msa_text, errors, raw, "
                        + "received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                            ps.setLong(1, messageId);
                            ps.setLong(2, attemptId);
                            ps.setString(3, ack == null ? null : ack.code().name());
                            ps.setString(4, ack == null ? null : ack.controlId());
                            ps.setString(5, ack == null ? null : emptyToNull(ack.text()));
                            ps.setString(6, ack == null ? null : emptyToNull(ack.errors().stream()
                                    .map(AckError::describe).collect(Collectors.joining("\n"))));
                            ps.setString(7, result.rawResponse().get());
                            ps.setLong(8, now);
                        });
            }
            boolean terminal = newStatus.isTerminal();
            // CASE rather than the two-argument MAX, which PostgreSQL does not have.
            execute("UPDATE message SET status = ?, next_attempt_at = ?, possible_duplicate = "
                    + "CASE WHEN ? = 1 THEN 1 ELSE possible_duplicate END, last_outcome = ?, last_error = ?, "
                    + "updated_at = ?, completed_at = ?, app_ack_mode = ?, app_ack_due_at = ? "
                    + "WHERE id = ?", ps -> {
                        ps.setString(1, newStatus.name());
                        ps.setLong(2, nextAttemptAt.toEpochMilli());
                        ps.setInt(3, possibleDuplicate ? 1 : 0);
                        ps.setString(4, result.outcome().name());
                        ps.setString(5, result.outcome().severity() == io.hl7sender.core.send.SendOutcome.Severity
                                .SUCCESS ? null : emptyToNull(result.detail()));
                        ps.setLong(6, now);
                        if (terminal) {
                            ps.setLong(7, now);
                        } else {
                            ps.setNull(7, Types.INTEGER);
                        }
                        ps.setString(8, wait == null ? null : wait.mode());
                        setNullableLong(ps, 9, wait == null ? null : wait.dueAt().toEpochMilli());
                        ps.setLong(10, messageId);
                    });
            long destinationId = query("SELECT destination_id FROM message WHERE id = ?",
                    ps -> ps.setLong(1, messageId), rs -> rs.getLong(1)).get(0);
            insertAudit(messageId, destinationId, MessageStatus.IN_FLIGHT, newStatus, ACTOR_ENGINE,
                    result.outcome().name() + (reason.isEmpty() ? "" : ": " + reason));
            return null;
        });
    }

    /**
     * Applies an application ACK received from a destination's receiver. It is matched by MSA-2 to the MSH-10 of
     * a message of that destination that is waiting for one, or whose wait already ended without one (so a late
     * ACK still corrects the result). AA completes the message; AE and AR move it to the dead-letter queue. The
     * ACK is kept in the message's history.
     *
     * @param source the receiver's address, for the audit trail
     * @return the updated message, or empty if no message matches (this is audited on the destination)
     */
    public synchronized Optional<QueuedMessage> applyApplicationAck(long destinationId, ParsedAck ack, String source) {
        long now = clock.millis();
        return tx(() -> {
            Optional<QueuedMessage> match = query("SELECT " + MESSAGE_COLUMNS + " FROM message "
                    + "WHERE destination_id = ? AND control_id = ? AND (status = ? "
                    + "OR (status = ? AND last_outcome = ?) OR (status = ? AND last_outcome = ?)) "
                    + "ORDER BY id DESC LIMIT 1", ps -> {
                        ps.setLong(1, destinationId);
                        ps.setString(2, ack.controlId());
                        ps.setString(3, MessageStatus.AWAITING_APP_ACK.name());
                        ps.setString(4, MessageStatus.DEAD_LETTER.name());
                        ps.setString(5, APP_ACK_TIMEOUT);
                        ps.setString(6, MessageStatus.ACKNOWLEDGED.name());
                        ps.setString(7, APP_ACK_NOT_SENT);
                    }, QueueStore::message).stream().findFirst();
            if (match.isEmpty()) {
                insertAudit(null, destinationId, null, null, ACTOR_ENGINE, "Application ACK " + ack.code()
                        + " from " + source + " for control ID '" + ack.controlId() + "' matches no waiting message");
                return Optional.empty();
            }
            QueuedMessage m = match.get();
            MessageStatus to = ack.isAccept() ? MessageStatus.ACKNOWLEDGED : MessageStatus.DEAD_LETTER;
            String outcome = SendOutcome.of(ack.code()).name();
            String errors = ack.errors().stream().map(AckError::describe).collect(Collectors.joining("\n"));
            String detail = ack.text().isEmpty() ? errors : ack.text();
            List<Long> attempt = query("SELECT MAX(id) FROM attempt WHERE message_id = ?",
                    ps -> ps.setLong(1, m.id()), rs -> rs.getLong(1));
            execute("INSERT INTO ack (message_id, attempt_id, ack_code, msa_control_id, msa_text, errors, raw, "
                    + "received_at, kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'application')", ps -> {
                        ps.setLong(1, m.id());
                        ps.setLong(2, attempt.get(0));
                        ps.setString(3, ack.code().name());
                        ps.setString(4, ack.controlId());
                        ps.setString(5, emptyToNull(ack.text()));
                        ps.setString(6, emptyToNull(errors));
                        ps.setString(7, ack.raw());
                        ps.setLong(8, now);
                    });
            execute("UPDATE message SET status = ?, last_outcome = ?, last_error = ?, updated_at = ?, "
                    + "completed_at = ?, app_ack_due_at = NULL WHERE id = ?", ps -> {
                        ps.setString(1, to.name());
                        ps.setString(2, outcome);
                        ps.setString(3, ack.isAccept() ? null : emptyToNull(detail));
                        ps.setLong(4, now);
                        ps.setLong(5, now);
                        ps.setLong(6, m.id());
                    });
            insertAudit(m.id(), destinationId, m.status(), to, ACTOR_ENGINE, "Application ACK " + ack.code() + " from "
                    + source + (detail.isEmpty() ? "" : ": " + detail));
            return message(m.id());
        });
    }

    /**
     * Ends the wait of messages whose application ACK is overdue. With MSH-16 ER the receiver only reports
     * errors, so silence means success and the message is ACKNOWLEDGED; with AL or SU it is dead-lettered as
     * {@link #APP_ACK_TIMEOUT}.
     *
     * @return the messages that changed
     */
    public synchronized List<QueuedMessage> expireApplicationAcks(long destinationId) {
        long now = clock.millis();
        return tx(() -> {
            record Due(long id, String mode) {
            }
            List<Due> due = query("SELECT id, app_ack_mode FROM message WHERE destination_id = ? AND status = ? "
                    + "AND app_ack_due_at <= ?", ps -> {
                        ps.setLong(1, destinationId);
                        ps.setString(2, MessageStatus.AWAITING_APP_ACK.name());
                        ps.setLong(3, now);
                    }, rs -> new Due(rs.getLong(1), rs.getString(2)));
            List<QueuedMessage> changed = new ArrayList<>();
            for (Due d : due) {
                boolean errorsOnly = "ER".equals(d.mode());
                MessageStatus to = errorsOnly ? MessageStatus.ACKNOWLEDGED : MessageStatus.DEAD_LETTER;
                String detail = errorsOnly
                        ? "No application ACK, and MSH-16 is ER (errors only), so the message is taken as accepted"
                        : "No application ACK received in time (MSH-16 " + d.mode() + ")";
                execute("UPDATE message SET status = ?, last_outcome = ?, last_error = ?, updated_at = ?, "
                        + "completed_at = ?, app_ack_due_at = NULL WHERE id = ?", ps -> {
                            ps.setString(1, to.name());
                            ps.setString(2, errorsOnly ? APP_ACK_NOT_SENT : APP_ACK_TIMEOUT);
                            ps.setString(3, errorsOnly ? null : detail);
                            ps.setLong(4, now);
                            ps.setLong(5, now);
                            ps.setLong(6, d.id());
                        });
                insertAudit(d.id(), destinationId, MessageStatus.AWAITING_APP_ACK, to, ACTOR_ENGINE, detail);
                message(d.id()).ifPresent(changed::add);
            }
            return changed;
        });
    }

    /** When the earliest application ACK wait of a destination ends, if any message is waiting. */
    public synchronized Optional<Instant> nextApplicationAckDue(long destinationId) {
        return query("SELECT MIN(app_ack_due_at) FROM message WHERE destination_id = ? AND status = ?", ps -> {
            ps.setLong(1, destinationId);
            ps.setString(2, MessageStatus.AWAITING_APP_ACK.name());
        }, rs -> {
            long v = rs.getLong(1);
            return rs.wasNull() ? null : Instant.ofEpochMilli(v);
        }).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * Crash recovery: messages left IN_FLIGHT (the process stopped between send and recording the
     * outcome) become RETRY_PENDING, due now and flagged as possible duplicates. Their unfinished
     * attempts are closed as {@code INTERRUPTED}.
     *
     * @param destinationId limit recovery to one destination, or {@code null} for all
     * @return number of messages recovered
     */
    public synchronized int recoverInFlight(Long destinationId) {
        long now = clock.millis();
        return tx(() -> {
            List<QueuedMessage> stuck = query("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE status = ?"
                    + (destinationId == null ? "" : " AND destination_id = ?"), ps -> {
                        ps.setString(1, MessageStatus.IN_FLIGHT.name());
                        if (destinationId != null) {
                            ps.setLong(2, destinationId);
                        }
                    }, QueueStore::message);
            for (QueuedMessage m : stuck) {
                execute("UPDATE attempt SET finished_at = ?, outcome = 'INTERRUPTED', detail = ? "
                        + "WHERE message_id = ? AND finished_at IS NULL", ps -> {
                            ps.setLong(1, now);
                            ps.setString(2, "Application stopped before the outcome was recorded");
                            ps.setLong(3, m.id());
                        });
                execute("UPDATE message SET status = ?, next_attempt_at = ?, possible_duplicate = 1, updated_at = ?, "
                        + "last_outcome = 'INTERRUPTED', last_error = ? WHERE id = ?", ps -> {
                            ps.setString(1, MessageStatus.RETRY_PENDING.name());
                            ps.setLong(2, now);
                            ps.setLong(3, now);
                            ps.setString(4, "Interrupted while in flight; the receiver may already have it");
                            ps.setLong(5, m.id());
                        });
                insertAudit(m.id(), m.destinationId(), MessageStatus.IN_FLIGHT, MessageStatus.RETRY_PENDING,
                        ACTOR_RECOVERY, "Recovered after interruption; flagged as possible duplicate");
            }
            if (!stuck.isEmpty()) {
                LOG.warn("Recovered {} message(s) that were in flight when the application stopped", stuck.size());
            }
            return stuck.size();
        });
    }

    /**
     * Puts a finished message (dead-lettered, or delivered for a deliberate resend) back at the end of
     * its queue with a fresh attempt budget.
     */
    public synchronized boolean requeue(long messageId, String actor) {
        long now = clock.millis();
        return transition(messageId, List.of(MessageStatus.DEAD_LETTER, MessageStatus.ACKNOWLEDGED,
                MessageStatus.SENT_UNCONFIRMED), MessageStatus.QUEUED, actor, "Re-queued",
                "UPDATE message SET status = ?, updated_at = ?, attempts = 0, "
                        + "queue_seq = (SELECT COALESCE(MAX(queue_seq), 0) + 1 FROM message), next_attempt_at = ?, "
                        + "completed_at = NULL WHERE id = ?", ps -> {
                            ps.setString(1, MessageStatus.QUEUED.name());
                            ps.setLong(2, now);
                            ps.setLong(3, now);
                            ps.setLong(4, messageId);
                        });
    }

    /**
     * Moves a waiting message to the dead-letter queue, for example to unblock the head of a FIFO queue, or to
     * stop waiting for an application ACK.
     */
    public synchronized boolean moveToDeadLetter(long messageId, String actor, String reason) {
        long now = clock.millis();
        return tx(() -> {
            Optional<QueuedMessage> m = message(messageId);
            if (m.isEmpty() || (m.get().status() != MessageStatus.QUEUED
                    && m.get().status() != MessageStatus.RETRY_PENDING
                    && m.get().status() != MessageStatus.AWAITING_APP_ACK)) {
                return false;
            }
            execute("UPDATE message SET status = ?, updated_at = ?, completed_at = ?, last_outcome = 'MOVED_BY_USER', "
                    + "last_error = ?, app_ack_due_at = NULL WHERE id = ?", ps -> {
                        ps.setString(1, MessageStatus.DEAD_LETTER.name());
                        ps.setLong(2, now);
                        ps.setLong(3, now);
                        ps.setString(4, reason);
                        ps.setLong(5, messageId);
                    });
            insertAudit(messageId, m.get().destinationId(), m.get().status(), MessageStatus.DEAD_LETTER, actor, reason);
            return true;
        });
    }

    /** Makes a RETRY_PENDING message due immediately. */
    public synchronized boolean retryNow(long messageId, String actor) {
        long now = clock.millis();
        return transition(messageId, List.of(MessageStatus.RETRY_PENDING), MessageStatus.RETRY_PENDING, actor,
                "Retry requested now", "UPDATE message SET updated_at = ?, next_attempt_at = ? WHERE id = ?", ps -> {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setLong(3, messageId);
                });
    }

    /** Replaces the content of a dead-lettered message, for example after fixing the error reported in an AE. */
    public synchronized boolean updatePayload(long messageId, String controlId, String messageType, String payload,
                                              String actor) {
        long now = clock.millis();
        return tx(() -> {
            Optional<QueuedMessage> m = message(messageId);
            if (m.isEmpty() || m.get().status() != MessageStatus.DEAD_LETTER) {
                return false;
            }
            execute("UPDATE message SET control_id = ?, message_type = ?, payload = ?, updated_at = ? WHERE id = ?",
                    ps -> {
                        ps.setString(1, controlId);
                        ps.setString(2, messageType);
                        ps.setString(3, payload);
                        ps.setLong(4, now);
                        ps.setLong(5, messageId);
                    });
            insertAudit(messageId, m.get().destinationId(), null, null, actor,
                    "Content edited (control ID " + m.get().controlId() + " -> " + controlId + ")");
            return true;
        });
    }

    /** Deletes a message that is not currently in flight. */
    public synchronized boolean delete(long messageId) {
        return update("DELETE FROM message WHERE id = ? AND status <> ?", ps -> {
            ps.setLong(1, messageId);
            ps.setString(2, MessageStatus.IN_FLIGHT.name());
        }) > 0;
    }

    /** Deletes delivered messages (and their history) that completed before {@code before}. */
    public synchronized int purgeDelivered(Instant before) {
        return update("DELETE FROM message WHERE status IN (?, ?) AND completed_at < ?", ps -> {
            ps.setString(1, MessageStatus.ACKNOWLEDGED.name());
            ps.setString(2, MessageStatus.SENT_UNCONFIRMED.name());
            ps.setLong(3, before.toEpochMilli());
        });
    }

    /** Messages matching {@code q}, newest first. */
    public synchronized List<QueuedMessage> search(MessageQuery q) {
        Where w = where(q);
        return query("SELECT " + MESSAGE_COLUMNS + " FROM message" + w.sql + " ORDER BY created_at DESC, id DESC "
                + "LIMIT ?", ps -> {
                    int i = w.bind(ps);
                    ps.setInt(i, q.limit());
                }, QueueStore::message);
    }

    /** Number of messages matching {@code q}, ignoring its limit. */
    public synchronized int count(MessageQuery q) {
        Where w = where(q);
        return query("SELECT COUNT(*) FROM message" + w.sql, w::bind, rs -> rs.getInt(1)).get(0);
    }

    /** SQL built only from constant fragments; every value is a bound parameter. */
    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> params = new ArrayList<>();

        void add(String condition, Object... values) {
            sql.append(sql.length() == 0 ? " WHERE " : " AND ").append(condition);
            params.addAll(List.of(values));
        }

        /** Binds all parameters and returns the next free index. */
        int bind(PreparedStatement ps) throws SQLException {
            int i = 1;
            for (Object p : params) {
                if (p instanceof Long l) {
                    ps.setLong(i++, l);
                } else {
                    ps.setString(i++, String.valueOf(p));
                }
            }
            return i;
        }
    }

    private static Where where(MessageQuery q) {
        Where w = new Where();
        q.destinationId().ifPresent(id -> w.add("destination_id = ?", id));
        if (!q.statuses().isEmpty()) {
            List<MessageStatus> list = List.copyOf(q.statuses());
            w.add("status IN (" + "?,".repeat(list.size() - 1) + "?)",
                    list.stream().map(MessageStatus::name).toArray());
        }
        if (!q.messageType().isEmpty()) {
            w.add("lower(message_type) LIKE lower(?) ESCAPE '\\'", like(q.messageType()));
        }
        if (!q.controlId().isEmpty()) {
            w.add("lower(control_id) LIKE lower(?) ESCAPE '\\'", like(q.controlId()));
        }
        if (!q.contains().isEmpty()) {
            w.add("lower(payload) LIKE lower(?) ESCAPE '\\'", like(q.contains()));
        }
        if (!q.batchId().isEmpty()) {
            w.add("batch_id = ?", q.batchId());
        }
        q.from().ifPresent(t -> w.add("created_at >= ?", t.toEpochMilli()));
        q.to().ifPresent(t -> w.add("created_at < ?", t.toEpochMilli()));
        return w;
    }

    private static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    // --- Schedules ------------------------------------------------------------------------------------

    private static final String SCHEDULE_COLUMNS = "id, name, cron, zone, destination_id, message, count, enabled, "
            + "last_run_at, last_result";

    public synchronized List<Schedule> schedules() {
        return query("SELECT " + SCHEDULE_COLUMNS + " FROM schedule ORDER BY lower(name), id", ps -> { },
                QueueStore::schedule);
    }

    public synchronized Optional<Schedule> schedule(long id) {
        return query("SELECT " + SCHEDULE_COLUMNS + " FROM schedule WHERE id = ?", ps -> ps.setLong(1, id),
                QueueStore::schedule).stream().findFirst();
    }

    /** Inserts ({@code id == 0}) or updates a schedule's definition; its run history is kept. */
    public synchronized Schedule saveSchedule(Schedule s) {
        return tx(() -> {
            long now = clock.millis();
            if (s.id() == 0) {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO schedule (name, cron, zone, "
                        + "destination_id, message, count, enabled, created_at, updated_at) VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    bindSchedule(ps, s);
                    ps.setLong(8, now);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                    long id = generatedKey(ps);
                    insertAudit(null, s.destinationId(), null, null, userActor, "Schedule '" + s.name()
                            + "' created (" + s.cron() + ")");
                    return s.withId(id);
                }
            }
            int n;
            try (PreparedStatement ps = connection.prepareStatement("UPDATE schedule SET name = ?, cron = ?, zone = ?, "
                    + "destination_id = ?, message = ?, count = ?, enabled = ?, updated_at = ? WHERE id = ?")) {
                bindSchedule(ps, s);
                ps.setLong(8, now);
                ps.setLong(9, s.id());
                n = ps.executeUpdate();
            }
            if (n == 0) {
                throw new QueueException("Schedule " + s.id() + " does not exist");
            }
            return schedule(s.id()).orElseThrow();
        });
    }

    public synchronized boolean deleteSchedule(long id) {
        return update("DELETE FROM schedule WHERE id = ?", ps -> ps.setLong(1, id)) > 0;
    }

    /** Records a run: when, and what it queued. */
    public synchronized void recordScheduleRun(long id, Instant at, String result) {
        update("UPDATE schedule SET last_run_at = ?, last_result = ? WHERE id = ?", ps -> {
            ps.setLong(1, at.toEpochMilli());
            ps.setString(2, result);
            ps.setLong(3, id);
        });
    }

    private static void bindSchedule(PreparedStatement ps, Schedule s) throws SQLException {
        ps.setString(1, s.name());
        ps.setString(2, s.cron());
        ps.setString(3, s.zone());
        ps.setLong(4, s.destinationId());
        ps.setString(5, s.message());
        ps.setInt(6, s.count());
        ps.setInt(7, s.enabled() ? 1 : 0);
    }

    private static Schedule schedule(ResultSet rs) throws SQLException {
        return new Schedule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8) != 0, optInstant(rs, 9), rs.getString(10));
    }

    // --- Users --------------------------------------------------------------------------------------

    private static final String USER_COLUMNS = "id, username, display_name, role, enabled, created_at, last_login_at";

    public synchronized List<User> users() {
        return query("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY username", ps -> { }, QueueStore::user);
    }

    public synchronized Optional<User> user(String username) {
        String name;
        try {
            name = User.normalize(username);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return query("SELECT " + USER_COLUMNS + " FROM app_user WHERE username = ?", ps -> ps.setString(1, name),
                QueueStore::user).stream().findFirst();
    }

    /** True once a user has been added: from then on everyone must sign in. */
    public synchronized boolean accessControlEnabled() {
        return !query("SELECT 1 FROM app_user LIMIT 1", ps -> { }, rs -> 1).isEmpty();
    }

    /**
     * Adds a user. The first user must be an administrator, so that someone can always manage users once access
     * control is on.
     */
    public synchronized User createUser(User u, String password) {
        PasswordHasher.check(password);
        String hash = PasswordHasher.hash(password);
        return tx(() -> {
            if (u.role() != Role.ADMIN && !accessControlEnabled()) {
                throw new QueueException("The first user must be an administrator");
            }
            long now = clock.millis();
            long id;
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO app_user (username, display_name, "
                    + "role, password_hash, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, u.username());
                ps.setString(2, u.displayName());
                ps.setString(3, u.role().name());
                ps.setString(4, hash);
                ps.setInt(5, u.enabled() ? 1 : 0);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                id = generatedKey(ps);
            }
            insertAudit(null, null, null, null, userActor, "User '" + u.username() + "' added ("
                    + u.role().name().toLowerCase(java.util.Locale.ROOT) + ")");
            return userById(id);
        });
    }

    /** Changes a user's display name, role or enabled state. The last enabled administrator cannot be demoted. */
    public synchronized User updateUser(User u) {
        return tx(() -> {
            User before = userById(u.id());
            boolean stillAdmin = u.enabled() && u.role() == Role.ADMIN;
            if (!stillAdmin && isLastAdmin(before)) {
                throw new QueueException("'" + before.username() + "' is the last administrator; make someone else "
                        + "an administrator first");
            }
            execute("UPDATE app_user SET display_name = ?, role = ?, enabled = ?, updated_at = ? WHERE id = ?", ps -> {
                ps.setString(1, u.displayName());
                ps.setString(2, u.role().name());
                ps.setInt(3, u.enabled() ? 1 : 0);
                ps.setLong(4, clock.millis());
                ps.setLong(5, u.id());
            });
            List<String> changes = new ArrayList<>();
            if (before.role() != u.role()) {
                changes.add("role " + u.role().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (before.enabled() != u.enabled()) {
                changes.add(u.enabled() ? "enabled" : "disabled");
            }
            if (!before.displayName().equals(u.displayName())) {
                changes.add("display name");
            }
            if (!changes.isEmpty()) {
                insertAudit(null, null, null, null, userActor, "User '" + before.username() + "' changed: "
                        + String.join(", ", changes));
            }
            return userById(u.id());
        });
    }

    public synchronized void setPassword(String username, String password) {
        PasswordHasher.check(password);
        String hash = PasswordHasher.hash(password);
        User u = user(username).orElseThrow(() -> new QueueException("No user '" + username
                + "'"));
        tx(() -> {
            execute("UPDATE app_user SET password_hash = ?, updated_at = ? WHERE id = ?", ps -> {
                ps.setString(1, hash);
                ps.setLong(2, clock.millis());
                ps.setLong(3, u.id());
            });
            insertAudit(null, null, null, null, userActor, "Password changed for user '" + u.username() + "'");
            return null;
        });
    }

    /** Removes a user. The last enabled administrator cannot be removed. */
    public synchronized void deleteUser(String username) {
        User u = user(username).orElseThrow(() -> new QueueException("No user '" + username
                + "'"));
        tx(() -> {
            if (isLastAdmin(u) && users().size() > 1) {
                throw new QueueException("'" + u.username() + "' is the last administrator; make someone else an "
                        + "administrator first");
            }
            execute("DELETE FROM app_user WHERE id = ?", ps -> ps.setLong(1, u.id()));
            insertAudit(null, null, null, null, userActor, "User '" + u.username() + "' removed");
            return null;
        });
    }

    /**
     * Checks a user name and password. Returns the user if they match and the user is enabled, and records the
     * sign-in; failed attempts are recorded too, without saying which part was wrong.
     */
    public synchronized Optional<User> authenticate(String username, String password) {
        Optional<User> u = user(username);
        String stored = u.isEmpty() ? null : query("SELECT password_hash FROM app_user WHERE id = ?",
                ps -> ps.setLong(1, u.get().id()), rs -> rs.getString(1)).stream().findFirst().orElse(null);
        boolean ok;
        if (stored == null) {
            // Hash anyway, so the time taken does not reveal which user names exist.
            PasswordHasher.verify(password, DummyHash.VALUE);
            ok = false;
        } else {
            ok = PasswordHasher.verify(password, stored);
        }
        String name = u.map(User::username).orElse(String.valueOf(username).trim());
        if (!ok || !u.get().enabled()) {
            tx(() -> {
                insertAudit(null, null, null, null, abbreviate(name), "Sign-in failed");
                return null;
            });
            return Optional.empty();
        }
        tx(() -> {
            execute("UPDATE app_user SET last_login_at = ? WHERE id = ?", ps -> {
                ps.setLong(1, clock.millis());
                ps.setLong(2, u.get().id());
            });
            insertAudit(null, null, null, null, name, "Signed in");
            return null;
        });
        return Optional.of(userById(u.get().id()));
    }

    /** Recent user and sign-in events (audit entries that belong to no destination or message), newest first. */
    public synchronized List<AuditEvent> userAudit(int limit) {
        return query("SELECT id, message_id, destination_id, from_status, to_status, actor, detail, at "
                + "FROM audit_event WHERE destination_id IS NULL AND message_id IS NULL ORDER BY id DESC LIMIT ?",
                ps -> ps.setInt(1, limit), QueueStore::auditEvent);
    }

    /** A hash to check unknown user names against; made on first use, as hashing is deliberately slow. */
    private static final class DummyHash {
        static final String VALUE = PasswordHasher.hash("not-a-real-password");
    }

    private static String abbreviate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64);
    }

    private User userById(long id) {
        return query("SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?", ps -> ps.setLong(1, id),
                QueueStore::user).stream().findFirst().orElseThrow(() -> new QueueException("User " + id
                + " does not exist"));
    }

    private boolean isLastAdmin(User u) {
        if (!u.enabled() || u.role() != Role.ADMIN) {
            return false;
        }
        return users().stream().filter(x -> x.enabled() && x.role() == Role.ADMIN).count()
                == 1;
    }

    private static User user(ResultSet rs) throws SQLException {
        return new User(rs.getLong(1), rs.getString(2), rs.getString(3),
                Role.valueOf(rs.getString(4)), rs.getInt(5) != 0,
                Instant.ofEpochMilli(rs.getLong(6)), optInstant(rs, 7));
    }

    /** A message's attempts in order, each followed by the application ACK received for it, if any. */
    public synchronized List<AttemptRecord> attempts(long messageId) {
        List<AttemptRecord> out = new ArrayList<>(query("SELECT a.id, a.message_id, a.attempt_no, a.started_at, "
                + "a.finished_at, a.outcome, a.detail, a.round_trip_ms, k.ack_code, k.raw FROM attempt a "
                + "LEFT JOIN ack k ON k.attempt_id = a.id AND k.kind = 'response' "
                + "WHERE a.message_id = ? ORDER BY a.attempt_no", ps -> ps.setLong(1, messageId),
                rs -> new AttemptRecord(rs.getLong(1), rs.getLong(2), rs.getInt(3), Instant.ofEpochMilli(rs.getLong(4)),
                        optInstant(rs, 5), Optional.ofNullable(rs.getString(6)), Optional.ofNullable(rs.getString(7)),
                        optLong(rs, 8), Optional.ofNullable(rs.getString(9)), Optional.ofNullable(rs.getString(10)))));
        List<AttemptRecord> applicationAcks = query("SELECT k.id, k.message_id, a.attempt_no, k.received_at, "
                + "k.ack_code, k.msa_text, k.errors, k.raw FROM ack k JOIN attempt a ON a.id = k.attempt_id "
                + "WHERE k.message_id = ? AND k.kind = 'application' ORDER BY k.id", ps -> ps.setLong(1, messageId),
                rs -> {
                    String code = rs.getString(5);
                    Instant at = Instant.ofEpochMilli(rs.getLong(4));
                    String text = rs.getString(6);
                    String errors = rs.getString(7);
                    String detail = text != null ? text : errors != null ? errors : "";
                    String outcome = io.hl7sender.core.ack.AckCode.parse(code).map(c -> SendOutcome.of(c).name())
                            .orElse("APPLICATION_ACK");
                    return new AttemptRecord(rs.getLong(1), rs.getLong(2), rs.getInt(3), at, Optional.of(at),
                            Optional.of(outcome), Optional.of("Application ACK" + (detail.isEmpty() ? "" : ": "
                            + detail)), Optional.empty(), Optional.ofNullable(code),
                            Optional.ofNullable(rs.getString(8)), true);
                });
        for (AttemptRecord ack : applicationAcks) {
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).attemptNo() > ack.attemptNo()) {
                    at = i;
                    break;
                }
            }
            out.add(at, ack);
        }
        return out;
    }

    public synchronized List<AuditEvent> audit(long messageId) {
        return query("SELECT id, message_id, destination_id, from_status, to_status, actor, detail, at "
                + "FROM audit_event WHERE message_id = ? ORDER BY id", ps -> ps.setLong(1, messageId),
                QueueStore::auditEvent);
    }

    public synchronized List<AuditEvent> destinationAudit(long destinationId, int limit) {
        return query("SELECT id, message_id, destination_id, from_status, to_status, actor, detail, at "
                + "FROM audit_event WHERE destination_id = ? AND message_id IS NULL ORDER BY id DESC LIMIT ?", ps -> {
                    ps.setLong(1, destinationId);
                    ps.setInt(2, limit);
                }, QueueStore::auditEvent);
    }

    // ---------------------------------------------------------------------------------------------
    // Internals

    /** Runs {@code update} only if the message is currently in one of {@code from}, and audits the change. */
    private boolean transition(long messageId, List<MessageStatus> from, MessageStatus to, String actor,
                               String detail, String update, Binder binder) {
        return tx(() -> {
            Optional<QueuedMessage> m = message(messageId);
            if (m.isEmpty() || !from.contains(m.get().status())) {
                return false;
            }
            execute(update, binder);
            insertAudit(messageId, m.get().destinationId(), m.get().status(), to, actor, detail);
            return true;
        });
    }

    private void insertAudit(Long messageId, Long destinationId, MessageStatus from, MessageStatus to, String actor,
                             String detail) throws SQLException {
        execute("INSERT INTO audit_event (message_id, destination_id, from_status, to_status, actor, detail, at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", ps -> {
                    setNullableLong(ps, 1, messageId);
                    setNullableLong(ps, 2, destinationId);
                    ps.setString(3, from == null ? null : from.name());
                    ps.setString(4, to == null ? null : to.name());
                    ps.setString(5, actor);
                    ps.setString(6, detail);
                    ps.setLong(7, clock.millis());
                });
    }

    private static void bindDestination(PreparedStatement ps, DestinationConfig d) throws SQLException {
        ps.setString(1, d.name());
        ps.setString(2, d.host());
        ps.setInt(3, d.port());
        ps.setInt(4, d.connectTimeoutMs());
        ps.setInt(5, d.ackTimeoutMs());
        ps.setString(6, d.charset());
        ps.setString(7, d.ackMode().name());
        ps.setString(8, d.connectionMode().name());
        ps.setInt(9, d.retry().maxAttempts());
        ps.setLong(10, d.retry().baseDelayMs());
        ps.setLong(11, d.retry().maxDelayMs());
        ps.setDouble(12, d.retry().jitter());
        ps.setInt(13, d.circuitBreaker().failureThreshold());
        ps.setLong(14, d.circuitBreaker().coolDownMs());
        ps.setString(15, d.ackPolicy().encode());
        ps.setInt(16, d.paused() ? 1 : 0);
        ps.setInt(17, d.maxPerSecond());
        ps.setString(18, d.validationLevel().name());
        ps.setString(19, d.profilePath());
        ps.setString(20, d.watchFolder());
        ps.setInt(21, d.tls().enabled() ? 1 : 0);
        ps.setString(22, d.tls().trustStorePath());
        ps.setString(23, d.tls().keyStorePath());
        ps.setInt(24, d.tls().verifyHostname() ? 1 : 0);
        ps.setString(25, d.tls().protocols());
        ps.setString(26, d.secretRef());
        ps.setString(27, d.notes());
        ps.setString(28, d.script());
        ps.setString(29, d.transport());
        ps.setString(30, TransportOptionsJson.write(d.transportOptions()));
        ps.setInt(31, d.appAckPort());
        ps.setInt(32, d.appAckTimeoutMs());
    }

    private static DestinationConfig destination(ResultSet rs) throws SQLException {
        return new DestinationConfig(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("host"),
                rs.getInt("port"),
                rs.getInt("connect_timeout_ms"),
                rs.getInt("ack_timeout_ms"),
                rs.getString("charset"),
                AckMode.valueOf(rs.getString("ack_mode")),
                ConnectionMode.valueOf(rs.getString("connection_mode")),
                new RetryPolicy(rs.getInt("retry_max_attempts"), rs.getLong("retry_base_ms"),
                        rs.getLong("retry_max_ms"), rs.getDouble("retry_jitter")),
                new CircuitBreakerSettings(rs.getInt("cb_failure_threshold"), rs.getLong("cb_cool_down_ms")),
                AckPolicy.decode(rs.getString("ack_policy")),
                rs.getInt("paused") != 0,
                rs.getInt("max_per_second"),
                validationLevel(rs.getString("validation_level")),
                rs.getString("profile_path"),
                rs.getString("watch_folder"),
                new TlsSettings(rs.getInt("tls_enabled") != 0, rs.getString("tls_trust_path"),
                        rs.getString("tls_key_path"), rs.getInt("tls_verify_hostname") != 0,
                        rs.getString("tls_protocols")),
                rs.getString("secret_ref"),
                rs.getString("notes"),
                rs.getString("script"),
                rs.getString("transport"),
                TransportOptionsJson.read(rs.getString("transport_options")),
                rs.getInt("app_ack_port"),
                rs.getInt("app_ack_timeout_ms"));
    }

    private static ValidationLevel validationLevel(String name) {
        if (name == null) {
            return ValidationLevel.STANDARD;
        }
        try {
            return ValidationLevel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ValidationLevel.STANDARD;
        }
    }

    private static QueuedMessage message(ResultSet rs) throws SQLException {
        return new QueuedMessage(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                MessageStatus.valueOf(rs.getString(7)),
                rs.getInt(8),
                Instant.ofEpochMilli(rs.getLong(9)),
                rs.getInt(10) != 0,
                Optional.ofNullable(rs.getString(11)),
                Optional.ofNullable(rs.getString(12)),
                Instant.ofEpochMilli(rs.getLong(13)),
                Instant.ofEpochMilli(rs.getLong(14)),
                optInstant(rs, 15),
                Optional.ofNullable(rs.getString(16)),
                Optional.ofNullable(rs.getString(17)));
    }

    private static AuditEvent auditEvent(ResultSet rs) throws SQLException {
        return new AuditEvent(rs.getLong(1), optLong(rs, 2), optLong(rs, 3), Optional.ofNullable(rs.getString(4)),
                Optional.ofNullable(rs.getString(5)), rs.getString(6), Optional.ofNullable(rs.getString(7)),
                Instant.ofEpochMilli(rs.getLong(8)));
    }

    private static Optional<Instant> optInstant(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(v));
    }

    private static Optional<Long> optLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? Optional.empty() : Optional.of(v);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated key returned");
            }
            return keys.getLong(1);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new QueueException("Queue query failed: " + e.getMessage(), e);
        }
    }

    private void execute(String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        }
    }

    private int update(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new QueueException("Queue update failed: " + e.getMessage(), e);
        }
    }

    /** Runs {@code work} in a transaction. Nested calls join the outer transaction. */
    private <T> T tx(SqlWork<T> work) {
        try {
            if (!connection.getAutoCommit()) {
                return work.run();
            }
            connection.setAutoCommit(false);
            try {
                T result = work.run();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage());
            boolean unique = m.contains("UNIQUE constraint failed") || "23505".equals(e.getSQLState());
            if (unique && (m.contains("destination.name") || m.contains("destination_name_key"))) {
                throw new QueueException("A destination with that name already exists", e);
            }
            if (unique && (m.contains("schedule.name") || m.contains("schedule_name_key"))) {
                throw new QueueException("A schedule with that name already exists", e);
            }
            if (unique && (m.contains("app_user.username") || m.contains("app_user_username_key"))) {
                throw new QueueException("A user with that name already exists", e);
            }
            throw new QueueException("Queue update failed: " + e.getMessage(), e);
        }
    }
}
