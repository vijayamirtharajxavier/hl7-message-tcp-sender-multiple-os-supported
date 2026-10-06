package io.hl7sender.core.hl7;

/** Thrown when text is not a structurally valid HL7 v2 message. */
public class Hl7FormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Hl7FormatException(String message) {
        super(message);
    }
}
