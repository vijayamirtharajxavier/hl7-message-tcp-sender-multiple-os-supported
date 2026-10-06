package io.hl7sender.core.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.batch.MessageFiles;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.template.TemplateEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Import 10,000 messages from a batch file and send them throttled, with every
 * message tracked in history.
 *
 * <p>Takes about a minute, so it is tagged {@code slow}: run it with {@code ./gradlew :core:slowTest}.
 */
@Tag("slow")
@Timeout(300)
class TenThousandMessageTest {

    private static final int COUNT = 10_000;
    /** Below the engine's natural throughput, so the limit is really exercised. */
    private static final int RATE = 250;

    @TempDir
    Path dir;

    @Test
    void importAndDeliverTenThousandMessagesFromABatchFile() throws Exception {
        // 1. Build a 10,000-message batch file from a built-in template (unique control IDs, random patients).
        TemplateEngine templates = new TemplateEngine();
        String template = SampleMessages.templates().get(0).text();
        List<String> generated = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            generated.add(templates.expand(template).text());
        }
        Path batchFile = dir.resolve("load.hl7");
        Files.writeString(batchFile, MessageSplitter.toBatchFile(generated), StandardCharsets.UTF_8);

        // 2. Split it.
        SplitResult split = MessageFiles.read(batchFile, StandardCharsets.UTF_8);
        assertThat(split.messages()).hasSize(COUNT);
        assertThat(split.warnings()).isEmpty();

        List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
        try (TestListener listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
             QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            listener.start();
            DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender());
            DestinationConfig d = engine.saveDestination(DestinationConfig.of("Load", "127.0.0.1", listener.port())
                    .withMaxPerSecond(RATE));
            engine.start();
            try {
                // 3. Import (delivery starts while the import is still running).
                List<BulkItem> items = new ArrayList<>(COUNT);
                for (int i = 0; i < COUNT; i++) {
                    items.add(new BulkItem(split.messages().get(i), "load.hl7#" + (i + 1)));
                }
                long t0 = System.nanoTime();
                BulkResult result = engine.enqueueAll(d.id(), items,
                        new SendOptions(false, false, AckMode.EXPECT_ACK), null, BulkProgress.NONE);
                long importMs = (System.nanoTime() - t0) / 1_000_000;
                assertThat(result.accepted()).isEqualTo(COUNT);
                assertThat(result.rejected()).isEmpty();

                long deadline = System.currentTimeMillis() + 240_000;
                while (store.counts(d.id()).get(MessageStatus.ACKNOWLEDGED) < COUNT) {
                    assertThat(System.currentTimeMillis()).as("delivery deadline").isLessThan(deadline);
                    Thread.sleep(200);
                }
                long totalMs = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("10,000 messages: import %d ms, import + delivery %d ms (limit %d/s)%n",
                        importMs, totalMs, RATE);
                // Throttled: cannot beat the configured rate.
                assertThat(totalMs).isGreaterThanOrEqualTo((COUNT - 1) * 1000L / RATE);

                // 4. Every message is tracked in history, in order, exactly once.
                MessageQuery batch = MessageQuery.all().withBatch(result.batchId()).withLimit(COUNT + 1);
                assertThat(store.count(batch)).isEqualTo(COUNT);
                assertThat(store.count(batch.withStatuses(EnumSet.of(MessageStatus.ACKNOWLEDGED)))).isEqualTo(COUNT);
                assertThat(received).hasSize(COUNT);
                assertThat(received.stream().map(ReceivedMessage::controlId).toList()).containsExactlyElementsOf(
                        split.messages().stream().map(m -> m.split("\\|")[9]).toList());
            } finally {
                engine.close();
            }
        }
    }
}
