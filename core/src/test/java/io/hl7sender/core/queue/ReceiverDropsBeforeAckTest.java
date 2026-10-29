package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The queue against a receiver that commits a message but drops the connection before its ACK: the retry carries
 * the same MSH-10, so the receiver sees the same message twice and can recognise the second copy.
 */
@Timeout(60)
class ReceiverDropsBeforeAckTest {

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener receiver;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender(), Clock.systemUTC(), new Random(7));
        receiver = new TestListener("127.0.0.1", 0,
                ListenerSettings.DEFAULTS.withMode(ResponseMode.CLOSE_FIRST_ATTEMPT), received::add);
        receiver.start();
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        receiver.close();
    }

    @Test
    void theRetryIsDeliveredAndTheReceiverSeesTheSameControlIdTwice() throws Exception {
        DestinationConfig d = engine.saveDestination(DestinationConfig.of("EHR", "127.0.0.1", receiver.port())
                .withTimeouts(1_000, 2_000).withRetry(new RetryPolicy(5, 20, 50, 0))
                .withCircuitBreaker(new CircuitBreakerSettings(0, 0)));
        QueuedMessage m = engine.enqueue(d.id(), DeliveryEngineTest.MESSAGE, SendOptions.DEFAULTS).message()
                .orElseThrow();
        await("acknowledged", () -> store.message(m.id()).orElseThrow().status() == MessageStatus.ACKNOWLEDGED);

        List<AttemptRecord> attempts = store.attempts(m.id());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).outcome()).contains(SendOutcome.CONNECTION_CLOSED.name());
        assertThat(attempts.get(1).ackCode()).contains("AA");

        assertThat(received).hasSize(2);
        assertThat(received.get(1).controlId()).isEqualTo(received.get(0).controlId())
                .isEqualTo(store.message(m.id()).orElseThrow().controlId());
        assertThat(received).extracting(ReceivedMessage::copy).containsExactly(1, 2);
        assertThat(received.get(1).duplicate()).isTrue();
    }
}
