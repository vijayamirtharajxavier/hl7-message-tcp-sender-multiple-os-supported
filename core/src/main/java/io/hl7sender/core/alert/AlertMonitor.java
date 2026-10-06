package io.hl7sender.core.alert;

import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueListener;
import io.hl7sender.core.secrets.SecretStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the delivery engine and raises alerts for dead-lettered messages, circuit breakers that open
 * (and close again), and TLS certificates that are expired or expire soon. Alerts go to registered
 * listeners (the desktop notifications), and to the webhook and e-mail channels in the current
 * {@link AlertSettings}.
 *
 * <p>Repeats are limited: the same alert for the same destination is raised at most every
 * {@value #REPEAT_MINUTES} minutes (certificates: once a day). All rule checks and deliveries run on one
 * background thread, so a slow SMTP server never delays message delivery.
 */
public final class AlertMonitor implements AutoCloseable {

    static final int REPEAT_MINUTES = 15;
    private static final Logger LOG = LoggerFactory.getLogger(AlertMonitor.class);
    private static final Duration REPEAT = Duration.ofMinutes(REPEAT_MINUTES);
    private static final Duration CERT_REPEAT = Duration.ofHours(24);
    private static final int RECENT = 200;

    /**
     * An alert and what happened when it was delivered.
     *
     * @param alert  the alert
     * @param errors one entry per channel that failed, e.g. {@code Webhook: HTTP 404}
     */
    public record Raised(Alert alert, List<String> errors) {
        public Raised {
            errors = List.copyOf(errors);
        }
    }

    private final DeliveryEngine engine;
    private final Supplier<AlertSettings> settings;
    private final SecretStore secrets;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final List<Consumer<Raised>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<Raised> recent = new ArrayDeque<>();
    // The fields below are only used on the executor thread.
    private final Map<Long, Integer> deadBaseline = new HashMap<>();
    private final Map<Long, DestinationState.Status> lastStatus = new HashMap<>();
    private final Map<String, Instant> lastRaised = new HashMap<>();
    private final QueueListener queueListener = new QueueListener() {
        @Override
        public void onQueueChanged(long destinationId) {
            submit(() -> checkDeadLetters(destinationId));
        }

        @Override
        public void onStateChanged(DestinationState state) {
            submit(() -> checkState(state));
        }
    };
    private volatile boolean started;

    public AlertMonitor(DeliveryEngine engine, Supplier<AlertSettings> settings, SecretStore secrets, Clock clock) {
        this.engine = engine;
        this.settings = settings;
        this.secrets = secrets;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("hl7-alerts").factory());
    }

    /** Receives every alert, after delivery to the configured channels. Called on the alert thread. */
    public void addListener(Consumer<Raised> listener) {
        listeners.add(listener);
    }

    /**
     * Starts watching. Messages already in the dead-letter queue do not raise an alert; only new ones do.
     *
     * @param checkEvery how often certificates and queues are re-checked, in addition to engine events
     */
    public synchronized void start(Duration checkEvery) {
        if (started) {
            return;
        }
        started = true;
        submit(() -> {
            for (DestinationConfig d : engine.destinations()) {
                deadBaseline.put(d.id(), deadCount(d.id()));
                lastStatus.put(d.id(), engine.state(d.id()).status());
            }
        });
        engine.addListener(queueListener);
        long ms = Math.max(1_000, checkEvery.toMillis());
        executor.scheduleWithFixedDelay(this::safeCheckAll, Math.min(ms, 10_000), ms, TimeUnit.MILLISECONDS);
    }

    /** Runs every rule now and waits for the resulting alerts to be delivered. */
    public void checkNow() {
        await(executor.submit(this::safeCheckAll));
    }

    /** Waits until queued checks and deliveries have finished. */
    public void flush() {
        await(executor.submit(() -> { }));
    }

    /** The most recent alerts, newest first. */
    public List<Raised> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /**
     * Sends a test alert through the channels in {@code s} (not the saved settings), and reports the result of
     * each. Runs on the caller's thread.
     *
     * @param smtpPassword password to use, or null for the stored one
     */
    public static List<String> test(AlertSettings s, String smtpPassword, SecretStore secrets, Clock clock) {
        Alert alert = new Alert(Alert.Kind.TEST, Alert.Severity.INFO, 0, "", "Test alert",
                "Alerts from HL7 Sender reach this channel.", clock.instant());
        List<String> results = new ArrayList<>();
        List<AlertSink> sinks;
        try {
            sinks = sinks(s, smtpPassword != null ? smtpPassword : secrets.get(AlertSettings.SMTP_PASSWORD_KEY)
                    .orElse(null));
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        if (sinks.isEmpty()) {
            return List.of("No webhook or e-mail channel is configured");
        }
        for (AlertSink sink : sinks) {
            try {
                sink.send(alert);
                results.add(sink.name() + ": sent");
            } catch (IOException | RuntimeException e) {
                results.add(sink.name() + ": failed - " + e.getMessage());
            }
        }
        return results;
    }

    @Override
    public void close() {
        engine.removeListener(queueListener);
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Rules (executor thread)

    private void safeCheckAll() {
        try {
            for (DestinationConfig d : engine.destinations()) {
                checkDeadLetters(d.id());
                checkState(engine.state(d.id()));
                checkCertificates(d);
            }
        } catch (QueueException e) {
            LOG.debug("Alert check skipped: {}", e.getMessage());
        }
    }

    private void checkDeadLetters(long destinationId) {
        AlertSettings s = settings.get();
        int count;
        try {
            count = deadCount(destinationId);
        } catch (QueueException e) {
            return;
        }
        int baseline = deadBaseline.getOrDefault(destinationId, 0);
        if (count < baseline || !s.deadLetter()) {
            deadBaseline.put(destinationId, count);
            return;
        }
        int added = count - baseline;
        if (added >= s.deadLetterThreshold()) {
            String name = name(destinationId);
            boolean raised = raise(new Alert(Alert.Kind.DEAD_LETTER, Alert.Severity.CRITICAL, destinationId, name,
                    added + " message(s) dead-lettered for " + name,
                    added + " message(s) could not be delivered to " + name + " and were moved to the dead-letter "
                            + "queue (" + count + " in total). Open the Queue tab to inspect, fix and requeue them.",
                    clock.instant()), "dead/" + destinationId, REPEAT);
            if (raised) {
                deadBaseline.put(destinationId, count);
            }
        }
    }

    private void checkState(DestinationState state) {
        DestinationState.Status previous = lastStatus.put(state.destinationId(), state.status());
        if (!settings.get().circuitOpen() || previous == state.status()) {
            return;
        }
        String name = name(state.destinationId());
        if (state.status() == DestinationState.Status.CIRCUIT_OPEN) {
            raise(new Alert(Alert.Kind.CIRCUIT_OPEN, Alert.Severity.CRITICAL, state.destinationId(), name,
                    "Delivery to " + name + " is suspended (circuit breaker open)",
                    "Delivery to " + name + " stopped after " + state.consecutiveFailures()
                            + " consecutive failures. " + state.detail(), clock.instant()),
                    "circuit/" + state.destinationId(), REPEAT);
        } else if (previous == DestinationState.Status.CIRCUIT_OPEN
                && (state.status() == DestinationState.Status.IDLE
                || state.status() == DestinationState.Status.SENDING)) {
            raise(new Alert(Alert.Kind.CIRCUIT_CLOSED, Alert.Severity.INFO, state.destinationId(), name,
                    "Delivery to " + name + " has resumed",
                    "The receiver " + name + " is accepting messages again.", clock.instant()),
                    "circuit-closed/" + state.destinationId(), Duration.ZERO);
        }
    }

    private void checkCertificates(DestinationConfig d) {
        if (!settings.get().certificateExpiry() || !d.tls().enabled()) {
            return;
        }
        List<String> warnings = engine.certificateWarnings(d);
        if (warnings.isEmpty()) {
            return;
        }
        boolean expired = warnings.stream().anyMatch(w -> w.contains("expired") || w.contains("Cannot read"));
        raise(new Alert(Alert.Kind.CERTIFICATE, expired ? Alert.Severity.CRITICAL : Alert.Severity.WARNING,
                d.id(), d.name(), (expired ? "Certificate problem for " : "Certificate expiring for ") + d.name(),
                String.join("\n", warnings), clock.instant()), "cert/" + d.id(), CERT_REPEAT);
    }

    // ---------------------------------------------------------------------------------------------
    // Delivery (executor thread)

    private boolean raise(Alert alert, String key, Duration repeat) {
        Instant last = lastRaised.get(key);
        if (last != null && alert.at().isBefore(last.plus(repeat))) {
            return false;
        }
        lastRaised.put(key, alert.at());
        LOG.warn("Alert: {} - {}", alert.summary(), alert.message().replace('\n', ' '));
        List<String> errors = new ArrayList<>();
        List<AlertSink> sinks;
        try {
            sinks = sinks(settings.get(), secrets.get(AlertSettings.SMTP_PASSWORD_KEY).orElse(null));
        } catch (IllegalArgumentException e) {
            sinks = List.of();
            errors.add(e.getMessage());
        }
        for (AlertSink sink : sinks) {
            try {
                sink.send(alert);
            } catch (IOException | RuntimeException e) {
                LOG.warn("{} alert failed: {}", sink.name(), e.getMessage());
                errors.add(sink.name() + ": " + e.getMessage());
            }
        }
        Raised raised = new Raised(alert, errors);
        synchronized (recent) {
            recent.addFirst(raised);
            while (recent.size() > RECENT) {
                recent.removeLast();
            }
        }
        for (Consumer<Raised> l : listeners) {
            try {
                l.accept(raised);
            } catch (RuntimeException e) {
                LOG.warn("Alert listener failed", e);
            }
        }
        return true;
    }

    static List<AlertSink> sinks(AlertSettings s, String smtpPassword) {
        List<AlertSink> out = new ArrayList<>();
        if (s.webhookEnabled()) {
            out.add(new WebhookSink(s.webhookUrl()));
        }
        if (s.email().enabled()) {
            out.add(new EmailSink(s.email(), smtpPassword));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    private int deadCount(long destinationId) {
        return engine.store().counts(destinationId).getOrDefault(MessageStatus.DEAD_LETTER, 0);
    }

    private String name(long destinationId) {
        Optional<DestinationConfig> d = engine.store().destination(destinationId);
        return d.map(DestinationConfig::name).orElse("destination " + destinationId);
    }

    private void submit(Runnable task) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.warn("Alert check failed: {}", e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.debug("Alert monitor closed");
        }
    }

    private static void await(java.util.concurrent.Future<?> f) {
        try {
            f.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Alert check failed: " + e.getMessage(), e);
        }
    }
}
