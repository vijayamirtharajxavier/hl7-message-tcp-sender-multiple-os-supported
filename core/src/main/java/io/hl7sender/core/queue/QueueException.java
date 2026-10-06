package io.hl7sender.core.queue;

/** A queue storage operation failed (disk full, database locked or corrupt, ...). */
public class QueueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueueException(String message, Throwable cause) {
        super(message, cause);
    }

    public QueueException(String message) {
        super(message);
    }
}
