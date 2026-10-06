package io.hl7sender.core.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.ack.AckParser;
import io.hl7sender.core.auth.Role;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueStoreTest {

    @TempDir
    Path dir;

    QueueStore store;
    DestinationConfig dest;

    /** The store under test; {@link PostgresQueueStoreTest} runs the same tests on PostgreSQL. */
    QueueStore openStore() {
        return QueueStore.open(dir.resolve("queue.db"));
    }

    @BeforeEach
    void open() {
        store = openStore();
        dest = store.saveDestination(DestinationConfig.of("Mirth", "localhost", 6661));
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static SendResult result(SendOutcome outcome, String rawAck) throws Exception {
        return new SendResult(outcome, "h:1", "C1", "ADT^A01", "MSH|...", new ValidationReport(Optional.empty(), 0,
                List.of()), rawAck == null ? Optional.empty() : Optional.of(AckParser.parse(rawAck)),
                Optional.ofNullable(rawAck), outcome == SendOutcome.ACCEPTED ? "" : "failure detail", Instant.now(),
                Duration.ofMillis(3), Duration.ofMillis(12));
    }

    @Test
    void migratesOnceAndReopens() throws Exception {
        store.close();
        store = QueueStore.open(dir.resolve("queue.db"));
        assertThat(store.destinations()).extracting(DestinationConfig::name).containsExactly("Mirth");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("queue.db"));
             Statement st = c.createStatement()) {
            assertThat(st.executeQuery("PRAGMA user_version").getInt(1)).isEqualTo(Migrations.latestVersion());
        }
    }

    @Test
    void refusesNewerSchema() throws Exception {
        store.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("queue.db"));
             Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 99");
        }
        assertThatThrownBy(() -> QueueStore.open(dir.resolve("queue.db")))
                .isInstanceOf(QueueException.class).hasMessageContaining("newer");
        // The failed open must not leak its connection (an open handle locks the file on Windows).
        java.nio.file.Files.delete(dir.resolve("queue.db"));
        store = QueueStore.open(dir.resolve("other.db"));
    }

    @Test
    void migrationScriptSplitting() {
        assertThat(Migrations.statements("-- c\nCREATE TABLE a (x INT);\n\nCREATE INDEX i ON a (x);\n"))
                .containsExactly("CREATE TABLE a (x INT)", "CREATE INDEX i ON a (x)");
    }

    @Test
    void destinationsRoundTripAndNamesAreUnique() {
        DestinationConfig edited = dest.withTimeouts(1_000, 9_000)
                .withRetry(new RetryPolicy(3, 100, 1_000, 0.1))
                .withCircuitBreaker(new CircuitBreakerSettings(2, 500))
                .withAckPolicy(AckPolicy.DEFAULT.with(SendOutcome.APPLICATION_REJECT, AckPolicy.Action.DEAD_LETTER))
                .withConnectionMode(ConnectionMode.PER_MESSAGE);
        store.saveDestination(edited);
        assertThat(store.destination(dest.id())).contains(edited);
        assertThatThrownBy(() -> store.saveDestination(DestinationConfig.of("Mirth", "other", 1)))
                .isInstanceOf(QueueException.class).hasMessageContaining("already exists");
    }

    @Test
    void headIsOldestPendingMessage() {
        QueuedMessage a = store.enqueue(dest.id(), "A", "ADT^A01", "MSH|a");
        QueuedMessage b = store.enqueue(dest.id(), "B", "ADT^A08", "MSH|b");
        assertThat(store.head(dest.id())).get().extracting(QueuedMessage::id).isEqualTo(a.id());
        assertThat(a.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(b.queueSeq()).isGreaterThan(a.queueSeq());
        assertThat(store.controlIdExists(dest.id(), "A")).isTrue();
        assertThat(store.controlIdExists(dest.id(), "Z")).isFalse();
        assertThat(store.counts(dest.id())).containsEntry(MessageStatus.QUEUED, 2)
                .containsEntry(MessageStatus.DEAD_LETTER, 0);
    }

    @Test
    void attemptLifecycleIsRecorded() throws Exception {
        QueuedMessage m = store.enqueue(dest.id(), "C1", "ADT^A01", "MSH|x");
        long attempt = store.beginAttempt(m.id());
        assertThat(store.message(m.id())).get().satisfies(q -> {
            assertThat(q.status()).isEqualTo(MessageStatus.IN_FLIGHT);
            assertThat(q.attempts()).isEqualTo(1);
        });
        assertThat(store.head(dest.id())).isEmpty();
        assertThatThrownBy(() -> store.beginAttempt(m.id())).isInstanceOf(QueueException.class);

        String ack = "MSH|^~\\&|R|F|S|F|20260101||ACK|X|P|2.5\rMSA|AA|C1\r";
        store.completeAttempt(m.id(), attempt, result(SendOutcome.ACCEPTED, ack), MessageStatus.ACKNOWLEDGED,
                Instant.now(), false, "");
        QueuedMessage done = store.message(m.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
        assertThat(done.completedAt()).isPresent();
        assertThat(done.lastOutcome()).contains("ACCEPTED");
        assertThat(done.lastError()).isEmpty();
        assertThat(store.attempts(m.id())).singleElement().satisfies(a -> {
            assertThat(a.attemptNo()).isEqualTo(1);
            assertThat(a.outcome()).contains("ACCEPTED");
            assertThat(a.ackCode()).contains("AA");
            assertThat(a.rawAck()).contains(ack);
            assertThat(a.roundTripMs()).contains(12L);
        });
        assertThat(store.audit(m.id())).extracting(e -> e.toStatus().orElse(""))
                .containsExactly("QUEUED", "IN_FLIGHT", "ACKNOWLEDGED");
    }

    @Test
    void retryPendingKeepsPossibleDuplicateFlag() throws Exception {
        QueuedMessage m = store.enqueue(dest.id(), "C1", "ADT^A01", "MSH|x");
        long a1 = store.beginAttempt(m.id());
        Instant later = Instant.now().plusSeconds(60);
        store.completeAttempt(m.id(), a1, result(SendOutcome.ACK_TIMEOUT, null), MessageStatus.RETRY_PENDING, later,
                true, "retry in 60 s");
        long a2 = store.beginAttempt(m.id());
        store.completeAttempt(m.id(), a2, result(SendOutcome.CONNECTION_FAILED, null), MessageStatus.RETRY_PENDING,
                later, false, "retry");
        QueuedMessage q = store.message(m.id()).orElseThrow();
        assertThat(q.possibleDuplicate()).isTrue();
        assertThat(q.attempts()).isEqualTo(2);
        assertThat(q.nextAttemptAt().toEpochMilli()).isEqualTo(later.toEpochMilli());
        assertThat(q.lastError()).contains("failure detail");
        assertThat(store.retryNow(m.id(), QueueStore.ACTOR_USER)).isTrue();
        assertThat(store.message(m.id()).orElseThrow().nextAttemptAt()).isBefore(later);
    }

    @Test
    void recoversMessagesLeftInFlight() {
        QueuedMessage m = store.enqueue(dest.id(), "C1", "ADT^A01", "MSH|x");
        store.beginAttempt(m.id());
        assertThat(store.recoverInFlight(null)).isEqualTo(1);
        QueuedMessage q = store.message(m.id()).orElseThrow();
        assertThat(q.status()).isEqualTo(MessageStatus.RETRY_PENDING);
        assertThat(q.possibleDuplicate()).isTrue();
        assertThat(store.attempts(m.id())).singleElement()
                .satisfies(a -> assertThat(a.outcome()).contains("INTERRUPTED"));
        assertThat(store.audit(m.id())).last().satisfies(e -> assertThat(e.actor()).isEqualTo("recovery"));
        assertThat(store.recoverInFlight(null)).isZero();
    }

    @Test
    void deadLetterRequeueEditAndDelete() {
        QueuedMessage a = store.enqueue(dest.id(), "A", "ADT^A01", "MSH|a");
        QueuedMessage b = store.enqueue(dest.id(), "B", "ADT^A01", "MSH|b");
        assertThat(store.moveToDeadLetter(a.id(), "user", "Unblock queue")).isTrue();
        assertThat(store.message(a.id()).orElseThrow().lastError()).contains("Unblock queue");
        assertThat(store.head(dest.id())).get().extracting(QueuedMessage::id).isEqualTo(b.id());
        assertThat(store.moveToDeadLetter(a.id(), "user", "again")).isFalse();

        assertThat(store.updatePayload(b.id(), "X", "ADT^A01", "MSH|x", "user")).isFalse();
        assertThat(store.updatePayload(a.id(), "A2", "ADT^A08", "MSH|fixed", "user")).isTrue();

        assertThat(store.requeue(a.id(), "user")).isTrue();
        QueuedMessage requeued = store.message(a.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(requeued.controlId()).isEqualTo("A2");
        assertThat(requeued.payload()).isEqualTo("MSH|fixed");
        assertThat(requeued.queueSeq()).isGreaterThan(b.queueSeq());
        assertThat(requeued.completedAt()).isEmpty();
        assertThat(store.requeue(a.id(), "user")).isFalse();

        store.beginAttempt(b.id());
        assertThat(store.delete(b.id())).isFalse();
        assertThat(store.delete(a.id())).isTrue();
        assertThat(store.message(a.id())).isEmpty();
    }

    @Test
    void listsByStatusAndPurgesDelivered() throws Exception {
        QueuedMessage a = store.enqueue(dest.id(), "A", "ADT^A01", "MSH|a");
        store.enqueue(dest.id(), "B", "ADT^A01", "MSH|b");
        long attempt = store.beginAttempt(a.id());
        store.completeAttempt(a.id(), attempt, result(SendOutcome.ACCEPTED, null), MessageStatus.ACKNOWLEDGED,
                Instant.now(), false, "");
        assertThat(store.messages(dest.id(), EnumSet.of(MessageStatus.QUEUED), 10, false))
                .extracting(QueuedMessage::controlId).containsExactly("B");
        assertThat(store.messages(dest.id(), EnumSet.of(MessageStatus.ACKNOWLEDGED), 10, true))
                .extracting(QueuedMessage::controlId).containsExactly("A");
        assertThat(store.purgeDelivered(Instant.now().minusSeconds(60))).isZero();
        assertThat(store.purgeDelivered(Instant.now().plusSeconds(1))).isEqualTo(1);
        assertThat(store.message(a.id())).isEmpty();
    }

    @Test
    void deletingDestinationRemovesItsMessages() {
        QueuedMessage m = store.enqueue(dest.id(), "A", "ADT^A01", "MSH|a");
        store.deleteDestination(dest.id());
        assertThat(store.destinations()).isEmpty();
        assertThat(store.message(m.id())).isEmpty();
    }

    @Test
    void pauseIsPersistedAndAudited() {
        store.setPaused(dest.id(), true);
        assertThat(store.destination(dest.id()).orElseThrow().paused()).isTrue();
        assertThat(store.destinationAudit(dest.id(), 10)).extracting(e -> e.detail().orElse(""))
                .contains("Delivery paused");
    }

    @Test
    void exportsMessagesWithHistoryAsJson() throws Exception {
        QueuedMessage m = store.enqueue(dest.id(), "C1", "ADT^A01", "MSH|^~\\&|A\rPID|1\r");
        long attempt = store.beginAttempt(m.id());
        store.completeAttempt(m.id(), attempt, result(SendOutcome.APPLICATION_ERROR,
                "MSH|^~\\&|R\rMSA|AE|C1|Bad PID\r"), MessageStatus.DEAD_LETTER, Instant.now(), false, "");
        Path out = dir.resolve("export/dlq.json");
        int n = QueueExport.writeJson(store, dest, store.messages(dest.id(), EnumSet.of(MessageStatus.DEAD_LETTER),
                100, true), out);
        assertThat(n).isEqualTo(1);
        String json = java.nio.file.Files.readString(out);
        assertThat(json).contains("\"controlId\" : \"C1\"").contains("\"ackCode\" : \"AE\"")
                .contains("\"PID|1\"").contains("\"name\" : \"Mirth\"");
    }

    @Test
    void migratesAVersion1DatabaseInPlace() throws Exception {
        store.close();
        Path v1 = dir.resolve("v1.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + v1); Statement st = c.createStatement()) {
            String script = new String(getClass().getResourceAsStream("db/V1__queue.sql").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String sql : Migrations.statements(script)) {
                st.execute(sql);
            }
            st.execute("PRAGMA user_version = 1");
            st.execute("INSERT INTO destination (name, host, port, connect_timeout_ms, ack_timeout_ms, charset, "
                    + "ack_mode, connection_mode, retry_max_attempts, retry_base_ms, retry_max_ms, retry_jitter, "
                    + "cb_failure_threshold, cb_cool_down_ms, ack_policy, paused, created_at, updated_at) VALUES "
                    + "('Old', 'h', 1, 1, 1, 'UTF-8', 'EXPECT_ACK', 'PERSISTENT', 3, 1, 2, 0, 0, 0, '', 0, 0, 0)");
            st.execute("INSERT INTO message (destination_id, queue_seq, control_id, message_type, payload, status, "
                    + "next_attempt_at, created_at, updated_at) "
                    + "VALUES (1, 1, 'C', 'ADT^A01', 'MSH|x', 'QUEUED', 0, 0, 0)");
        }
        store = QueueStore.open(v1);
        DestinationConfig old = store.destination(1).orElseThrow();
        assertThat(old.maxPerSecond()).isZero();
        assertThat(old.validationLevel()).isEqualTo(io.hl7sender.core.hl7.validation.ValidationLevel.STANDARD);
        assertThat(old.watchFolder()).isEmpty();
        QueuedMessage m = store.message(1).orElseThrow();
        assertThat(m.source()).isEmpty();
        assertThat(m.batchId()).isEmpty();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + v1); Statement st = c.createStatement()) {
            assertThat(st.executeQuery("PRAGMA user_version").getInt(1)).isEqualTo(Migrations.latestVersion());
        }
    }

    @Test
    void newDestinationSettingsRoundTrip() {
        DestinationConfig d = dest.withMaxPerSecond(25)
                .withValidation(io.hl7sender.core.hl7.validation.ValidationLevel.STRICT, "/profiles/p.xml")
                .withWatchFolder("/data/inbox");
        store.saveDestination(d);
        assertThat(store.destination(dest.id())).contains(d);
    }

    @Test
    void searchFiltersByEveryCriterion() {
        DestinationConfig other = store.saveDestination(DestinationConfig.of("Other", "h", 2));
        store.enqueue(dest.id(), "CTRL-A01", "ADT^A01", "MSH|a\rPID|1||DOE^JANE", "a.hl7#1", "B1");
        store.enqueue(dest.id(), "CTRL-A08", "ADT^A08", "MSH|b\rPID|1||ROE^RICHARD", "a.hl7#2", "B1");
        store.enqueue(other.id(), "CTRL_R01", "ORU^R01", "MSH|c\rPID|1||DOE^JOHN", "editor", null);
        QueuedMessage done = store.enqueue(dest.id(), "X100%", "ORU^R01", "MSH|d", null, null);
        store.moveToDeadLetter(done.id(), "user", "test");

        assertThat(store.search(MessageQuery.all())).hasSize(4);
        assertThat(store.count(MessageQuery.all().withDestination(dest.id()))).isEqualTo(3);
        assertThat(store.search(MessageQuery.all().withText("a0", "", ""))).extracting(QueuedMessage::controlId)
                .containsExactlyInAnyOrder("CTRL-A01", "CTRL-A08");
        assertThat(store.search(MessageQuery.all().withText("oru", "", ""))).hasSize(2);
        assertThat(store.search(MessageQuery.all().withText("", "", "doe^"))).hasSize(2);
        assertThat(store.search(MessageQuery.all().withBatch("B1"))).hasSize(2)
                .allSatisfy(m -> assertThat(m.source()).get().asString().startsWith("a.hl7#"));
        assertThat(store.search(MessageQuery.all().withStatuses(java.util.Set.of(MessageStatus.DEAD_LETTER))))
                .extracting(QueuedMessage::controlId).containsExactly("X100%");
        // LIKE wildcards in user input are literal.
        assertThat(store.search(MessageQuery.all().withText("", "_R", ""))).extracting(QueuedMessage::controlId)
                .containsExactly("CTRL_R01");
        assertThat(store.search(MessageQuery.all().withText("", "100%", ""))).hasSize(1);
        assertThat(store.search(MessageQuery.all().withRange(Instant.now().plusSeconds(60), null))).isEmpty();
        assertThat(store.search(MessageQuery.all().withLimit(1))).hasSize(1);
    }

    @Test
    void bulkInsertIsOneTransactionAndTagsTheBatch() {
        List<Long> ids = store.enqueueAll(dest.id(), List.of(
                new QueueStore.NewMessage("A", "ADT^A01", "MSH|a", "f#1"),
                new QueueStore.NewMessage("B", "ADT^A01", "MSH|b", "f#2")), "BATCH-1");
        assertThat(ids).hasSize(2);
        assertThat(store.head(dest.id())).get().extracting(QueuedMessage::controlId).isEqualTo("A");
        assertThat(store.search(MessageQuery.all().withBatch("BATCH-1"))).hasSize(2);
    }

    @Test
    void csvExportHasMetadataOnlyAndNeutralisesFormulas() throws Exception {
        QueuedMessage m = store.enqueue(dest.id(), "=HYPERLINK(\"x\")", "ADT^A01", "MSH|SECRET-PHI", "a,b", null);
        Path out = dir.resolve("history.csv");
        QueueExport.writeCsv(List.of(m), java.util.Map.of(dest.id(), "Mirth"), out);
        String csv = java.nio.file.Files.readString(out);
        assertThat(csv).startsWith("id,destination,enqueued,message_type,control_id,status");
        assertThat(csv).doesNotContain("SECRET-PHI");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"").contains(",\"a,b\",").contains(",Mirth,");
    }

    @Test
    void usersSignInAndTheLastAdministratorIsKept() {
        assertThat(store.accessControlEnabled()).isFalse();
        assertThatThrownBy(() -> store.createUser(User.of("op", "", Role.OPERATOR),
                "password1")).hasMessageContaining("first user must be an administrator");
        User admin = store.createUser(User.of("Admin", "The Admin", Role.ADMIN), "password1");
        assertThat(admin.id()).isPositive();
        assertThat(admin.username()).isEqualTo("admin");
        assertThat(store.accessControlEnabled()).isTrue();
        assertThatThrownBy(() -> store.createUser(User.of("admin", "", Role.VIEWER), "password2"))
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> store.createUser(User.of("x", "", Role.VIEWER), "short"))
                .hasMessageContaining("at least 8");
        store.setUserActor("admin");
        User viewer = store.createUser(User.of("vic", "", Role.VIEWER), "password2");

        assertThat(store.authenticate("ADMIN", "password1")).get().satisfies(u -> {
            assertThat(u.lastLoginAt()).isPresent();
            assertThat(u.displayName()).isEqualTo("The Admin");
        });
        assertThat(store.authenticate("admin", "wrong-password")).isEmpty();
        assertThat(store.authenticate("nobody", "password1")).isEmpty();
        assertThat(store.authenticate("bad name!", "password1")).isEmpty();

        assertThatThrownBy(() -> store.updateUser(admin.withRole(Role.OPERATOR)))
                .hasMessageContaining("last administrator");
        assertThatThrownBy(() -> store.updateUser(admin.withEnabled(false))).hasMessageContaining("last administrator");
        assertThatThrownBy(() -> store.deleteUser("admin")).hasMessageContaining("last administrator");
        store.updateUser(viewer.withRole(Role.ADMIN));
        store.updateUser(admin.withEnabled(false));
        assertThat(store.authenticate("admin", "password1")).isEmpty();
        store.setPassword("vic", "password3");
        assertThat(store.authenticate("vic", "password2")).isEmpty();
        assertThat(store.authenticate("vic", "password3")).get().extracting(User::role).isEqualTo(Role.ADMIN);
        store.deleteUser("admin");
        assertThat(store.users()).extracting(User::username).containsExactly("vic");

        assertThat(store.userAudit(50)).extracting(AuditEvent::detail).map(d -> d.orElse(""))
                .contains("User 'admin' added (admin)", "User 'vic' added (viewer)", "Sign-in failed", "Signed in",
                        "User 'vic' changed: role admin", "User 'admin' changed: disabled",
                        "Password changed for user 'vic'", "User 'admin' removed");
        assertThat(store.userAudit(50)).filteredOn(e -> e.detail().orElse("").equals("User 'vic' added (viewer)"))
                .singleElement().extracting(AuditEvent::actor).isEqualTo("admin");
        // Removing the only user turns access control off again.
        store.deleteUser("vic");
        assertThat(store.accessControlEnabled()).isFalse();
    }
}
