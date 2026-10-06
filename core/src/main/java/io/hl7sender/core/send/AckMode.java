package io.hl7sender.core.send;

/** Whether the sender waits for an acknowledgment after sending. */
public enum AckMode {
    /** Wait for an MLLP response frame and read it as an HL7 ACK (MSA-1 = AA/AE/AR/CA/CE/CR). */
    EXPECT_ACK,
    /**
     * Send without waiting for a response. Use this only for receivers that never acknowledge, such
     * as a plain NiFi {@code ListenTCP} flow. Delivery cannot be confirmed.
     */
    NO_ACK
}
