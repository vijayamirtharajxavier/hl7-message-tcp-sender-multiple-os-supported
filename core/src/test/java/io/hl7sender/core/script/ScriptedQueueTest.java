package io.hl7sender.core.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.queue.BulkItem;
import io.hl7sender.core.queue.BulkProgress;
import io.hl7sender.core.queue.BulkResult;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Destination scripts run when messages are queued. */
class ScriptedQueueTest {

    private static final String ADT = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ADT^A08^ADT_A01|C1|P|2.5.1\r"
            + "EVN|A08|20260101\rPID|1||MRN1^^^HOSP^MR||DOE^JANE\rNK1|1|DOE^JOHN|SPO\rPV1|1|I\r";
    private static final String SCRIPT = "msg.set('MSH-6', 'TESTFAC'); msg.remove('NK1');"
            + " if (msg.get('PV1-2') == 'O') filter('outpatient');";

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private DestinationConfig dest;

    @BeforeEach
    void setUp() {
        store = QueueStore.open(dir.resolve("queue.db"), Clock.systemUTC());
        engine = new DeliveryEngine(store, new Hl7Sender());
        dest = engine.saveDestination(DestinationConfig.of("Lab", "127.0.0.1", 1).withScript(SCRIPT));
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
    }

    @Test
    void scriptIsStoredAndTransformsQueuedMessages() {
        assertThat(store.destination(dest.id()).orElseThrow().script()).isEqualTo(SCRIPT);
        EnqueueResult r = engine.enqueue(dest.id(), ADT, SendOptions.AS_IS);
        assertThat(r.accepted()).isTrue();
        QueuedMessage m = r.message().orElseThrow();
        assertThat(m.payload()).contains("|LAB|TESTFAC|").doesNotContain("NK1");
    }

    @Test
    void filteredAndFailingMessagesAreNotQueued() {
        EnqueueResult filtered = engine.enqueue(dest.id(), ADT.replace("PV1|1|I", "PV1|1|O"), SendOptions.AS_IS);
        assertThat(filtered.accepted()).isFalse();
        assertThat(filtered.validation().errors()).singleElement().satisfies(i -> {
            assertThat(i.source()).isEqualTo(ValidationIssue.Source.SCRIPT);
            assertThat(i.message()).isEqualTo("Filtered out by the destination's script: outpatient");
        });

        DestinationConfig broken = engine.saveDestination(DestinationConfig.of("Broken", "127.0.0.1", 1)
                .withScript("msg.set('PID-3', undefinedThing.x)"));
        EnqueueResult failed = engine.enqueue(broken.id(), ADT, SendOptions.AS_IS);
        assertThat(failed.accepted()).isFalse();
        assertThat(failed.validation().errors().get(0).message()).startsWith("Script error on line 1")
                .contains("undefinedThing");
    }

    @Test
    void bulkImportsReportFilteredMessages() {
        BulkResult r = engine.enqueueAll(dest.id(), List.of(new BulkItem(ADT, "a"),
                new BulkItem(ADT.replace("PV1|1|I", "PV1|1|O"), "b"), new BulkItem(ADT, "c")),
                SendOptions.DEFAULTS, null, BulkProgress.NONE);
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.rejected()).singleElement().satisfies(x -> {
            assertThat(x.source()).isEqualTo("b");
            assertThat(x.reason()).contains("outpatient");
        });
    }

    @Test
    void replayDoesNotRunTheScriptAgain() {
        DestinationConfig adds = engine.saveDestination(DestinationConfig.of("Adds", "127.0.0.1", 1)
                .withScript("msg.add('ZHS|1')"));
        QueuedMessage first = engine.enqueue(adds.id(), ADT, SendOptions.AS_IS).message().orElseThrow();
        QueuedMessage copy = engine.replay(List.of(first.id()), null, true).get(0).copy().orElseThrow();
        assertThat(copy.payload().split("ZHS", -1)).hasSize(2);
    }

    @Test
    void scriptsTravelWithExportedProfiles() throws Exception {
        Path file = dir.resolve("profiles.json");
        DestinationProfiles.write(List.of(store.destination(dest.id()).orElseThrow()), file);
        List<DestinationConfig> read = DestinationProfiles.read(file);
        assertThat(read.get(0).script()).isEqualTo(SCRIPT);
    }
}
