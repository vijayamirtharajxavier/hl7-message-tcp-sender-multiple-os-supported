package io.hl7sender.core.listener;

/** How the {@link TestListener} responds to each message it receives. */
public enum ResponseMode {
    ACCEPT("Accept (AA / CA)"),
    ERROR("Application error (AE / CE)"),
    REJECT("Reject (AR / CR)"),
    WRONG_CONTROL_ID("Accept with the wrong MSA-2"),
    MALFORMED("Malformed response (not HL7)"),
    NO_RESPONSE("No response (sender times out)"),
    CLOSE_CONNECTION("Close the connection without responding"),
    CUSTOM("Custom response (template)");

    private final String label;

    ResponseMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
