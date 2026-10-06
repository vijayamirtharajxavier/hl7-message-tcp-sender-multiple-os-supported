package io.hl7sender.core.queue;

import java.time.Clock;
import java.time.Instant;

/**
 * Per-destination circuit breaker. After {@code failureThreshold} consecutive failures the circuit
 * opens and no attempts are made during the cool-down. After that one probe attempt is allowed
 * (half-open). A success closes the circuit, and a failure opens it again.
 *
 * <p>Updated by the destination's worker thread and read by others for display. It is thread-safe.
 */
final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final Clock clock;
    private CircuitBreakerSettings settings;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

    CircuitBreaker(CircuitBreakerSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    synchronized void updateSettings(CircuitBreakerSettings newSettings) {
        this.settings = newSettings;
        if (!newSettings.enabled()) {
            reset();
        }
    }

    /** Milliseconds to wait before an attempt is allowed. Zero means go ahead. */
    synchronized long millisUntilAllowed() {
        if (state != State.OPEN) {
            return 0;
        }
        long wait = openUntil.toEpochMilli() - clock.millis();
        if (wait <= 0) {
            state = State.HALF_OPEN;
            return 0;
        }
        return wait;
    }

    synchronized void recordSuccess() {
        reset();
    }

    /** Records a failure and returns true if the circuit is now open. */
    synchronized boolean recordFailure() {
        CircuitBreakerSettings s = settings;
        if (!s.enabled()) {
            return false;
        }
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= s.failureThreshold()) {
            state = State.OPEN;
            openUntil = clock.instant().plusMillis(s.coolDownMs());
            return true;
        }
        return false;
    }

    synchronized State state() {
        return state;
    }

    synchronized Instant openUntil() {
        return openUntil;
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    private synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openUntil = Instant.MIN;
    }
}
