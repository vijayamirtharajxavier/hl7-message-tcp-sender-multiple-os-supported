package io.hl7sender.core.hl7;

/**
 * The delimiters declared in MSH-1 and MSH-2.
 *
 * @param field        field separator (MSH-1), usually {@code |}
 * @param component    component separator, usually {@code ^}
 * @param repetition   repetition separator, usually {@code ~}
 * @param escape       escape character, usually {@code \}
 * @param subcomponent subcomponent separator, usually {@code &}
 */
public record Delimiters(char field, char component, char repetition, char escape, char subcomponent) {

    public static final Delimiters DEFAULT = new Delimiters('|', '^', '~', '\\', '&');

    /**
     * Reads delimiters from the start of an MSH segment. Missing encoding characters fall back to
     * the HL7 defaults.
     *
     * @throws IllegalArgumentException if the segment is not an MSH (or batch/file header) segment
     */
    public static Delimiters fromHeaderSegment(String segment) {
        if (segment == null || segment.length() < 4 || !isHeaderName(segment.substring(0, 3))) {
            throw new IllegalArgumentException("Not an MSH segment");
        }
        char field = segment.charAt(3);
        int end = segment.indexOf(field, 4);
        String enc = end < 0 ? segment.substring(4) : segment.substring(4, end);
        return new Delimiters(
                field,
                enc.length() > 0 ? enc.charAt(0) : DEFAULT.component,
                enc.length() > 1 ? enc.charAt(1) : DEFAULT.repetition,
                enc.length() > 2 ? enc.charAt(2) : DEFAULT.escape,
                enc.length() > 3 ? enc.charAt(3) : DEFAULT.subcomponent);
    }

    /** The MSH-2 value built from these delimiters. */
    public String encodingCharacters() {
        return new String(new char[] {component, repetition, escape, subcomponent});
    }

    static boolean isHeaderName(String name) {
        return "MSH".equals(name) || "FHS".equals(name) || "BHS".equals(name);
    }
}
