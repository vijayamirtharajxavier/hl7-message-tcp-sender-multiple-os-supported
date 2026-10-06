package io.hl7sender.core.queue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Filter for searching message history. Empty or absent criteria match everything.
 *
 * @param destinationId  one destination, or empty for all
 * @param statuses       statuses to include; empty means all
 * @param messageType    text contained in MSH-9 (case-insensitive), e.g. {@code A01} or {@code ORU}
 * @param controlId      text contained in MSH-10
 * @param contains       text contained anywhere in the message content (case-insensitive)
 * @param batchId        exact import batch ID
 * @param from           enqueued at or after
 * @param to             enqueued before
 * @param limit          maximum rows
 */
public record MessageQuery(
        Optional<Long> destinationId,
        Set<MessageStatus> statuses,
        String messageType,
        String controlId,
        String contains,
        String batchId,
        Optional<Instant> from,
        Optional<Instant> to,
        int limit) {

    public static final int DEFAULT_LIMIT = 1_000;

    public MessageQuery {
        statuses = statuses == null || statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
        messageType = messageType == null ? "" : messageType.trim();
        controlId = controlId == null ? "" : controlId.trim();
        contains = contains == null ? "" : contains;
        batchId = batchId == null ? "" : batchId.trim();
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
    }

    public static MessageQuery all() {
        return new MessageQuery(Optional.empty(), Set.of(), "", "", "", "", Optional.empty(), Optional.empty(),
                DEFAULT_LIMIT);
    }

    public MessageQuery withDestination(long id) {
        return new MessageQuery(Optional.of(id), statuses, messageType, controlId, contains, batchId, from, to,
                limit);
    }

    public MessageQuery withStatuses(Set<MessageStatus> s) {
        return new MessageQuery(destinationId, s, messageType, controlId, contains, batchId, from, to, limit);
    }

    public MessageQuery withBatch(String batch) {
        return new MessageQuery(destinationId, statuses, messageType, controlId, contains, batch, from, to, limit);
    }

    public MessageQuery withText(String type, String control, String text) {
        return new MessageQuery(destinationId, statuses, type, control, text, batchId, from, to, limit);
    }

    public MessageQuery withRange(Instant start, Instant end) {
        return new MessageQuery(destinationId, statuses, messageType, controlId, contains, batchId,
                Optional.ofNullable(start), Optional.ofNullable(end), limit);
    }

    public MessageQuery withLimit(int max) {
        return new MessageQuery(destinationId, statuses, messageType, controlId, contains, batchId, from, to, max);
    }
}
