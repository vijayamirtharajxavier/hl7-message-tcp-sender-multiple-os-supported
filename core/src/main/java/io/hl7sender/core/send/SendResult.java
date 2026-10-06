package io.hl7sender.core.send;

import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.hl7.validation.ValidationReport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Everything known about one send attempt.
 *
 * @param outcome      final result
 * @param destination  {@code host:port}
 * @param controlId    MSH-10 of the message that was (or would have been) sent
 * @param messageType  MSH-9, e.g. {@code ADT^A01}
 * @param sentMessage  exact wire text sent, CR-separated, after MSH stamping
 * @param validation   validation report for the prepared message
 * @param ack          the parsed acknowledgment, if one was received and could be parsed
 * @param rawResponse  the raw response payload, if any bytes were received
 * @param detail       extra explanation, such as an exception message or mismatch details (may be empty)
 * @param startedAt    when the attempt started
 * @param connectTime  time taken to open the TCP connection (zero if not connected)
 * @param roundTrip    time from the end of the write to the complete response (zero if none)
 */
public record SendResult(
        SendOutcome outcome,
        String destination,
        String controlId,
        String messageType,
        String sentMessage,
        ValidationReport validation,
        Optional<ParsedAck> ack,
        Optional<String> rawResponse,
        String detail,
        Instant startedAt,
        Duration connectTime,
        Duration roundTrip) {

    /** One-line summary for logs and status bars. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(outcome.name());
        ack.ifPresent(a -> sb.append(" (").append(a.code()).append(')'));
        sb.append(": ").append(messageType.isEmpty() ? "message" : messageType);
        if (!controlId.isEmpty()) {
            sb.append(" [").append(controlId).append(']');
        }
        sb.append(" -> ").append(destination);
        if (!roundTrip.isZero()) {
            sb.append(" in ").append(roundTrip.toMillis()).append(" ms");
        }
        if (!detail.isEmpty()) {
            sb.append(" - ").append(detail);
        }
        return sb.toString();
    }
}
