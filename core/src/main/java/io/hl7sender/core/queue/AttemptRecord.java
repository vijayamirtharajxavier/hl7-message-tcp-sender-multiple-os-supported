package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * One delivery attempt, with the acknowledgment if one was received.
 *
 * @param id          database ID
 * @param messageId   message
 * @param attemptNo   1-based attempt number (keeps counting across re-queues)
 * @param startedAt   when the attempt started
 * @param finishedAt  when the outcome was recorded (empty if the process stopped mid-attempt)
 * @param outcome     result, e.g. {@code ACCEPTED} or {@code ACK_TIMEOUT}
 * @param detail      error or ACK text
 * @param roundTripMs time from write to ACK
 * @param ackCode     MSA-1, if an ACK was parsed
 * @param rawAck      raw response, if any
 */
public record AttemptRecord(
        long id,
        long messageId,
        int attemptNo,
        Instant startedAt,
        Optional<Instant> finishedAt,
        Optional<String> outcome,
        Optional<String> detail,
        Optional<Long> roundTripMs,
        Optional<String> ackCode,
        Optional<String> rawAck) {
}
