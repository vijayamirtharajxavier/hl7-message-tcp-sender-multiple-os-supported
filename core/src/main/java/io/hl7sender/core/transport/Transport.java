package io.hl7sender.core.transport;

import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendResult;
import java.io.Closeable;

/**
 * An open connection (or handle) to one destination for a non-MLLP transport. The delivery engine sends one message
 * at a time and records the outcome exactly as for MLLP: accepted messages complete, and others are retried or
 * dead-lettered by the destination's ACK policy.
 *
 * <p>Implementations must not throw for delivery problems: report them as a {@link SendResult} with a
 * {@link io.hl7sender.core.send.SendOutcome} (for example {@code CONNECTION_FAILED} to retry, or
 * {@code APPLICATION_ERROR} to dead-letter). {@link Transports#result} builds one.
 */
public interface Transport extends Closeable {

    /** Sends one message; with {@link AckMode#NO_ACK} the transport need not wait for a confirmation. */
    SendResult send(PreparedMessage message, AckMode ackMode);

    /** Interrupts a send in progress from another thread (delivery is stopping). */
    default void abort() {
    }

    @Override
    default void close() {
    }
}
