package io.hl7sender.core.schedule;

import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.queue.BulkItem;
import io.hl7sender.core.queue.BulkProgress;
import io.hl7sender.core.queue.BulkResult;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.template.TemplateEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link Schedule}s: at each cron time it queues the schedule's message(s) to its destination, with new
 * MSH-10/MSH-7 values and template variables expanded, as one batch.
 *
 * <p>Only the process that delivers from the queue runs the scheduler, so a schedule never fires twice. Runs
 * missed while nothing was running are not made up: after a restart each schedule waits for its next time. Changes
 * to schedules are picked up within 30 seconds, or at once through {@link #wake()} (which the local API's reload
 * calls).
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);
    /** Longest single wait, so schedules changed by another process are noticed. */
    private static final long MAX_WAIT_MS = 30_000;

    private final DeliveryEngine engine;
    private final TemplateEngine templates;
    private final Clock clock;
    private final Object signal = new Object();
    /** Next due time per schedule, and the definition it was computed from. */
    private final Map<Long, Instant> due = new HashMap<>();
    private final Map<Long, Schedule> known = new HashMap<>();
    private volatile boolean running;
    private boolean woken;
    private Thread thread;

    public Scheduler(DeliveryEngine engine, TemplateEngine templates, Clock clock) {
        this.engine = engine;
        this.templates = templates;
        this.clock = clock;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        engine.onReload(this::wake);
        thread = Thread.ofPlatform().daemon().name("scheduler").start(this::loop);
        LOG.info("Scheduler started");
    }

    /** Re-reads the schedules now (after one is added, changed or deleted). */
    public void wake() {
        synchronized (signal) {
            woken = true;
            signal.notifyAll();
        }
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        wake();
        if (t != null) {
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** When each enabled schedule runs next, as far as the scheduler knows. */
    public synchronized Map<Long, Instant> nextRuns() {
        return Map.copyOf(due);
    }

    private void loop() {
        while (running) {
            long waitMs;
            try {
                waitMs = tick(clock.instant());
            } catch (RuntimeException e) {
                LOG.error("Scheduler pass failed; trying again in {} ms", MAX_WAIT_MS, e);
                waitMs = MAX_WAIT_MS;
            }
            synchronized (signal) {
                if (!woken && running && waitMs > 0) {
                    try {
                        signal.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                woken = false;
            }
        }
    }

    /**
     * One pass at time {@code now}: reads the schedules, runs those that are due, and returns how long to wait
     * before the next pass. Package-private so tests can drive time.
     */
    synchronized long tick(Instant now) {
        List<Schedule> schedules = engine.store().schedules();
        Map<Long, Schedule> current = new HashMap<>();
        for (Schedule s : schedules) {
            current.put(s.id(), s);
            if (!s.enabled()) {
                due.remove(s.id());
                known.remove(s.id());
                continue;
            }
            Schedule before = known.get(s.id());
            if (before == null || !before.sameDefinition(s)) {
                known.put(s.id(), s);
                s.nextRunAfter(now).ifPresentOrElse(t -> due.put(s.id(), t), () -> due.remove(s.id()));
            }
        }
        due.keySet().removeIf(id -> !current.containsKey(id));
        known.keySet().removeIf(id -> !current.containsKey(id));

        for (Map.Entry<Long, Instant> e : new ArrayList<>(due.entrySet())) {
            if (!e.getValue().isAfter(now)) {
                Schedule s = current.get(e.getKey());
                run(s, now);
                // Next time after both the slot that just ran and now: a late pass does not run twice.
                Instant base = e.getValue().isAfter(now) ? e.getValue() : now;
                s.nextRunAfter(base).ifPresentOrElse(t -> due.put(s.id(), t), () -> due.remove(s.id()));
            }
        }
        long wait = MAX_WAIT_MS;
        for (Instant t : due.values()) {
            wait = Math.min(wait, Math.max(1, t.toEpochMilli() - now.toEpochMilli()));
        }
        return wait;
    }

    /** Runs a schedule now, whatever its time; for "Run now" and {@code hl7send schedule run}. */
    public Result runNow(long scheduleId) {
        Schedule s = engine.store().schedule(scheduleId)
                .orElseThrow(() -> new QueueException("Schedule " + scheduleId + " does not exist"));
        return run(engine, templates, s, clock.instant());
    }

    private void run(Schedule s, Instant now) {
        run(engine, templates, s, now);
    }

    /**
     * What one run did.
     *
     * @param queued   messages queued
     * @param rejected messages that failed validation and were not queued
     * @param batchId  the batch the messages were queued as, or empty if none were
     * @param summary  one line for the schedule list, e.g. {@code 3 queued (batch B...)}
     */
    public record Result(int queued, int rejected, Optional<String> batchId, String summary) {
    }

    /**
     * Queues one run of {@code s} with {@code engine}: its messages, {@code count} times, with template variables
     * expanded for each copy, and records the run. Usable without a running scheduler (from the CLI).
     */
    public static Result run(DeliveryEngine engine, TemplateEngine templates, Schedule s, Instant now) {
        QueueStore store = engine.store();
        Result result;
        try {
            List<String> messages = MessageSplitter.split(s.message()).messages();
            List<BulkItem> items = new ArrayList<>(messages.size() * s.count());
            for (int c = 0; c < s.count(); c++) {
                for (String m : messages) {
                    items.add(new BulkItem(m, "Schedule: " + s.name()));
                }
            }
            if (items.isEmpty()) {
                result = new Result(0, 0, Optional.empty(), "No HL7 message to queue");
            } else {
                BulkResult r = engine.enqueueAll(s.destinationId(), items, SendOptions.DEFAULTS, templates,
                        BulkProgress.NONE);
                String summary = r.accepted() + " queued (batch " + r.batchId() + ")";
                if (!r.rejected().isEmpty()) {
                    summary += ", " + r.rejected().size() + " not valid: " + r.rejected().get(0).reason();
                }
                result = new Result(r.accepted(), r.rejected().size(),
                        r.accepted() > 0 ? Optional.of(r.batchId()) : Optional.empty(), summary);
            }
        } catch (QueueException e) {
            result = new Result(0, 0, Optional.empty(), "Failed: " + e.getMessage());
        }
        store.recordScheduleRun(s.id(), now, result.summary());
        store.auditDestination(s.destinationId(), QueueStore.ACTOR_ENGINE, "Schedule '" + s.name() + "' ran: "
                + result.summary());
        if (result.queued() == 0) {
            LOG.warn("Schedule '{}' ran but queued nothing: {}", s.name(), result.summary());
        } else {
            LOG.info("Schedule '{}' ran: {}", s.name(), result.summary());
        }
        return result;
    }
}
