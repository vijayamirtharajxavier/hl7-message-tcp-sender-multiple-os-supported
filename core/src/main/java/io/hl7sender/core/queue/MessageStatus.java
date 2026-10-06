package io.hl7sender.core.queue;

/**
 * Lifecycle of a queued message.
 *
 * <pre>
 *  QUEUED ──▶ IN_FLIGHT ──▶ ACKNOWLEDGED        (AA/CA)
 *     ▲           │    └──▶ SENT_UNCONFIRMED    (no-ACK destination)
 *     │           ├───────▶ DEAD_LETTER         (AE/CE, validation, or retries exhausted)
 *     └── RETRY_PENDING ◀── (AR/CR, timeout, connection problems)
 * </pre>
 *
 * The state is written to the database before any network I/O happens, so a crash always leaves a
 * recoverable state. A message found {@code IN_FLIGHT} at startup is moved back to
 * {@code RETRY_PENDING} and flagged as a possible duplicate.
 */
public enum MessageStatus {
    QUEUED(false),
    IN_FLIGHT(false),
    RETRY_PENDING(false),
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

    /** True for messages that still have to be delivered. */
    public boolean isPending() {
        return !terminal;
    }
}
