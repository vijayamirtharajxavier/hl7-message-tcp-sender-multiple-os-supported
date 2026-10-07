package io.hl7sender.cli;

import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.SendOutcome;
import java.util.List;

/** Maps outcomes to process exit codes. See {@link Hl7SendCli} for the documented list. */
final class ExitCodes {

    static final int OK = 0;
    static final int INVALID_INPUT = 1;
    static final int USAGE = 2;
    static final int APPLICATION_ERROR = 3;
    static final int APPLICATION_REJECT = 4;
    static final int DELIVERY_UNKNOWN = 5;
    static final int CONNECTION_FAILURE = 6;
    static final int QUEUE_UNAVAILABLE = 7;
    static final int TIMEOUT = 8;
    static final int ATTENTION = 9;
    static final int THRESHOLD = 10;
    static final int ACCESS_DENIED = 11;

    private ExitCodes() {
    }

    static int of(SendOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED, SENT_NO_ACK -> OK;
            case VALIDATION_FAILED -> INVALID_INPUT;
            case APPLICATION_ERROR, COMMIT_ERROR -> APPLICATION_ERROR;
            case APPLICATION_REJECT, COMMIT_REJECT -> APPLICATION_REJECT;
            case ACK_TIMEOUT, CONNECTION_CLOSED, INVALID_ACK, CONTROL_ID_MISMATCH -> DELIVERY_UNKNOWN;
            case CONNECTION_FAILED, SEND_FAILED, PROTOCOL_ERROR -> CONNECTION_FAILURE;
        };
    }

    /**
     * The exit code for queued messages after waiting: 0 if all were delivered, the code of the first dead-lettered
     * message's last outcome, or {@link #TIMEOUT} if some are still pending.
     */
    static int ofQueued(List<QueuedMessage> messages) {
        boolean pending = false;
        for (QueuedMessage m : messages) {
            if (m.status() == MessageStatus.DEAD_LETTER) {
                return m.lastOutcome().map(o -> {
                    try {
                        return of(SendOutcome.valueOf(o));
                    } catch (IllegalArgumentException e) {
                        return DELIVERY_UNKNOWN;
                    }
                }).filter(code -> code != OK).orElse(DELIVERY_UNKNOWN);
            }
            if (!terminal(m.status())) {
                pending = true;
            }
        }
        return pending ? TIMEOUT : OK;
    }

    static boolean terminal(MessageStatus s) {
        return s == MessageStatus.ACKNOWLEDGED || s == MessageStatus.SENT_UNCONFIRMED
                || s == MessageStatus.DEAD_LETTER;
    }
}
