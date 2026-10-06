package io.hl7sender.core.send;

import io.hl7sender.core.hl7.validation.ValidationReport;

/**
 * A message ready to send: normalized to CR segment separators, with MSH-7/MSH-10 stamped
 * according to {@link SendOptions}, and validated.
 *
 * @param wire        exact text to send
 * @param controlId   MSH-10 after stamping (may be empty if the message has none)
 * @param messageType MSH-9 for display
 * @param validation  validation report for {@code wire}
 */
public record PreparedMessage(String wire, String controlId, String messageType, ValidationReport validation) {
}
