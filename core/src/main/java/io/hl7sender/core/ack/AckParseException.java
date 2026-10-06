package io.hl7sender.core.ack;

/** The response is not a usable acknowledgment (no MSA segment, unknown MSA-1 code, ...). */
public class AckParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public AckParseException(String message) {
        super(message);
    }
}
