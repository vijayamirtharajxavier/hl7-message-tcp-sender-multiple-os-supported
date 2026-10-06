package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * A message in the durable queue.
 *
 * @param id                database ID
 * @param destinationId     owning destination
 * @param queueSeq          dispatch order within the destination
 * @param controlId         MSH-10, fixed at enqueue time and reused on every retry so receivers can de-duplicate
 * @param messageType       MSH-9 for display
 * @param payload           exact wire text (CR-separated)
 * @param status            lifecycle state
 * @param attempts          attempts since enqueue or the last re-queue
 * @param nextAttemptAt     earliest time for the next attempt
 * @param possibleDuplicate true if an earlier attempt may have reached the receiver without an ACK
 *                          (timeout, crash while in flight)
 * @param lastOutcome       outcome of the latest attempt
 * @param lastError         detail of the latest failure
 * @param createdAt         enqueue time
 * @param updatedAt         last change
 * @param completedAt       when it reached a terminal state
 * @param source            where it came from, e.g. a file name or {@code editor}
 * @param batchId           import batch it belongs to, if any
 */
public record QueuedMessage(
        long id,
        long destinationId,
        long queueSeq,
        String controlId,
        String messageType,
        String payload,
        MessageStatus status,
        int attempts,
        Instant nextAttemptAt,
        boolean possibleDuplicate,
        Optional<String> lastOutcome,
        Optional<String> lastError,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt,
        Optional<String> source,
        Optional<String> batchId) {
}
