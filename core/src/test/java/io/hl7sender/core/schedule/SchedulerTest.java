package io.hl7sender.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.template.TemplateEngine;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Schedules in the queue database, the scheduler's timing (driven with synthetic times), and replay. */
@Timeout(30)
class SchedulerTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101000000||ADT^A08^ADT_A01|FIXED|P|2.5.1\r"
            + "EVN|A08|20260101000000\rPID|1||${RANDOM_MRN}^^^HOSP^MR||DOE^JANE\rPV1|1|I\r";

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private Scheduler scheduler;
    private DestinationConfig dest;

    @BeforeEach
    void setUp() {
        store = QueueStore.open(dir.resolve("queue.db"), Clock.systemUTC());
        // Not started: messages stay queued, so the tests can inspect them.
        engine = new DeliveryEngine(store, new Hl7Sender());
        dest = engine.saveDestination(DestinationConfig.of("Lab", "127.0.0.1", 1));
        scheduler = new Scheduler(engine, new TemplateEngine(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        engine.close();
        store.close();
    }

    private static Instant utc(String local) {
        return java.time.LocalDateTime.parse(local).atZone(ZoneId.of("UTC")).toInstant();
    }

    private Schedule save(String cron) {
        return store.saveSchedule(Schedule.of("Morning A08", cron, dest.id(), MESSAGE).withZone("UTC"));
    }

    private List<QueuedMessage> queued() {
        return store.search(MessageQuery.all().withDestination(dest.id()));
    }

    @Test
    void schedulesAreStoredAndValidated() {
        Schedule s = save("0 7 * * 1-5");
        assertThat(s.id()).isPositive();
        assertThat(store.schedules()).containsExactly(s);
        Schedule changed = store.saveSchedule(new Schedule(s.id(), "Renamed", "@hourly", "Europe/London", dest.id(),
                MESSAGE, 3, false, Optional.empty(), ""));
        assertThat(store.schedule(s.id())).contains(changed);
        assertThat(changed.count()).isEqualTo(3);

        assertThatThrownBy(() -> store.saveSchedule(Schedule.of("Renamed", "@daily", dest.id(), MESSAGE)))
                .isInstanceOf(QueueException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> Schedule.of("", "@daily", dest.id(), MESSAGE)).hasMessageContaining("name");
        assertThatThrownBy(() -> Schedule.of("x", "every day", dest.id(), MESSAGE)).hasMessageContaining("cron");
        assertThatThrownBy(() -> Schedule.of("x", "@daily", dest.id(), " ")).hasMessageContaining("empty");
        assertThatThrownBy(() -> Schedule.of("x", "@daily", dest.id(), MESSAGE).withZone("Mars/Base"))
                .hasMessageContaining("time zone");
        assertThatThrownBy(() -> Schedule.of("x", "@daily", dest.id(), MESSAGE).withCount(0))
                .hasMessageContaining("between 1");

        // Deleting the destination deletes its schedules.
        engine.deleteDestination(dest.id());
        assertThat(store.schedules()).isEmpty();
    }

    @Test
    void runsAtItsTimesButDoesNotMakeUpMissedRuns() {
        save("0 7 * * *");
        // First pass at 06:00 learns the schedule (next: 07:00) and runs nothing.
        long wait = scheduler.tick(utc("2026-01-05T06:00"));
        assertThat(queued()).isEmpty();
        assertThat(wait).isEqualTo(30_000); // capped, to notice changes
        assertThat(scheduler.nextRuns().values()).containsExactly(utc("2026-01-05T07:00"));

        scheduler.tick(utc("2026-01-05T06:59:59"));
        assertThat(queued()).isEmpty();
        scheduler.tick(utc("2026-01-05T07:00:02"));
        assertThat(queued()).hasSize(1);
        // A second pass in the same minute does not run it again.
        scheduler.tick(utc("2026-01-05T07:00:40"));
        assertThat(queued()).hasSize(1);
        assertThat(scheduler.nextRuns().values()).containsExactly(utc("2026-01-06T07:00"));

        // Asleep for three days: one run when it wakes, not three.
        scheduler.tick(utc("2026-01-09T12:00"));
        assertThat(queued()).hasSize(2);
        assertThat(scheduler.nextRuns().values()).containsExactly(utc("2026-01-10T07:00"));

        Schedule ran = store.schedules().get(0);
        assertThat(ran.lastRunAt()).contains(utc("2026-01-09T12:00"));
        assertThat(ran.lastResult()).startsWith("1 queued (batch B");
    }

    @Test
    void eachRunGetsNewControlIdsAndTemplateValues() {
        Schedule s = store.saveSchedule(Schedule.of("Load", "@hourly", dest.id(), MESSAGE + MESSAGE).withCount(5));
        Scheduler.Result r = scheduler.runNow(s.id());

        assertThat(r.queued()).isEqualTo(10);
        List<QueuedMessage> q = queued();
        assertThat(q).hasSize(10);
        assertThat(q).extracting(QueuedMessage::controlId).doesNotHaveDuplicates().doesNotContain("FIXED");
        assertThat(q.stream().map(m -> m.payload().split("\r")[2]).distinct().count()).isGreaterThan(5);
        assertThat(q).allSatisfy(m -> {
            assertThat(m.source()).contains("Schedule: Load");
            assertThat(m.batchId()).isEqualTo(r.batchId());
            assertThat(m.status()).isEqualTo(MessageStatus.QUEUED);
        });
        assertThat(store.destinationAudit(dest.id(), 10)).anySatisfy(e ->
                assertThat(e.detail()).get().asString().contains("Schedule 'Load' ran: 10 queued"));
    }

    @Test
    void changesDisablingAndDeletionArePickedUp() {
        Schedule s = save("0 7 * * *");
        scheduler.tick(utc("2026-01-05T06:00"));
        // Moved to 06:30: the next pass uses the new time.
        store.saveSchedule(new Schedule(s.id(), s.name(), "30 6 * * *", "UTC", dest.id(), MESSAGE, 1, true,
                Optional.empty(), ""));
        scheduler.tick(utc("2026-01-05T06:10"));
        assertThat(scheduler.nextRuns().values()).containsExactly(utc("2026-01-05T06:30"));
        scheduler.tick(utc("2026-01-05T06:30"));
        assertThat(queued()).hasSize(1);

        store.saveSchedule(store.schedule(s.id()).orElseThrow().withEnabled(false));
        scheduler.tick(utc("2026-01-06T06:30"));
        assertThat(queued()).hasSize(1);
        assertThat(scheduler.nextRuns()).isEmpty();

        store.deleteSchedule(s.id());
        scheduler.tick(utc("2026-01-07T06:30"));
        assertThat(scheduler.nextRuns()).isEmpty();
    }

    @Test
    void invalidMessagesAreReportedInTheRunResult() {
        Schedule s = store.saveSchedule(Schedule.of("Broken", "@daily", dest.id(), "PID|1||X\r"));
        Scheduler.Result r = scheduler.runNow(s.id());
        assertThat(r.queued()).isZero();
        assertThat(store.schedule(s.id()).orElseThrow().lastResult()).isEqualTo("No HL7 message to queue");
    }

    @Test
    void replayQueuesCopiesToTheSameOrAnotherDestination() {
        DestinationConfig other = engine.saveDestination(DestinationConfig.of("Other", "127.0.0.1", 2));
        String fixed = MESSAGE.replace("${RANDOM_MRN}", "MRN1");
        QueuedMessage a = engine.enqueue(dest.id(), fixed, io.hl7sender.core.send.SendOptions.AS_IS)
                .message().orElseThrow();
        QueuedMessage b = engine.enqueue(dest.id(), fixed.replace("FIXED", "SECOND"),
                io.hl7sender.core.send.SendOptions.AS_IS).message().orElseThrow();

        List<DeliveryEngine.Replayed> same = engine.replay(List.of(a.id(), 999L), null, false);
        assertThat(same.get(0).copy()).get().satisfies(c -> {
            assertThat(c.destinationId()).isEqualTo(dest.id());
            assertThat(c.controlId()).isEqualTo("FIXED");
            assertThat(c.payload()).isEqualTo(a.payload());
            assertThat(c.source()).contains("Replay of message " + a.id());
            assertThat(c.batchId()).get().asString().startsWith("R");
        });
        assertThat(same.get(1).problem()).contains("does not exist");

        List<DeliveryEngine.Replayed> moved = engine.replay(List.of(a.id(), b.id()), other.id(), true);
        assertThat(moved).allSatisfy(r -> assertThat(r.copy()).isPresent());
        assertThat(moved.get(0).copy().orElseThrow().controlId()).isNotEqualTo("FIXED").hasSize(20);
        assertThat(moved.get(0).copy().orElseThrow().batchId()).isEqualTo(moved.get(1).copy().orElseThrow()
                .batchId());
        assertThat(store.search(MessageQuery.all().withDestination(other.id()))).hasSize(2);
        // The originals are untouched.
        assertThat(store.message(a.id())).contains(a);
    }
}
