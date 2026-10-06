package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.Optional;

/**
 * An immutable audit trail entry.
 *
 * @param id            database ID
 * @param messageId     message, if the event concerns one
 * @param destinationId destination, if the event concerns one
 * @param fromStatus    previous status
 * @param toStatus      new status
 * @param actor         {@code engine}, {@code user} or {@code recovery}
 * @param detail        explanation
 * @param at            when it happened
 */
public record AuditEvent(
        long id,
        Optional<Long> messageId,
        Optional<Long> destinationId,
        Optional<String> fromStatus,
        Optional<String> toStatus,
        String actor,
        Optional<String> detail,
        Instant at) {
}
