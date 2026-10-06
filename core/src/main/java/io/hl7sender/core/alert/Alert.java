package io.hl7sender.core.alert;

import java.time.Instant;

/**
 * Something an operator should know about. Alerts never contain message content (PHI), only destination
 * names, counts and certificate details.
 *
 * @param kind            what triggered it
 * @param severity        how serious it is
 * @param destinationId   the destination concerned, or 0
 * @param destinationName its name, or empty
 * @param title           one-line summary
 * @param message         details
 * @param at              when it was raised
 */
public record Alert(Kind kind, Severity severity, long destinationId, String destinationName, String title,
                    String message, Instant at) {

    public enum Kind { DEAD_LETTER, CIRCUIT_OPEN, CIRCUIT_CLOSED, CERTIFICATE, UPDATE, TEST }

    public enum Severity { INFO, WARNING, CRITICAL }

    /** One line for logs, e-mail subjects and chat messages. */
    public String summary() {
        return "[" + severity + "] " + title;
    }
}
