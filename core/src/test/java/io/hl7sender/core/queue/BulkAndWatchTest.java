package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.MESSAGE;
import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.template.TemplateEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Bulk import, rate limiting, per-destination validation and folder watch. */
@Timeout(60)
class BulkAndWatchTest {

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender());
        engine.setWatchPollMillis(50);
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        listener.close();
    }

    /** An unsaved test destination; tests that change it save it once, so no worker sees a half-set-up version. */
    private DestinationConfig config() {
        return DestinationConfig.of("Bulk", "127.0.0.1", listener.port()).withRetry(new RetryPolicy(3, 20, 50, 0));
    }

    private DestinationConfig destination() {
        return engine.saveDestination(config());
    }

    private int count(DestinationConfig d, MessageStatus s) {
        return store.counts(d.id()).get(s);
    }

    @Test
    void bulkImportQueuesValidItemsAndReportsInvalidOnes() throws Exception {
        DestinationConfig d = destination();
        List<BulkItem> items = List.of(new BulkItem(MESSAGE, "f#1"), new BulkItem("PID|broken", "f#2"),
                new BulkItem(MESSAGE, "f#3"));
        List<int[]> progress = new ArrayList<>();
        BulkResult r = engine.enqueueAll(d.id(), items, SendOptions.DEFAULTS, null, new BulkProgress() {
            @Override
            public void onProgress(int processed, int total, int accepted, int rejected) {
                progress.add(new int[] {processed, total, accepted, rejected});
            }
        });
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).singleElement().satisfies(x -> {
            assertThat(x.index()).isEqualTo(1);
            assertThat(x.source()).isEqualTo("f#2");
            assertThat(x.reason()).contains("MSH");
        });
        assertThat(progress).last().satisfies(p -> assertThat(p).containsExactly(3, 3, 2, 1));
        assertThat(store.search(MessageQuery.all().withBatch(r.batchId()))).hasSize(2)
                .extracting(m -> m.source().orElse("")).containsExactlyInAnyOrder("f#1", "f#3");
        engine.start();
        await("delivery", () -> count(d, MessageStatus.ACKNOWLEDGED) == 2);
    }

    @Test
    void templatesAreExpandedPerItemAndDuplicatesWarned() {
        DestinationConfig d = destination();
        String template = MESSAGE.replace("|ORIG|", "|T${SEQ:5}|").replace("MRN1", "${RANDOM_MRN}");
        List<BulkItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(new BulkItem(template, "template#" + (i + 1)));
        }
        SendOptions keepIds = new SendOptions(false, true, io.hl7sender.core.send.AckMode.EXPECT_ACK);
        BulkResult r = engine.enqueueAll(d.id(), items, keepIds, new TemplateEngine(), BulkProgress.NONE);
        assertThat(r.accepted()).isEqualTo(5);
        assertThat(r.warnings()).isEmpty();
        assertThat(store.search(MessageQuery.all().withBatch(r.batchId()))).extracting(QueuedMessage::controlId)
                .containsExactlyInAnyOrder("T00001", "T00002", "T00003", "T00004", "T00005");

        BulkResult again = engine.enqueueAll(d.id(), List.of(new BulkItem(MESSAGE, "x"), new BulkItem(MESSAGE, "y")),
                keepIds, null, BulkProgress.NONE);
        assertThat(again.warnings()).singleElement().asString().startsWith("1 message(s) reuse an MSH-10");
    }

    @Test
    void bulkImportCanBeCancelled() {
        DestinationConfig d = destination();
        List<BulkItem> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            items.add(new BulkItem(MESSAGE, "#" + i));
        }
        AtomicInteger seen = new AtomicInteger();
        BulkResult r = engine.enqueueAll(d.id(), items, SendOptions.DEFAULTS, null, new BulkProgress() {
            @Override
            public boolean isCancelled() {
                return seen.incrementAndGet() > 300;
            }
        });
        assertThat(r.cancelled()).isTrue();
        assertThat(r.accepted()).isEqualTo(300);
        assertThat(store.counts(d.id()).get(MessageStatus.QUEUED)).isEqualTo(300);
    }

    @Test
    void rateLimitSpacesDeliveries() throws Exception {
        DestinationConfig d = engine.saveDestination(config().withMaxPerSecond(20));
        for (int i = 0; i < 10; i++) {
            engine.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS);
        }
        long start = System.nanoTime();
        engine.start();
        await("delivery", () -> count(d, MessageStatus.ACKNOWLEDGED) == 10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // 10 messages at 20/s need at least 9 intervals of 50 ms.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(430);
        // The limiter spaces send starts 50 ms apart. Individual receipt gaps jitter with scheduling (Windows timer
        // ticks are ~15.6 ms), so check that no burst exceeds the rate: any 5 consecutive receipts span at least
        // 4 intervals minus one tick of jitter at each end.
        List<java.time.Instant> times = received.stream().map(ReceivedMessage::receivedAt).toList();
        for (int i = 4; i < times.size(); i++) {
            long spanMs = java.time.Duration.between(times.get(i - 4), times.get(i)).toMillis();
            assertThat(spanMs).as("receipts %d..%d", i - 4, i).isGreaterThanOrEqualTo(4 * 50 - 35);
        }
    }

    @Test
    void destinationValidationPolicyApplies() throws Exception {
        DestinationConfig d = destination();
        String badDate = MESSAGE.replace("EVN|A01|20260101", "EVN|A01|NOTADATE");
        assertThat(badDate).isNotEqualTo(MESSAGE);
        assertThat(engine.enqueue(d.id(), badDate, SendOptions.DEFAULTS).accepted()).isTrue();

        DestinationConfig strict = engine.saveDestination(d.withValidation(ValidationLevel.STRICT, ""));
        assertThat(engine.enqueue(strict.id(), badDate, SendOptions.DEFAULTS).accepted()).isFalse();

        DestinationConfig missingProfile = engine.saveDestination(
                d.withValidation(ValidationLevel.STANDARD, dir.resolve("nope.xml").toString()));
        EnqueueResult r = engine.enqueue(missingProfile.id(), MESSAGE, SendOptions.DEFAULTS);
        assertThat(r.accepted()).isFalse();
        assertThat(r.validation().errors().get(0).message()).contains("could not be loaded");
    }

    @Test
    void watchFolderImportsStableFilesAndSortsResults() throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        DestinationConfig d = engine.saveDestination(config().withWatchFolder(inbox.toString()));
        engine.start();

        String second = MESSAGE.replace("|ORIG|", "|ORIG2|");
        Files.writeString(inbox.resolve("good.hl7"), MessageSplitter.toBatchFile(List.of(MESSAGE, second)));
        Files.writeString(inbox.resolve("mixed.txt"), MESSAGE.replace("|ORIG|", "|ORIG3|") + "\nPID|orphan\n"
                + "MSH|^~\\&|X");
        Files.writeString(inbox.resolve("empty.hl7"), "nothing here");
        Files.writeString(inbox.resolve("ignored.pdf"), "not hl7");

        await("files processed", () -> Files.exists(inbox.resolve("processed/good.hl7"))
                && Files.exists(inbox.resolve("error/mixed.txt")) && Files.exists(inbox.resolve("error/empty.hl7")));
        await("delivery", () -> count(d, MessageStatus.ACKNOWLEDGED) == 3);
        assertThat(received).extracting(ReceivedMessage::controlId).containsExactly("ORIG", "ORIG2", "ORIG3");
        assertThat(Files.readString(inbox.resolve("error/mixed.txt.error.txt"))).contains("not queued");
        assertThat(Files.readString(inbox.resolve("error/empty.hl7.error.txt"))).contains("No HL7 messages");
        assertThat(inbox.resolve("ignored.pdf")).exists();
        assertThat(store.destinationAudit(d.id(), 10)).extracting(e -> e.detail().orElse(""))
                .anySatisfy(s -> assertThat(s).startsWith("Watched file good.hl7: 2 queued"));

        // A second file with the same name does not overwrite the archived one.
        Files.writeString(inbox.resolve("good.hl7"), MessageSplitter.toBatchFile(List.of(
                MESSAGE.replace("|ORIG|", "|ORIG4|"))));
        await("second import", () -> {
            try (var files = Files.list(inbox.resolve("processed"))) {
                return files.count() == 2;
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void removingTheWatchFolderStopsWatching() throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        DestinationConfig d = engine.saveDestination(config().withWatchFolder(inbox.toString()));
        engine.start();
        engine.saveDestination(d.withWatchFolder(""));
        Thread.sleep(200);
        Files.writeString(inbox.resolve("late.hl7"), MESSAGE);
        Thread.sleep(400);
        assertThat(inbox.resolve("late.hl7")).exists();
    }
}
