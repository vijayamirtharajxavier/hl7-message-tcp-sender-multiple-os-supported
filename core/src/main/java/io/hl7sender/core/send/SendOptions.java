package io.hl7sender.core.send;

/**
 * Per-send behaviour.
 *
 * @param generateControlId replace MSH-10 with a new unique control ID
 * @param generateTimestamp replace MSH-7 with the current time
 * @param ackMode           whether to wait for an acknowledgment
 */
public record SendOptions(boolean generateControlId, boolean generateTimestamp, AckMode ackMode) {

    public static final SendOptions DEFAULTS = new SendOptions(true, true, AckMode.EXPECT_ACK);

    /** Sends the message exactly as written and waits for an ACK. */
    public static final SendOptions AS_IS = new SendOptions(false, false, AckMode.EXPECT_ACK);
}
