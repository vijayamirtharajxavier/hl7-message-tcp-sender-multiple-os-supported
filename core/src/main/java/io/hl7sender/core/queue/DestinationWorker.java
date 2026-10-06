package io.hl7sender.core.queue;

import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.mllp.MllpClient;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.tls.CertificateInfo;
import io.hl7sender.core.tls.TlsContexts;
import io.hl7sender.core.tls.TlsOptions;
import io.hl7sender.core.tls.TlsSettings;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers one destination's queue in strict FIFO order, one message in flight at a time.
 *
 * <p>Each loop reads the destination config and the head of the queue from the store, waits if the
 * head is not due yet or the circuit is open, then sends and records the outcome. A message that is
 * waiting to be retried blocks everything behind it. This keeps HL7 event order
 * (A01 → A08 → A03) intact. Users can unblock a queue by moving the head to the dead-letter queue.
 */
final class DestinationWorker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationWorker.class);

    /** Longest single wait, so config changes made outside the engine are noticed. */
    private static final long MAX_WAIT_MS = 30_000;
    /** Back-off after an unexpected error (database unavailable, ...). */
    private static final long ERROR_BACKOFF_MS = 5_000;
    /** Outcomes after which the receiver may have processed the message even though we did not see an ACK. */
    private static final Set<SendOutcome> MAY_HAVE_BEEN_PROCESSED = EnumSet.of(SendOutcome.ACK_TIMEOUT,
            SendOutcome.CONNECTION_CLOSED, SendOutcome.CONTROL_ID_MISMATCH, SendOutcome.INVALID_ACK,
            SendOutcome.PROTOCOL_ERROR);
    /** A message is validated when it is enqueued, so it is not validated again on every attempt. */
    private static final ValidationReport ALREADY_VALIDATED = new ValidationReport(Optional.empty(), 0, List.of());

    private final long destinationId;
    private final QueueStore store;
    private final Hl7Sender sender;
    private final Clock clock;
    private final RandomGenerator random;
    private final Consumer<DestinationState> stateListener;
    private final Runnable queueChanged;
    private final CircuitBreaker breaker;
    private final DeliveryEngine engine;
    private final Object signal = new Object();

    private volatile boolean running = true;
    private volatile DestinationState state;
    private boolean woken;
    private volatile MllpClient client;
    private MllpClientConfig clientConfig;
    /** The open connection for non-MLLP transports, and the settings it was opened with. */
    private volatile io.hl7sender.core.transport.Transport transport;
    private String transportKey;
    private TlsSettings tlsSettings;
    private long tlsGeneration = -1;
    private TlsOptions tls;
    private String tlsError;
    private volatile List<CertificateInfo> serverCertificates = List.of();
    private LocalDate lastCertificateWarning;
    /** Earliest time (System.nanoTime) the next attempt may start under the rate limit. */
    private long nextSendNanos;

    DestinationWorker(DestinationConfig initial, QueueStore store, Hl7Sender sender, Clock clock,
                      RandomGenerator random, Consumer<DestinationState> stateListener, Runnable queueChanged,
                      DeliveryEngine engine) {
        this.engine = engine;
        this.destinationId = initial.id();
        this.store = store;
        this.sender = sender;
        this.clock = clock;
        this.random = random;
        this.stateListener = stateListener;
        this.queueChanged = queueChanged;
        this.breaker = new CircuitBreaker(initial.circuitBreaker(), clock);
        this.state = DestinationState.stopped(destinationId);
    }

    DestinationState state() {
        return state;
    }

    /** Wakes the worker to re-check its queue now (new message, config change, retry-now, ...). */
    void wake() {
        synchronized (signal) {
            woken = true;
            signal.notifyAll();
        }
    }

    /** Asks the worker to stop, aborting any blocking network operation. */
    void stop() {
        running = false;
        MllpClient c = client;
        if (c != null) {
            c.abort();
        }
        io.hl7sender.core.transport.Transport t = transport;
        if (t != null) {
            t.abort();
        }
        wake();
    }

    @Override
    public void run() {
        LOG.info("Delivery worker started for destination {}", destinationId);
        while (running) {
            try {
                if (!loopOnce()) {
                    break;
                }
            } catch (RuntimeException e) {
                LOG.error("Delivery worker for destination {} failed; retrying in {} ms", destinationId,
                        ERROR_BACKOFF_MS, e);
                closeConnection();
                setState(DestinationState.Status.ERROR, "Internal error: " + e.getMessage(), null);
                await(ERROR_BACKOFF_MS);
                try {
                    // If the failure happened between send and record, the message is stuck IN_FLIGHT.
                    if (store.recoverInFlight(destinationId) > 0) {
                        queueChanged.run();
                    }
                } catch (QueueException again) {
                    LOG.warn("Recovery for destination {} failed: {}", destinationId, again.getMessage());
                }
            }
        }
        closeConnection();
        setState(DestinationState.Status.STOPPED, "Not running", null);
        LOG.info("Delivery worker stopped for destination {}", destinationId);
    }

    /** One scheduling decision. Returns false if the destination no longer exists. */
    private boolean loopOnce() {
        Optional<DestinationConfig> maybeConfig = store.destination(destinationId);
        if (maybeConfig.isEmpty()) {
            return false;
        }
        DestinationConfig config = maybeConfig.get();
        applyConfig(config);

        if (config.paused()) {
            closeConnection();
            setState(DestinationState.Status.PAUSED, "Paused", null);
            await(MAX_WAIT_MS);
            return true;
        }

        if (tlsError != null) {
            closeConnection();
            setState(DestinationState.Status.ERROR, "TLS configuration: " + tlsError, null);
            await(MAX_WAIT_MS);
            return true;
        }

        Optional<QueuedMessage> head = store.head(destinationId);
        if (head.isEmpty()) {
            setState(DestinationState.Status.IDLE, "Queue empty", null);
            await(MAX_WAIT_MS);
            return true;
        }
        QueuedMessage message = head.get();

        long untilDue = message.nextAttemptAt().toEpochMilli() - clock.millis();
        if (untilDue > 0) {
            setState(DestinationState.Status.WAITING_RETRY, "Message " + message.controlId() + " waiting to retry ("
                    + message.lastOutcome().orElse("") + ")", message.nextAttemptAt());
            await(Math.min(untilDue, MAX_WAIT_MS));
            return true;
        }

        long untilAllowed = breaker.millisUntilAllowed();
        if (untilAllowed > 0) {
            setState(DestinationState.Status.CIRCUIT_OPEN, "Circuit open after " + breaker.consecutiveFailures()
                    + " consecutive failures", breaker.openUntil());
            await(Math.min(untilAllowed, MAX_WAIT_MS));
            return true;
        }

        if (config.maxPerSecond() > 0) {
            long waitNanos = nextSendNanos - System.nanoTime();
            if (waitNanos > 0) {
                await(Math.max(1, waitNanos / 1_000_000));
                return true;
            }
            nextSendNanos = System.nanoTime() + 1_000_000_000L / config.maxPerSecond();
        }

        deliver(config, message);
        return true;
    }

    private void deliver(DestinationConfig config, QueuedMessage message) {
        long attemptId;
        try {
            attemptId = store.beginAttempt(message.id());
        } catch (QueueException e) {
            // The user moved or deleted the message after it was read. Re-check the queue.
            LOG.debug("Skipping message {}: {}", message.id(), e.getMessage());
            return;
        }
        queueChanged.run();
        int attemptNo = message.attempts() + 1;
        setState(DestinationState.Status.SENDING, "Sending " + message.messageType() + " [" + message.controlId()
                + "], attempt " + attemptNo, null);

        PreparedMessage prepared = new PreparedMessage(message.payload(), message.controlId(), message.messageType(),
                ALREADY_VALIDATED);
        SendResult result;
        if (config.isMllp()) {
            MllpClient c = connection(config);
            boolean wasConnected = c.isConnected();
            result = sender.send(c, prepared, config.ackMode());
            if (!wasConnected && c.isTls() && !c.peerCertificates().isEmpty()) {
                checkServerCertificates(config, c);
            }
        } else {
            result = sendByTransport(config, prepared);
        }
        if (config.connectionMode() == ConnectionMode.PER_MESSAGE || !result.outcome().connectionReusable()) {
            closeConnection();
        }

        SendOutcome outcome = result.outcome();
        AckPolicy.Action action = config.ackPolicy().actionFor(outcome);
        boolean receiverResponded = outcome.connectionReusable();
        boolean possibleDuplicate = MAY_HAVE_BEEN_PROCESSED.contains(outcome);
        Instant now = clock.instant();

        MessageStatus next;
        Instant nextAttemptAt = now;
        String reason;
        switch (action) {
            case COMPLETE -> {
                next = outcome == SendOutcome.SENT_NO_ACK ? MessageStatus.SENT_UNCONFIRMED : MessageStatus.ACKNOWLEDGED;
                reason = "";
            }
            case DEAD_LETTER -> {
                next = MessageStatus.DEAD_LETTER;
                reason = "not retried by policy";
            }
            case RETRY -> {
                if (config.retry().allowsAnotherAttempt(attemptNo)) {
                    Duration delay = config.retry().delayAfter(attemptNo, random);
                    next = MessageStatus.RETRY_PENDING;
                    nextAttemptAt = now.plus(delay);
                    reason = "retry in " + formatDelay(delay);
                } else {
                    next = MessageStatus.DEAD_LETTER;
                    reason = "retries exhausted after " + attemptNo + " attempts";
                }
            }
            default -> throw new IllegalStateException("Unknown action " + action);
        }

        if (receiverResponded) {
            breaker.recordSuccess();
        } else if (breaker.recordFailure()) {
            String detail = "Circuit opened after " + breaker.consecutiveFailures() + " consecutive failures ("
                    + outcome + "); pausing " + formatDelay(Duration.ofMillis(config.circuitBreaker().coolDownMs()));
            LOG.warn("Destination '{}': {}", config.name(), detail);
            store.auditDestination(destinationId, QueueStore.ACTOR_ENGINE, detail);
        }

        store.completeAttempt(message.id(), attemptId, result, next, nextAttemptAt, possibleDuplicate, reason);
        if (next == MessageStatus.DEAD_LETTER) {
            LOG.warn("Destination '{}': message [{}] dead-lettered after {}: {}", config.name(), message.controlId(),
                    outcome, reason);
        }
        queueChanged.run();
    }

    /** Sends with the destination's transport, opening it first if needed. Never throws. */
    private SendResult sendByTransport(DestinationConfig config, PreparedMessage prepared) {
        io.hl7sender.core.transport.Transport t = transport;
        if (t == null) {
            java.time.Instant start = clock.instant();
            Optional<io.hl7sender.core.transport.TransportFactory> factory = engine == null ? Optional.empty()
                    : engine.transports().get(config.transport());
            if (factory.isEmpty()) {
                return io.hl7sender.core.transport.Transports.result(SendOutcome.CONNECTION_FAILED,
                        config.address(), prepared, null, null, "Unknown transport '" + config.transport()
                                + "': is its plugin in the plugins folder?", start, Duration.ZERO);
            }
            try {
                t = factory.get().open(config, new io.hl7sender.core.transport.TransportContext(
                        Optional.ofNullable(tls)));
            } catch (Exception e) {
                return io.hl7sender.core.transport.Transports.result(SendOutcome.CONNECTION_FAILED,
                        config.address(), prepared, null, null, "Cannot open " + config.transport() + ": "
                                + e.getMessage(), start, Duration.ZERO);
            }
            transport = t;
            if (!running) {
                t.abort();
            }
        }
        try {
            return t.send(prepared, config.ackMode());
        } catch (RuntimeException e) {
            LOG.warn("Transport '{}' failed for destination '{}'", config.transport(), config.name(), e);
            return io.hl7sender.core.transport.Transports.result(SendOutcome.SEND_FAILED, config.address(),
                    prepared, null, null, e.toString(), clock.instant(), Duration.ZERO);
        }
    }

    private MllpClient connection(DestinationConfig config) {
        MllpClient c = client;
        if (c != null && c.isConnected() && !c.probe()) {
            LOG.info("Connection to {} was closed by the receiver; reconnecting", config.address());
            closeConnection();
            c = null;
        }
        if (c == null) {
            c = new MllpClient(clientConfig, tls);
            client = c;
            // stop() may have run just before the client was published; it could not abort it then.
            if (!running) {
                c.abort();
            }
        }
        return c;
    }

    private void checkServerCertificates(DestinationConfig config, MllpClient c) {
        List<CertificateInfo> chain = c.peerCertificates().stream()
                .map(x -> TlsContexts.info("Server", "", x)).toList();
        serverCertificates = chain;
        LocalDate today = LocalDate.now(clock);
        if (today.equals(lastCertificateWarning)) {
            return;
        }
        for (CertificateInfo cert : chain) {
            String warning = cert.warning(clock);
            if (!warning.isEmpty()) {
                lastCertificateWarning = today;
                LOG.warn("Destination '{}': {}", config.name(), warning);
                store.auditDestination(destinationId, QueueStore.ACTOR_ENGINE, warning);
            }
        }
    }

    private void applyConfig(DestinationConfig config) {
        if (config.isMllp()) {
            MllpClientConfig newClientConfig = config.clientConfig();
            if (!newClientConfig.equals(clientConfig) || transportKey != null) {
                closeConnection();
                clientConfig = newClientConfig;
                transportKey = null;
            }
        } else {
            String key = config.transport() + config.transportOptions() + config.connectTimeoutMs() + "/"
                    + config.ackTimeoutMs() + "/" + config.charset();
            if (!key.equals(transportKey)) {
                closeConnection();
                transportKey = key;
                clientConfig = null;
            }
        }
        long generation = engine == null ? 0 : engine.tlsGeneration();
        if (!config.tls().equals(tlsSettings) || generation != tlsGeneration) {
            closeConnection();
            tlsSettings = config.tls();
            tlsGeneration = generation;
            tls = null;
            tlsError = null;
            serverCertificates = List.of();
            if (engine != null && config.tls().enabled()) {
                try {
                    tls = engine.tlsOptions(config).orElse(null);
                } catch (IOException | RuntimeException e) {
                    tlsError = e.getMessage();
                    LOG.error("Destination '{}': TLS is misconfigured: {}", config.name(), tlsError);
                }
            }
        }
        breaker.updateSettings(config.circuitBreaker());
    }

    /** Certificates presented by the server on the last TLS connection. */
    List<CertificateInfo> serverCertificates() {
        return serverCertificates;
    }

    private void closeConnection() {
        MllpClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
        io.hl7sender.core.transport.Transport t = transport;
        transport = null;
        if (t != null) {
            try {
                t.close();
            } catch (RuntimeException e) {
                LOG.debug("Closing transport failed: {}", e.getMessage());
            }
        }
    }

    private void await(long millis) {
        if (millis <= 0) {
            return;
        }
        synchronized (signal) {
            if (!woken && running) {
                try {
                    signal.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
            woken = false;
        }
    }

    private void setState(DestinationState.Status status, String detail, Instant nextAttemptAt) {
        MllpClient c = client;
        DestinationState newState = new DestinationState(destinationId, status, detail,
                Optional.ofNullable(nextAttemptAt), c != null && c.isConnected(), breaker.consecutiveFailures());
        if (!newState.equals(state)) {
            state = newState;
            stateListener.accept(newState);
        }
    }

    static String formatDelay(Duration d) {
        long ms = d.toMillis();
        if (ms < 1_000) {
            return ms + " ms";
        }
        if (ms < 120_000) {
            return String.format("%.1f s", ms / 1000.0);
        }
        return (ms / 60_000) + " min";
    }
}
