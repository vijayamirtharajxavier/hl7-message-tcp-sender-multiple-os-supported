package io.hl7sender.core.ack;

/**
 * One ERR segment from an acknowledgment. Empty strings mean the value was not supplied.
 *
 * @param location segment/field the error refers to, e.g. {@code PID-5}
 * @param code     error code, e.g. {@code 207} (HL7 table 0357)
 * @param text     human-readable error text
 * @param severity {@code E} error, {@code W} warning, {@code I} information, {@code F} fatal (v2.5+ only)
 * @param raw      the ERR segment as received
 */
public record AckError(String location, String code, String text, String severity, String raw) {

    /** Short description for display, e.g. {@code 207 Application internal error at PID-5}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!severity.isEmpty()) {
            sb.append('[').append(severity).append("] ");
        }
        if (!code.isEmpty()) {
            sb.append(code).append(' ');
        }
        sb.append(text.isEmpty() ? "(no description)" : text);
        if (!location.isEmpty()) {
            sb.append(" at ").append(location);
        }
        return sb.toString();
    }
}
