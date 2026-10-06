package io.hl7sender.core.mllp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Socket input stream with a total deadline for a whole operation, not a per-read timeout.
 * Before each blocking read it sets the socket's SO_TIMEOUT to the time left. This stops a peer
 * that trickles one byte at a time from extending the ACK timeout forever.
 */
final class DeadlineInputStream extends FilterInputStream {

    private final Socket socket;
    private long deadlineNanos = Long.MAX_VALUE;

    DeadlineInputStream(Socket socket, InputStream in) {
        super(in);
        this.socket = socket;
    }

    /** Sets the deadline {@code timeoutMillis} from now. Zero or less means no deadline. */
    void startDeadline(long timeoutMillis) {
        deadlineNanos = timeoutMillis <= 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    void clearDeadline() {
        deadlineNanos = Long.MAX_VALUE;
    }

    @Override
    public int read() throws IOException {
        applyTimeout();
        return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        applyTimeout();
        return super.read(b, off, len);
    }

    private void applyTimeout() throws IOException {
        if (deadlineNanos == Long.MAX_VALUE) {
            socket.setSoTimeout(0);
            return;
        }
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMillis <= 0) {
            throw new SocketTimeoutException("Deadline expired");
        }
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
    }
}
