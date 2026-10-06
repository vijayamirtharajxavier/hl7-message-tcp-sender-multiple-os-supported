package io.hl7sender.core.mllp;

import java.nio.charset.Charset;

/**
 * Minimal Lower Layer Protocol (MLLP) framing constants and encoder.
 *
 * <pre>
 *   &lt;VT 0x0B&gt; HL7 message bytes &lt;FS 0x1C&gt;&lt;CR 0x0D&gt;
 * </pre>
 */
public final class Mllp {

    /** Start block (vertical tab). */
    public static final byte START_BLOCK = 0x0B;
    /** End block (file separator). */
    public static final byte END_BLOCK = 0x1C;
    /** Carriage return that follows the end block. */
    public static final byte CARRIAGE_RETURN = 0x0D;

    /** Default upper bound for a single inbound frame (10 MiB). */
    public static final int DEFAULT_MAX_FRAME_BYTES = 10 * 1024 * 1024;

    private Mllp() {
    }

    /** Wraps a message in an MLLP frame using {@code charset}. */
    public static byte[] frame(String message, Charset charset) {
        return frame(message.getBytes(charset));
    }

    /** Wraps message bytes in an MLLP frame. */
    public static byte[] frame(byte[] payload) {
        byte[] out = new byte[payload.length + 3];
        out[0] = START_BLOCK;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[out.length - 2] = END_BLOCK;
        out[out.length - 1] = CARRIAGE_RETURN;
        return out;
    }
}
