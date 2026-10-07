package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * One delivery attempt, with the acknowledgment if one was received; or, with {@code applicationAck}, an
 * application ACK the receiver sent later for that attempt (enhanced mode).
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
 * @param applicationAck true for an application ACK received after the attempt: {@code startedAt} and
 *                    {@code finishedAt} are when it arrived, and {@code outcome} is what it means, e.g.
 *                    {@code APPLICATION_ERROR}
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
        Optional<String> rawAck,
        boolean applicationAck) {

    /** A delivery attempt. */
    public AttemptRecord(long id, long messageId, int attemptNo, Instant startedAt, Optional<Instant> finishedAt,
                         Optional<String> outcome, Optional<String> detail, Optional<Long> roundTripMs,
                         Optional<String> ackCode, Optional<String> rawAck) {
        this(id, messageId, attemptNo, startedAt, finishedAt, outcome, detail, roundTripMs, ackCode, rawAck, false);
    }
}
