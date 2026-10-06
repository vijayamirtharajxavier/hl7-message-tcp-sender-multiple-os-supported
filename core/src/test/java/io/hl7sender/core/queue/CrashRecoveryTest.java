package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.MESSAGE;
import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chaos test: kill the delivering process with SIGKILL (Windows:
 * TerminateProcess) mid-stream. Then a fresh engine on the same database must deliver every
 * message, with none lost, in order, and at most the one in-flight message sent twice.
 */
@Timeout(120)
class CrashRecoveryTest {

    private static final int MESSAGES = 40;

    @TempDir
    Path dir;

    @Test
    void killDashNineMidStreamLosesNothing() throws Exception {
        Path db = dir.resolve("queue.db");
        List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
        // A slow receiver keeps a message in flight most of the time, so the kill lands mid-attempt.
        try (TestListener listener = new TestListener("127.0.0.1", 0,
                ListenerSettings.DEFAULTS.withDelayMs(60), received::add)) {
            listener.start();

            List<String> controlIds = new ArrayList<>();
            long destinationId;
            try (QueueStore store = QueueStore.open(db)) {
                DeliveryEngine enqueuer = new DeliveryEngine(store, new Hl7Sender());
                destinationId = enqueuer.saveDestination(DestinationConfig.of("Chaos", "127.0.0.1", listener.port())
                        .withRetry(new RetryPolicy(0, 20, 100, 0))).id();
                for (int i = 0; i < MESSAGES; i++) {
                    controlIds.add(enqueuer.enqueue(destinationId, MESSAGE, SendOptions.DEFAULTS)
                            .message().orElseThrow().controlId());
                }
            }

            Process child = startChild(db);
            try {
                await("child to deliver some messages", () -> received.size() >= 10);
                // The child holds the instance lock while it runs...
                assertThat(InstanceLock.tryAcquire(dir.resolve("queue.lock"))).isEmpty();
            } finally {
                child.destroyForcibly();
                child.waitFor();
            }
            int receivedBeforeKill = received.size();
            assertThat(receivedBeforeKill).isLessThan(MESSAGES);

            // ...and the OS releases it when the process dies.
            InstanceLock lock = InstanceLock.tryAcquire(dir.resolve("queue.lock")).orElseThrow();
            try (lock; QueueStore store = QueueStore.open(db)) {
                DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender());
                engine.start();
                try {
                    // Every message commits with a disk sync (synchronous=FULL); slow CI disks (Windows) need time.
                    await("all messages delivered after restart", 90_000, () ->
                            store.counts(destinationId).get(MessageStatus.ACKNOWLEDGED) == MESSAGES);
                } catch (AssertionError e) {
                    throw new AssertionError(e.getMessage() + "; statuses: " + store.counts(destinationId)
                            + ", received " + received.size() + " (" + receivedBeforeKill + " before the kill)", e);
                } finally {
                    engine.close();
                }
                List<QueuedMessage> all = store.messages(destinationId, EnumSet.allOf(MessageStatus.class), 1000,
                        false);
                assertThat(all).hasSize(MESSAGES)
                        .allSatisfy(m -> assertThat(m.status()).isEqualTo(MessageStatus.ACKNOWLEDGED));
                List<QueuedMessage> dup = all.stream().filter(QueuedMessage::possibleDuplicate).toList();
                assertThat(dup).hasSizeLessThanOrEqualTo(1);
            }

            // Nothing lost; order preserved; at most the in-flight message was received twice.
            Map<String, Long> counts = received.stream()
                    .collect(Collectors.groupingBy(ReceivedMessage::controlId, Collectors.counting()));
            assertThat(counts.keySet()).containsExactlyInAnyOrderElementsOf(controlIds);
            assertThat(counts.values().stream().filter(c -> c > 1)).hasSizeLessThanOrEqualTo(1);
            List<String> firstSeenOrder = received.stream().map(ReceivedMessage::controlId).distinct().toList();
            assertThat(firstSeenOrder).containsExactlyElementsOf(controlIds);
        }
    }

    private Process startChild(Path db) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ChaosChild.class.getName(), db.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        // Wait for READY, draining output in the background afterwards so the child never blocks on a full pipe.
        String line;
        List<String> early = new ArrayList<>();
        while ((line = out.readLine()) != null && !line.contains("READY")) {
            early.add(line);
        }
        if (line == null) {
            throw new AssertionError("Child exited early:\n" + String.join("\n", early));
        }
        Thread.ofVirtual().start(() -> out.lines().forEach(ignored -> { }));
        return p;
    }
}
