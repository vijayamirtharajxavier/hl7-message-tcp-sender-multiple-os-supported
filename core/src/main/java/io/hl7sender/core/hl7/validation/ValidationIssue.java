package io.hl7sender.core.hl7.validation;

/**
 * One finding from {@link MessageValidator}.
 *
 * @param severity {@link Severity#ERROR} stops the message from being sent; {@link Severity#WARNING} does not
 * @param source   which check produced the finding
 * @param message  human-readable description
 */
public record ValidationIssue(Severity severity, Source source, String message) {

    public enum Severity { ERROR, WARNING }

    public enum Source {
        /** Basic framing and MSH checks that every receiver needs. */
        STRUCTURE,
        /** HAPI parsing and data-type validation against the declared HL7 version. */
        HAPI,
        /** A conformance profile configured for the destination. */
        PROFILE,
        /** The destination's transform script (a script error, or the message was filtered out). */
        SCRIPT
    }

    public static ValidationIssue error(Source source, String message) {
        return new ValidationIssue(Severity.ERROR, source, message);
    }

    public static ValidationIssue warning(Source source, String message) {
        return new ValidationIssue(Severity.WARNING, source, message);
    }

    @Override
    public String toString() {
        return severity + " [" + source + "] " + message;
    }
}
