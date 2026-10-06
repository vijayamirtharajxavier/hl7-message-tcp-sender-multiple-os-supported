package io.hl7sender.core.mllp;

import java.io.IOException;

/** The peer sent bytes that break MLLP framing, or a frame larger than allowed. */
public class MllpProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public MllpProtocolException(String message) {
        super(message);
    }
}
