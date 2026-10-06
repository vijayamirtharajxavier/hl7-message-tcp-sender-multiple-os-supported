package io.hl7sender.core.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads MLLP frames from a stream. It handles frames split across several TCP reads, several
 * frames in one read, and bytes between frames (which are skipped and counted).
 *
 * <p>For compatibility with lenient senders, a frame also ends at an FS that is not followed by CR.
 * In that case the byte after the FS is kept for the next frame. A frame larger than
 * {@code maxFrameBytes} fails, so a faulty peer cannot use up all memory.
 *
 * <p>Not thread-safe. Use one reader per connection.
 */
public final class MllpFrameReader {

    private final InputStream in;
    private final int maxFrameBytes;
    private final byte[] buffer = new byte[8192];
    private int pos;
    private int limit;
    private long discardedBytes;
    /** The previous frame ended with FS but its CR had not arrived yet. */
    private boolean expectTrailingCr;

    public MllpFrameReader(InputStream in, int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.in = in;
        this.maxFrameBytes = maxFrameBytes;
    }

    public MllpFrameReader(InputStream in) {
        this(in, Mllp.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * Blocks until a complete frame has been read and returns its payload, without the framing bytes.
     *
     * @return the payload, or {@code null} if the stream ended cleanly before a new frame started
     * @throws MllpProtocolException if the stream ends inside a frame or the frame is too large
     * @throws IOException           on I/O failure, including {@link java.net.SocketTimeoutException}
     */
    public byte[] readFrame() throws IOException {
        // Skip anything before the start block.
        while (true) {
            int b = next();
            if (b < 0) {
                return null;
            }
            if (b == Mllp.START_BLOCK) {
                expectTrailingCr = false;
                break;
            }
            if (!(expectTrailingCr && b == Mllp.CARRIAGE_RETURN)) {
                discardedBytes++;
            }
            expectTrailingCr = false;
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream(1024);
        while (true) {
            int b = next();
            if (b < 0) {
                throw new MllpProtocolException(
                        "Connection closed in the middle of an MLLP frame after " + payload.size() + " bytes");
            }
            if (b == Mllp.END_BLOCK) {
                int after = peek();
                if (after == Mllp.CARRIAGE_RETURN) {
                    pos++;
                } else {
                    expectTrailingCr = after < 0;
                }
                return payload.toByteArray();
            }
            if (payload.size() >= maxFrameBytes) {
                throw new MllpProtocolException("MLLP frame exceeds the maximum size of " + maxFrameBytes + " bytes");
            }
            payload.write(b);
        }
    }

    /** True if bytes have been read from the stream but not yet returned in a frame. */
    public boolean hasBufferedBytes() {
        return pos < limit;
    }

    /** Bytes received outside any frame since this reader was created. */
    public long discardedBytes() {
        return discardedBytes;
    }

    private int next() throws IOException {
        if (pos >= limit && !fill()) {
            return -1;
        }
        return buffer[pos++] & 0xFF;
    }

    /** Returns the next byte without consuming it, or -1. Only reads from the stream if bytes are already available. */
    private int peek() throws IOException {
        if (pos >= limit) {
            if (in.available() <= 0 || !fill()) {
                return -1;
            }
        }
        return buffer[pos] & 0xFF;
    }

    private boolean fill() throws IOException {
        int n = in.read(buffer, 0, buffer.length);
        if (n <= 0) {
            return false;
        }
        pos = 0;
        limit = n;
        return true;
    }
}
