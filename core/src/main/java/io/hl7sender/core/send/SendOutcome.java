package io.hl7sender.core.send;

/** Final result of one send attempt. */
public enum SendOutcome {
    ACCEPTED(Severity.SUCCESS, false, "Accepted by the receiver"),
    SENT_NO_ACK(Severity.SUCCESS, false, "Sent; no acknowledgment expected"),
    APPLICATION_ERROR(Severity.FAILURE, false, "Receiver reported an error in the message (AE/CE)"),
    APPLICATION_REJECT(Severity.FAILURE, true, "Receiver rejected the message (AR/CR)"),
    CONTROL_ID_MISMATCH(Severity.WARNING, true, "ACK refers to a different message control ID"),
    INVALID_ACK(Severity.WARNING, true, "Response is not a valid HL7 acknowledgment"),
    ACK_TIMEOUT(Severity.WARNING, true, "No acknowledgment received before the timeout"),
    CONNECTION_CLOSED(Severity.WARNING, true, "Receiver closed the connection without acknowledging"),
    CONNECTION_FAILED(Severity.FAILURE, true, "Could not connect to the receiver"),
    PROTOCOL_ERROR(Severity.FAILURE, true, "MLLP protocol error"),
    SEND_FAILED(Severity.FAILURE, true, "Connection failed while sending"),
    VALIDATION_FAILED(Severity.FAILURE, false, "Message failed validation and was not sent");

    /**
     * How the UI should present an outcome: success (green), warning (amber: delivery state unknown)
     * or failure (red).
     */
    public enum Severity { SUCCESS, WARNING, FAILURE }

    private final Severity severity;
    private final boolean retryable;
    private final String description;

    SendOutcome(Severity severity, boolean retryable, String description) {
        this.severity = severity;
        this.retryable = retryable;
        this.description = description;
    }

    public Severity severity() {
        return severity;
    }

    /** Default retry policy: true if resending the same message later might succeed. */
    public boolean retryable() {
        return retryable;
    }

    public String description() {
        return description;
    }

    /**
     * True if the connection is still in a known-good state after this outcome and can be reused for
     * the next message. After a timeout, mismatch or protocol problem the connection must be closed,
     * so that a late or stray response is not read as the next message's ACK.
     */
    public boolean connectionReusable() {
        return switch (this) {
            case ACCEPTED, SENT_NO_ACK, APPLICATION_ERROR, APPLICATION_REJECT, VALIDATION_FAILED -> true;
            default -> false;
        };
    }
}
