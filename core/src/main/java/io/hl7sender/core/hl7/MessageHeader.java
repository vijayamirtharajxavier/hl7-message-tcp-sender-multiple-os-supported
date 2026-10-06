package io.hl7sender.core.hl7;

/**
 * Key MSH fields. Empty strings mean the field is absent.
 *
 * @param sendingApplication       MSH-3.1
 * @param sendingFacility          MSH-4.1
 * @param receivingApplication     MSH-5.1
 * @param receivingFacility        MSH-6.1
 * @param timestamp                MSH-7
 * @param messageCode              MSH-9.1, e.g. {@code ADT}
 * @param triggerEvent             MSH-9.2, e.g. {@code A01}
 * @param messageStructure         MSH-9.3, e.g. {@code ADT_A01}
 * @param controlId                MSH-10
 * @param processingId             MSH-11.1
 * @param version                  MSH-12.1
 * @param acceptAckType            MSH-15 (enhanced acknowledgment mode)
 * @param applicationAckType       MSH-16 (enhanced acknowledgment mode)
 * @param characterSet             MSH-18
 */
public record MessageHeader(
        String sendingApplication,
        String sendingFacility,
        String receivingApplication,
        String receivingFacility,
        String timestamp,
        String messageCode,
        String triggerEvent,
        String messageStructure,
        String controlId,
        String processingId,
        String version,
        String acceptAckType,
        String applicationAckType,
        String characterSet) {

    /** Message type for display, e.g. {@code ADT^A01}. */
    public String messageType() {
        if (triggerEvent.isEmpty()) {
            return messageCode;
        }
        return messageCode + "^" + triggerEvent;
    }
}
