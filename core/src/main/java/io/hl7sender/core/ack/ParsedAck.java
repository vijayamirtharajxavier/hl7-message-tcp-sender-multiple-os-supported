package io.hl7sender.core.ack;

import java.util.List;

/**
 * A parsed acknowledgment message.
 *
 * @param code        MSA-1
 * @param controlId   MSA-2: the MSH-10 of the message being acknowledged
 * @param text        MSA-3 text message (may be empty)
 * @param messageType MSH-9 of the acknowledgment, e.g. {@code ACK^A01^ACK}
 * @param ackControlId MSH-10 of the acknowledgment itself
 * @param errors      ERR segments, in order
 * @param raw         the acknowledgment as received (CR-separated)
 */
public record ParsedAck(
        AckCode code,
        String controlId,
        String text,
        String messageType,
        String ackControlId,
        List<AckError> errors,
        String raw) {

    public ParsedAck {
        errors = List.copyOf(errors);
    }

    public boolean isAccept() {
        return code.category() == AckCode.Category.ACCEPT;
    }
}
