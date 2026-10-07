package io.hl7sender.core.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The delivery engine end-to-end against the built-in {@link TestListener}. */
@Timeout(60)
class DeliveryEngineTest {

    static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|ORIG|P|2.5.1\n"
            + "EVN|A01|20260101\nPID|1||MRN1^^^H^MR||DOE^JANE\nPV1|1|I";

    /** Fast retries so tests finish quickly. */
    static final RetryPolicy FAST = new RetryPolicy(5, 50, 200, 0);

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private final List<DestinationState> states = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender(), Clock.systemUTC(), new Random(7));
        engine.addListener(new QueueListener() {
            @Override
            public void onStateChanged(DestinationState state) {
                states.add(state);
            }
        });
        listener = startListener(0, ResponseMode.ACCEPT);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        if (listener != null) {
            listener.close();
        }
    }

    private TestListener startListener(int port, ResponseMode mode) throws IOException, InterruptedException {
        // A port reported free a moment ago may still be briefly unavailable on some OSes; retry for a while.
        for (int attempt = 1; ; attempt++) {
            TestListener l = new TestListener("127.0.0.1", port, ListenerSettings.DEFAULTS.withMode(mode),
                    received::add);
            try {
                l.start();
                return l;
            } catch (java.net.BindException e) {
                if (port == 0 || attempt >= 50) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    /** An unsaved test destination; tests that change it save it once, so no worker sees a half-set-up version. */
    private static DestinationConfig config(int port) {
        return DestinationConfig.of("Test", "127.0.0.1", port).withTimeouts(1_000, 500).withRetry(FAST);
    }

    private DestinationConfig destination(int port) {
        return engine.saveDestination(config(port));
    }

    private QueuedMessage enqueue(DestinationConfig d) {
        EnqueueResult r = engine.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS);
        assertThat(r.accepted()).isTrue();
        return r.message().orElseThrow();
    }

    private QueuedMessage reload(QueuedMessage m) {
        return store.message(m.id()).orElseThrow();
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        await(what, 20_000, condition);
    }

    static void await(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private void awaitStatus(QueuedMessage m, MessageStatus status) throws InterruptedException {
        await(m.controlId() + " to become " + status, () -> reload(m).status() == status);
    }

    @Test
    void deliversInFifoOrderOverOnePersistentConnection() throws Exception {
        engine.start();
        DestinationConfig d = destination(listener.port());
        List<QueuedMessage> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sent.add(enqueue(d));
        }
        awaitStatus(sent.get(19), MessageStatus.ACKNOWLEDGED);
        assertThat(sent).allSatisfy(m -> assertThat(reload(m).status()).isEqualTo(MessageStatus.ACKNOWLEDGED));
        assertThat(received).extracting(ReceivedMessage::controlId)
                .containsExactlyElementsOf(sent.stream().map(QueuedMessage::controlId).toList());
        assertThat(received.stream().map(ReceivedMessage::remote).distinct()).hasSize(1);
        assertThat(sent).allSatisfy(m -> assertThat(reload(m).attempts()).isEqualTo(1));
    }

    @Test
    void perMessageModeOpensAConnectionForEachMessage() throws Exception {
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port())
                .withConnectionMode(ConnectionMode.PER_MESSAGE));
        QueuedMessage a = enqueue(d);
        QueuedMessage b = enqueue(d);
        awaitStatus(b, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(a).status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
        assertThat(received.stream().map(ReceivedMessage::remote).distinct()).hasSize(2);
    }

    @Test
    void applicationErrorGoesToDeadLetterAndQueueContinues() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage bad = enqueue(d);
        awaitStatus(bad, MessageStatus.DEAD_LETTER);
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        QueuedMessage good = enqueue(d);
        awaitStatus(good, MessageStatus.ACKNOWLEDGED);

        QueuedMessage dl = reload(bad);
        assertThat(dl.attempts()).isEqualTo(1);
        assertThat(dl.lastOutcome()).contains(SendOutcome.APPLICATION_ERROR.name());
        assertThat(dl.possibleDuplicate()).isFalse();
        assertThat(store.attempts(bad.id())).singleElement().satisfies(a -> assertThat(a.ackCode()).contains("AE"));
    }

    @Test
    void rejectIsRetriedUntilAccepted() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage m = enqueue(d);
        await("two rejects", () -> received.size() >= 2);
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(m).attempts()).isGreaterThanOrEqualTo(3);
        // Every attempt reused the original control ID.
        assertThat(received).extracting(ReceivedMessage::controlId).containsOnly(m.controlId());
        assertThat(store.attempts(m.id())).extracting(a -> a.ackCode().orElse("")).endsWith("AA").contains("AR");
    }

    @Test
    void commitRejectGoesToDeadLetterAndCommitErrorIsRetried() throws Exception {
        listener.updateSettings(listener.settings().withCommitCodes(true).withMode(ResponseMode.REJECT));
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage rejected = enqueue(d);
        awaitStatus(rejected, MessageStatus.DEAD_LETTER);
        assertThat(reload(rejected).attempts()).isEqualTo(1);
        assertThat(reload(rejected).lastOutcome()).contains(SendOutcome.COMMIT_REJECT.name());

        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        QueuedMessage m = enqueue(d);
        await("two commit errors", () -> store.attempts(m.id()).size() >= 2);
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(store.attempts(m.id())).extracting(a -> a.ackCode().orElse("")).endsWith("CA").contains("CE");
    }

    @Test
    void rejectCanBeConfiguredToDeadLetter() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port()).withAckPolicy(
                        AckPolicy.DEFAULT.with(SendOutcome.APPLICATION_REJECT, AckPolicy.Action.DEAD_LETTER)));
        QueuedMessage m = enqueue(d);
        awaitStatus(m, MessageStatus.DEAD_LETTER);
        assertThat(reload(m).attempts()).isEqualTo(1);
    }

    @Test
    void retriesAreExhaustedIntoDeadLetter() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.MALFORMED));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port())
                .withRetry(new RetryPolicy(3, 20, 50, 0)).withCircuitBreaker(new CircuitBreakerSettings(0, 0)));
        QueuedMessage m = enqueue(d);
        awaitStatus(m, MessageStatus.DEAD_LETTER);
        QueuedMessage dl = reload(m);
        assertThat(dl.attempts()).isEqualTo(3);
        assertThat(dl.lastOutcome()).contains(SendOutcome.INVALID_ACK.name());
        assertThat(dl.possibleDuplicate()).isTrue();
        assertThat(store.audit(m.id())).last().satisfies(e ->
                assertThat(e.detail()).get().asString().contains("retries exhausted after 3 attempts"));
    }

    @Test
    void timeoutFlagsPossibleDuplicateThenDelivers() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage m = enqueue(d);
        await("first timeout", () -> reload(m).lastOutcome().filter("ACK_TIMEOUT"::equals).isPresent());
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(m).possibleDuplicate()).isTrue();
    }

    @Test
    void receiverDownAtFirstThenComesUp() throws Exception {
        int port = freePort();
        engine.start();
        DestinationConfig d = engine.saveDestination(config(port).withRetry(new RetryPolicy(0, 50, 100, 0))
                .withCircuitBreaker(new CircuitBreakerSettings(0, 0)));
        QueuedMessage m = enqueue(d);
        await("connection failures", () -> reload(m).attempts() >= 2);
        assertThat(reload(m).lastOutcome()).contains(SendOutcome.CONNECTION_FAILED.name());
        assertThat(reload(m).possibleDuplicate()).isFalse();
        TestListener late = startListener(port, ResponseMode.ACCEPT);
        try {
            awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        } finally {
            late.close();
        }
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresAndRecovers() throws Exception {
        int port = freePort();
        engine.start();
        DestinationConfig d = engine.saveDestination(config(port).withRetry(new RetryPolicy(0, 10, 10, 0))
                .withCircuitBreaker(new CircuitBreakerSettings(3, 1_000)));
        QueuedMessage m = enqueue(d);
        await("circuit to open", () -> engine.state(d.id()).status() == DestinationState.Status.CIRCUIT_OPEN);
        int attemptsWhenOpened = reload(m).attempts();
        assertThat(attemptsWhenOpened).isEqualTo(3);
        Thread.sleep(300);
        assertThat(reload(m).attempts()).isEqualTo(attemptsWhenOpened);
        assertThat(store.destinationAudit(d.id(), 5)).extracting(e -> e.detail().orElse(""))
                .anySatisfy(s -> assertThat(s).contains("Circuit opened"));

        TestListener late = startListener(port, ResponseMode.ACCEPT);
        try {
            awaitStatus(m, MessageStatus.ACKNOWLEDGED);
            await("idle", () -> engine.state(d.id()).status() == DestinationState.Status.IDLE);
            assertThat(engine.state(d.id()).consecutiveFailures()).isZero();
        } finally {
            late.close();
        }
    }

    @Test
    void reconnectsTransparentlyWhenReceiverDropsIdleConnection() throws Exception {
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage first = enqueue(d);
        awaitStatus(first, MessageStatus.ACKNOWLEDGED);
        // Receiver drops the idle persistent connection (without rebinding its port, which is OS-dependent).
        listener.disconnectClients();
        Thread.sleep(100);
        QueuedMessage second = enqueue(d);
        awaitStatus(second, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(second).attempts()).isEqualTo(1);
        assertThat(reload(second).possibleDuplicate()).isFalse();
        assertThat(received.stream().map(ReceivedMessage::remote).distinct()).hasSize(2);
    }

    @Test
    void strictFifoBlocksBehindRetryingHeadUntilItIsMovedToDeadLetter() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port())
                .withRetry(new RetryPolicy(0, 60_000, 60_000, 0)));
        QueuedMessage head = enqueue(d);
        QueuedMessage behind = enqueue(d);
        await("head rejected", () -> reload(head).status() == MessageStatus.RETRY_PENDING);
        Thread.sleep(200);
        assertThat(reload(behind).status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(engine.state(d.id()).status()).isEqualTo(DestinationState.Status.WAITING_RETRY);

        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        assertThat(engine.moveToDeadLetter(head.id(), "skip")).isTrue();
        awaitStatus(behind, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(head).status()).isEqualTo(MessageStatus.DEAD_LETTER);
    }

    @Test
    void retryNowSkipsTheBackoff() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port())
                .withRetry(new RetryPolicy(0, 60_000, 60_000, 0)));
        QueuedMessage m = enqueue(d);
        await("rejected", () -> reload(m).status() == MessageStatus.RETRY_PENDING);
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        assertThat(engine.retryNow(m.id())).isTrue();
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
    }

    @Test
    void deadLetterCanBeEditedAndRequeued() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        engine.start();
        DestinationConfig d = destination(listener.port());
        QueuedMessage m = enqueue(d);
        awaitStatus(m, MessageStatus.DEAD_LETTER);

        assertThat(engine.editDeadLetter(m.id(), "PID|broken").hasErrors()).isTrue();
        String fixed = MESSAGE.replace("|ORIG|", "|FIXED1|");
        assertThat(engine.editDeadLetter(m.id(), fixed).hasErrors()).isFalse();
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        assertThat(engine.requeue(m.id())).isTrue();
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(m).controlId()).isEqualTo("FIXED1");
        assertThat(received).last().extracting(ReceivedMessage::controlId).isEqualTo("FIXED1");
    }

    @Test
    void pauseHoldsMessagesUntilResumed() throws Exception {
        engine.start();
        DestinationConfig d = destination(listener.port());
        engine.pause(d.id());
        await("paused", () -> engine.state(d.id()).status() == DestinationState.Status.PAUSED);
        QueuedMessage m = enqueue(d);
        Thread.sleep(300);
        assertThat(reload(m).status()).isEqualTo(MessageStatus.QUEUED);
        engine.resume(d.id());
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
    }

    @Test
    void noAckDestinationMarksMessagesUnconfirmed() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port()).withAckMode(AckMode.NO_ACK));
        QueuedMessage m = enqueue(d);
        awaitStatus(m, MessageStatus.SENT_UNCONFIRMED);
    }

    @Test
    void invalidMessagesAreRejectedAndDuplicatesWarned() throws Exception {
        DestinationConfig d = destination(listener.port());
        EnqueueResult bad = engine.enqueue(d.id(), "PID|1", SendOptions.DEFAULTS);
        assertThat(bad.accepted()).isFalse();
        assertThat(bad.validation().hasErrors()).isTrue();

        SendOptions keep = new SendOptions(false, true, AckMode.EXPECT_ACK);
        assertThat(engine.enqueue(d.id(), MESSAGE, keep).warnings()).isEmpty();
        assertThat(engine.enqueue(d.id(), MESSAGE, keep).warnings())
                .singleElement().asString().contains("already queued");
    }

    @Test
    void messagesQueuedWhileStoppedAreSentAfterStartAndInterruptedOnesRecovered() throws Exception {
        DestinationConfig d = destination(listener.port());
        QueuedMessage interrupted = enqueue(d);
        QueuedMessage waiting = enqueue(d);
        // Simulate a crash between "mark in flight" and "record outcome".
        store.beginAttempt(interrupted.id());

        engine.start();
        awaitStatus(waiting, MessageStatus.ACKNOWLEDGED);
        QueuedMessage recovered = reload(interrupted);
        assertThat(recovered.status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
        assertThat(recovered.possibleDuplicate()).isTrue();
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(store.attempts(interrupted.id())).extracting(a -> a.outcome().orElse(""))
                .containsExactly("INTERRUPTED", "ACCEPTED");
    }

    @Test
    void stoppingDuringAckWaitRecordsTheAttempt() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        engine.start();
        DestinationConfig d = engine.saveDestination(config(listener.port()).withTimeouts(1_000, 30_000));
        QueuedMessage m = enqueue(d);
        // Wait until the receiver has the message, so it is truly "sent, awaiting ACK" when we stop.
        await("message at receiver", () -> received.size() == 1);
        long start = System.currentTimeMillis();
        engine.close();
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
        QueuedMessage after = reload(m);
        assertThat(after.status()).isEqualTo(MessageStatus.RETRY_PENDING);
        assertThat(after.possibleDuplicate()).isTrue();
    }

    @Test
    void deletingADestinationStopsItsWorker() throws Exception {
        engine.start();
        DestinationConfig d = destination(listener.port());
        await("idle", () -> engine.state(d.id()).status() == DestinationState.Status.IDLE);
        engine.deleteDestination(d.id());
        assertThat(engine.state(d.id()).status()).isEqualTo(DestinationState.Status.STOPPED);
        assertThat(engine.destinations()).isEmpty();
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
