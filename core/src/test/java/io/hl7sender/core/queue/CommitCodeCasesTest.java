package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * How receivers use CE and CR loosely in practice, and what the default per-code policy does with each shape:
 * CR for a content error still cannot be fixed by resending; CE may be transient or permanent.
 */
@Timeout(60)
class CommitCodeCasesTest {

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener receiver;

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender(), Clock.systemUTC(), new Random(7));
        receiver = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, m -> { });
        receiver.start();
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        receiver.close();
    }

    /** The receiver answers every message in enhanced mode with {@code mode}'s code and {@code text} as MSA-3. */
    private void receiverAnswers(ResponseMode mode, String text) {
        receiver.updateSettings(new ListenerSettings(mode, 0, true, text, StandardCharsets.UTF_8,
                Mllp.DEFAULT_MAX_FRAME_BYTES));
    }

    private QueuedMessage send(int maxAttempts) {
        DestinationConfig d = engine.saveDestination(DestinationConfig.of("EHR", "127.0.0.1", receiver.port())
                .withTimeouts(1_000, 2_000).withRetry(new RetryPolicy(maxAttempts, 20, 50, 0))
                .withCircuitBreaker(new CircuitBreakerSettings(0, 0)));
        return engine.enqueue(d.id(), DeliveryEngineTest.MESSAGE, SendOptions.DEFAULTS).message().orElseThrow();
    }

    private QueuedMessage reload(QueuedMessage m) {
        return store.message(m.id()).orElseThrow();
    }

    @Test
    void crForAContentErrorIsDeadLetteredAtOnce() throws Exception {
        // CR is meant for MSH-9/11/12, but receivers also use it for content, e.g. a missing required field.
        receiverAnswers(ResponseMode.REJECT, "PID-3 is required");
        QueuedMessage m = send(5);
        await("dead letter", () -> reload(m).status() == MessageStatus.DEAD_LETTER);

        QueuedMessage done = reload(m);
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(done.lastOutcome()).contains(SendOutcome.COMMIT_REJECT.name());
        assertThat(done.lastError().orElse("")).contains("PID-3 is required");
    }

    @Test
    void transientCeIsRetriedUntilTheReceiverCommits() throws Exception {
        // CE for something that clears, such as a lock on the receiver's database.
        receiverAnswers(ResponseMode.ERROR, "database locked");
        QueuedMessage m = send(10);
        await("two CEs", () -> store.attempts(m.id()).size() >= 2);
        receiverAnswers(ResponseMode.ACCEPT, "");
        await("acknowledged", () -> reload(m).status() == MessageStatus.ACKNOWLEDGED);

        List<String> codes = store.attempts(m.id()).stream().map(a -> a.ackCode().orElse("")).toList();
        assertThat(codes).endsWith("CA");
        assertThat(codes.subList(0, codes.size() - 1)).isNotEmpty().containsOnly("CE");
    }

    @Test
    void permanentCeStopsAtTheAttemptLimitWithEveryCeInTheHistory() throws Exception {
        // CE for a content error that will never clear: retried, but only up to the limit.
        receiverAnswers(ResponseMode.ERROR, "OBX-5 is not a number");
        QueuedMessage m = send(4);
        await("dead letter", () -> reload(m).status() == MessageStatus.DEAD_LETTER);

        QueuedMessage done = reload(m);
        assertThat(done.attempts()).isEqualTo(4);
        assertThat(done.lastOutcome()).contains(SendOutcome.COMMIT_ERROR.name());
        List<AttemptRecord> history = store.attempts(m.id());
        assertThat(history).hasSize(4).allSatisfy(a -> {
            assertThat(a.ackCode()).contains("CE");
            assertThat(a.outcome()).contains(SendOutcome.COMMIT_ERROR.name());
            assertThat(a.detail().orElse("")).contains("OBX-5 is not a number");
        });
        assertThat(store.audit(m.id())).anySatisfy(e ->
                assertThat(e.detail().orElse("")).contains("retries exhausted after 4 attempts"));

        // With a per-code policy, a destination whose receiver uses CE this way can stop at the first CE instead.
        engine.saveDestination(store.destination(m.destinationId()).orElseThrow().withAckPolicy(
                AckPolicy.DEFAULT.with(SendOutcome.COMMIT_ERROR, AckPolicy.Action.DEAD_LETTER)));
        QueuedMessage next = engine.enqueue(m.destinationId(), DeliveryEngineTest.MESSAGE, SendOptions.DEFAULTS)
                .message().orElseThrow();
        await("dead letter", () -> reload(next).status() == MessageStatus.DEAD_LETTER);
        assertThat(reload(next).attempts()).isEqualTo(1);
    }
}
