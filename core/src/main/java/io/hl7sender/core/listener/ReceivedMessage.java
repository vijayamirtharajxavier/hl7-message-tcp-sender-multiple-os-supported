package io.hl7sender.core.listener;

import java.time.Instant;
import java.util.Optional;

/**
 * A message received by the {@link TestListener} and how the listener answered it.
 *
 * @param receivedAt   when the frame was complete
 * @param remote       remote {@code host:port}
 * @param payload      message text as received
 * @param messageType  MSH-9, or empty if the payload was not parseable
 * @param controlId    MSH-10, or empty
 * @param mode         response mode in effect
 * @param responseCode MSA-1 sent back, or a short label such as {@code none} / {@code closed} / {@code malformed}
 * @param response     response payload sent, if any
 * @param rule         name of the responder rule that decided the response, or empty for the default response
 */
public record ReceivedMessage(
        Instant receivedAt,
        String remote,
        String payload,
        String messageType,
        String controlId,
        ResponseMode mode,
        String responseCode,
        Optional<String> response,
        String rule) {
}
