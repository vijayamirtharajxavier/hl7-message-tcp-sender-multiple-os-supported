package io.hl7sender.core.hl7.validation;

/** How strictly messages are checked before they are sent or queued. */
public enum ValidationLevel {
    /** Structural checks only (MSH, delimiters, type, version). Fastest, for trusted bulk loads. */
    LENIENT("Lenient - structure only"),
    /** Structural errors block. HAPI and conformance-profile findings are warnings. */
    STANDARD("Standard - HAPI findings are warnings"),
    /** Structural, HAPI and conformance-profile findings all block. */
    STRICT("Strict - all findings block");

    private final String label;

    ValidationLevel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
