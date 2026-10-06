package io.hl7sender.core.load;

import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.mllp.MllpClient;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.template.TemplateEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends messages over several concurrent MLLP connections at a target rate and measures throughput, ACK latency
 * percentiles and outcomes.
 *
 * <p>Each connection is a virtual user: it sends one message, waits for its ACK, then sends the next. With a rate,
 * messages are scheduled at fixed intervals across all connections (an open workload), so a slow receiver shows up
 * as rising latency rather than as a lower send rate, until every connection is busy. Without a rate, each
 * connection sends as fast as the receiver answers.
 *
 * <p>Messages are not queued or stored: this measures the receiver, and the durable queue would measure the local
 * disk instead. Messages are checked for structure only, so validation does not limit the rate.
 *
 * <p>A run can be cancelled from another thread with {@link #cancel()}.
 */
public final class LoadTest {

    private static final Logger LOG = LoggerFactory.getLogger(LoadTest.class);
    private static final int MAX_DISTINCT_ERRORS = 20;
    /** Pause after a failed connection, so a receiver that is down is not hammered with connection attempts. */
    private static final long CONNECT_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final LoadTestPlan plan;
    private final Hl7Sender sender;
    private final TemplateEngine templates;
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final Set<MllpClient> clients = ConcurrentHashMap.newKeySet();
    private final Recorder recorder = new Recorder();
    private volatile boolean cancelled;
    private volatile long startNanos;
    private volatile long deadlineNanos;

    public LoadTest(LoadTestPlan plan) {
        this(plan, new Hl7Sender().quiet(), new TemplateEngine());
    }

    public LoadTest(LoadTestPlan plan, Hl7Sender sender, TemplateEngine templates) {
        this.plan = plan;
        this.sender = sender;
        this.templates = templates;
    }

    public LoadTestPlan plan() {
        return plan;
    }

    /** Stops the test: no new messages are sent and messages waiting for an ACK are abandoned. */
    public void cancel() {
        cancelled = true;
        for (MllpClient c : clients) {
            c.abort();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Runs the test on the calling thread's behalf and returns when it is finished or cancelled.
     *
     * @param progress called about once a second with the results so far, and once more with the final result
     */
    public LoadTestReport run(Consumer<LoadTestSnapshot> progress) throws InterruptedException {
        Instant startedAt = Instant.now();
        startNanos = System.nanoTime();
        deadlineNanos = plan.duration().isZero() ? 0 : startNanos + plan.duration().toNanos();
        LOG.info("Load test started: {}", plan.describe());

        CountDownLatch done = new CountDownLatch(plan.connections());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < plan.connections(); i++) {
            threads.add(Thread.ofVirtual().name("load-" + i).start(() -> {
                try {
                    user();
                } catch (RuntimeException e) {
                    LOG.error("Load test connection failed", e);
                    recorder.error("Internal error: " + e);
                } finally {
                    done.countDown();
                }
            }));
        }
        try {
            while (!done.await(1, TimeUnit.SECONDS)) {
                emit(progress, false);
            }
        } catch (InterruptedException e) {
            cancel();
            for (Thread t : threads) {
                t.join();
            }
            throw e;
        }
        LoadTestSnapshot result = emit(progress, true);
        LOG.info("Load test {}: {} messages in {} ms, {} msg/s, {}% not accepted, p95 {} ms",
                cancelled ? "cancelled" : "finished", result.completed(), result.elapsedMillis(),
                result.throughput(), result.errorPercent(), result.latency().p95());
        return new LoadTestReport(plan.describe(), startedAt, result, recorder.errors(), cancelled);
    }

    /** The results so far. */
    public LoadTestSnapshot snapshot() {
        return recorder.snapshot(elapsedNanos(), sent.get(), false);
    }

    private LoadTestSnapshot emit(Consumer<LoadTestSnapshot> progress, boolean finished) {
        LoadTestSnapshot s = recorder.snapshot(elapsedNanos(), sent.get(), finished);
        if (progress != null) {
            try {
                progress.accept(s);
            } catch (RuntimeException e) {
                LOG.warn("Load test progress callback failed", e);
            }
        }
        return s;
    }

    private long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    /** One virtual user: its own connection, one message at a time. */
    private void user() {
        SendOptions options = new SendOptions(plan.generateControlId(), plan.generateControlId(), plan.ackMode());
        MllpClient client = null;
        try {
            while (!cancelled) {
                long n = issued.getAndIncrement();
                if (plan.messageCount() > 0 && n >= plan.messageCount()) {
                    return;
                }
                if (plan.ratePerSecond() > 0 && !sleepUntil(startNanos + (long) (n * 1e9 / plan.ratePerSecond()))) {
                    return;
                }
                if (pastDeadline()) {
                    return;
                }
                String text = plan.messages().get((int) (n % plan.messages().size()));
                if (TemplateEngine.hasVariables(text)) {
                    text = templates.expand(text).text();
                }
                PreparedMessage message = sender.prepare(text, options, ValidationLevel.LENIENT, null);
                if (client == null) {
                    client = new MllpClient(plan.target(), plan.tls());
                    clients.add(client);
                    if (cancelled) {
                        return;
                    }
                }
                sent.incrementAndGet();
                long start = System.nanoTime();
                SendResult result = sender.send(client, message, plan.ackMode());
                long elapsed = System.nanoTime() - start;
                if (cancelled && result.outcome() != SendOutcome.ACCEPTED) {
                    // Aborted by cancel(): not a result of the receiver.
                    sent.decrementAndGet();
                    return;
                }
                recorder.record(result, elapsed - result.connectTime().toNanos(), System.nanoTime() - startNanos);
                if (!result.outcome().connectionReusable()) {
                    clients.remove(client);
                    client.close();
                    client = null;
                }
                if (result.outcome() == SendOutcome.CONNECTION_FAILED
                        && !sleepUntil(System.nanoTime() + CONNECT_RETRY_NANOS)) {
                    return;
                }
            }
        } finally {
            if (client != null) {
                clients.remove(client);
                client.close();
            }
        }
    }

    private boolean pastDeadline() {
        return deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0;
    }

    /** Waits until {@code due}. Returns false if the test was cancelled or its time ran out first. */
    private boolean sleepUntil(long due) {
        while (true) {
            if (cancelled || pastDeadline()) {
                return false;
            }
            long wait = due - System.nanoTime();
            if (wait <= 0) {
                return true;
            }
            LockSupport.parkNanos(Math.min(wait, TimeUnit.MILLISECONDS.toNanos(50)));
        }
    }

    /** Collects results from all connections. */
    private static final class Recorder {
        private final LatencyHistogram latency = new LatencyHistogram();
        private final Map<SendOutcome, Long> outcomes = new EnumMap<>(SendOutcome.class);
        private final TreeMap<Long, SecondCounts> seconds = new TreeMap<>();
        private final Map<String, Long> errors = new LinkedHashMap<>();
        private long completed;
        private long otherErrors;

        synchronized void record(SendResult r, long latencyNanos, long completedAtNanos) {
            completed++;
            outcomes.merge(r.outcome(), 1L, Long::sum);
            boolean accepted = r.outcome() == SendOutcome.ACCEPTED || r.outcome() == SendOutcome.SENT_NO_ACK;
            SecondCounts sec = seconds.computeIfAbsent(completedAtNanos / 1_000_000_000L, k -> new SecondCounts());
            sec.completed++;
            if (!accepted) {
                sec.failed++;
                error(r.outcome().name() + (r.detail().isEmpty() ? "" : ": " + r.detail()));
            }
            if (r.outcome() != SendOutcome.CONNECTION_FAILED && r.outcome() != SendOutcome.VALIDATION_FAILED) {
                long micros = Math.max(0, latencyNanos / 1000);
                latency.record(micros);
                sec.timed++;
                sec.sumMicros += micros;
                sec.maxMicros = Math.max(sec.maxMicros, micros);
            }
        }

        synchronized void error(String text) {
            if (errors.containsKey(text) || errors.size() < MAX_DISTINCT_ERRORS) {
                errors.merge(text, 1L, Long::sum);
            } else {
                otherErrors++;
            }
        }

        synchronized List<String> errors() {
            List<String> list = new ArrayList<>();
            errors.forEach((text, n) -> list.add(n == 1 ? text : text + " (x" + n + ")"));
            if (otherErrors > 0) {
                list.add("... and " + otherErrors + " more");
            }
            return list;
        }

        synchronized LoadTestSnapshot snapshot(long elapsedNanos, long sent, boolean finished) {
            List<LoadTestSnapshot.Second> timeline = new ArrayList<>(seconds.size());
            seconds.forEach((second, c) -> timeline.add(new LoadTestSnapshot.Second(second, c.completed, c.failed,
                    c.timed == 0 ? 0 : Math.round(c.sumMicros / (double) c.timed / 10.0) / 100.0,
                    Math.round(c.maxMicros / 10.0) / 100.0)));
            double secs = elapsedNanos / 1e9;
            double throughput = secs <= 0 ? 0 : Math.round(completed / secs * 10.0) / 10.0;
            return new LoadTestSnapshot(elapsedNanos / 1_000_000, sent, completed, new EnumMap<>(outcomes),
                    LatencyStats.of(latency), throughput, timeline, finished);
        }
    }

    private static final class SecondCounts {
        long completed;
        long failed;
        long timed;
        long sumMicros;
        long maxMicros;
    }
}
