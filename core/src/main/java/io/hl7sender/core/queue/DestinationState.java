package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * Live status of a destination's delivery worker, for display.
 *
 * @param destinationId       destination
 * @param status              what the worker is doing
 * @param detail              human-readable explanation
 * @param nextAttemptAt       when the worker will try again, if it is waiting
 * @param connected           a persistent connection is currently open
 * @param consecutiveFailures failures since the last success (circuit breaker input)
 */
public record DestinationState(
        long destinationId,
        Status status,
        String detail,
        Optional<Instant> nextAttemptAt,
        boolean connected,
        int consecutiveFailures) {

    public enum Status {
        /** No worker (engine stopped or destination just deleted). */
        STOPPED,
        /** Queue is empty. */
        IDLE,
        /** Delivery paused by the user. */
        PAUSED,
        /** A message is being sent or its ACK awaited. */
        SENDING,
        /** The head of the queue is waiting for its retry time. */
        WAITING_RETRY,
        /** Too many consecutive failures; waiting for the cool-down to end. */
        CIRCUIT_OPEN,
        /** The worker hit an unexpected error (for example, the database is unavailable) and will try again. */
        ERROR
    }

    public static DestinationState stopped(long destinationId) {
        return new DestinationState(destinationId, Status.STOPPED, "Not running", Optional.empty(), false, 0);
    }
}
