package io.hl7sender.core.queue;

/** How the delivery engine manages TCP connections to a destination. */
public enum ConnectionMode {
    /**
     * Keep one connection open and reuse it for every message. It is closed after any error, timeout
     * or unexpected response, so a late ACK can never be mistaken for the next message's ACK.
     */
    PERSISTENT,
    /** Open a new connection for each message. */
    PER_MESSAGE
}
