package io.hl7sender.core.queue;

/**
 * Lifecycle of a queued message.
 *
 * <pre>
 *  QUEUED ──▶ IN_FLIGHT ──▶ ACKNOWLEDGED        (AA/CA)
 *     ▲           │    ├──▶ AWAITING_APP_ACK    (CA, and MSH-16 asks for an application ACK)
 *     │           │    └──▶ SENT_UNCONFIRMED    (no-ACK destination)
 *     │           ├───────▶ DEAD_LETTER         (AE/CR, validation, or retries exhausted)
 *     └── RETRY_PENDING ◀── (AR/CE, timeout, connection problems)
 * </pre>
 *
 * The state is written to the database before any network I/O happens, so a crash always leaves a
 * recoverable state. A message found {@code IN_FLIGHT} at startup is moved back to
 * {@code RETRY_PENDING} and flagged as a possible duplicate.
 *
 * <p>An {@code AWAITING_APP_ACK} message has been committed by the receiver and does not block the queue. It
 * becomes {@code ACKNOWLEDGED} or {@code DEAD_LETTER} when the application ACK arrives or its wait ends.
 */
public enum MessageStatus {
    QUEUED(false),
    IN_FLIGHT(false),
    RETRY_PENDING(false),
    AWAITING_APP_ACK(false),
    ACKNOWLEDGED(true),
    SENT_UNCONFIRMED(true),
    DEAD_LETTER(true);

    private final boolean terminal;

    MessageStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True if the engine will not touch the message again unless a user re-queues it. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True for messages that are not finished: still to be delivered, or waiting for an application ACK. */
    public boolean isPending() {
        return !terminal;
    }
}
