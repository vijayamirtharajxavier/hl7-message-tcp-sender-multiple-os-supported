package io.hl7sender.core.hl7;

import java.util.Optional;

/**
 * Where a character offset falls inside HL7 message text.
 *
 * @param segment        segment name, e.g. {@code PID}
 * @param segmentOrdinal 1-based position of the segment in the message
 * @param field          HL7 field number (0 = on the segment name)
 * @param repetition     1-based repetition within the field
 * @param component      1-based component within the repetition
 * @param subcomponent   1-based subcomponent within the component
 * @param fieldValue     full text of the field
 * @param componentValue text of the component under the cursor
 */
public record Hl7Location(String segment, int segmentOrdinal, int field, int repetition, int component,
                          int subcomponent, String fieldValue, String componentValue) {

    /** e.g. {@code PID-5.1}, {@code PID-3[2].1}, or {@code PID} on the segment name. */
    public String path() {
        if (field == 0) {
            return segment;
        }
        StringBuilder sb = new StringBuilder(segment).append('-').append(field);
        if (repetition > 1) {
            sb.append('[').append(repetition).append(']');
        }
        if (component > 1 || subcomponent > 1 || !componentValue.equals(fieldValue)) {
            sb.append('.').append(component);
            if (subcomponent > 1) {
                sb.append('.').append(subcomponent);
            }
        }
        return sb.toString();
    }

    /**
     * Finds the location of {@code offset} in {@code text}. Lines may end in CR, LF or CRLF. Delimiters
     * are read from the first MSH segment.
     */
    public static Optional<Hl7Location> at(String text, int offset) {
        if (text == null || offset < 0 || offset > text.length()) {
            return Optional.empty();
        }
        int lineStart = offset;
        while (lineStart > 0 && !isBreak(text.charAt(lineStart - 1))) {
            lineStart--;
        }
        int lineEnd = offset;
        while (lineEnd < text.length() && !isBreak(text.charAt(lineEnd))) {
            lineEnd++;
        }
        String line = text.substring(lineStart, lineEnd);
        if (line.isBlank() || line.length() < 3) {
            return Optional.empty();
        }
        Delimiters d = delimitersOf(text);
        int ordinal = countSegmentsBefore(text, lineStart) + 1;
        int col = offset - lineStart;
        String name = line.substring(0, Math.min(3, line.length()));
        boolean header = Delimiters.isHeaderName(name);

        if (col < 3) {
            return Optional.of(new Hl7Location(name, ordinal, 0, 1, 1, 1, "", ""));
        }
        if (header && col == 3) {
            String sep = String.valueOf(d.field());
            return Optional.of(new Hl7Location(name, ordinal, 1, 1, 1, 1, sep, sep));
        }

        // Index of the field separator that starts the field containing col.
        int sepsBefore = 0;
        int fieldStart = 3;
        for (int i = 3; i < col && i < line.length(); i++) {
            if (line.charAt(i) == d.field()) {
                sepsBefore++;
                fieldStart = i;
            }
        }
        if (sepsBefore == 0 && col > 3) {
            sepsBefore = 1;
        }
        int fieldEnd = line.indexOf(d.field(), fieldStart + 1);
        String fieldValue = line.substring(Math.min(fieldStart + 1, line.length()), fieldEnd < 0 ? line.length()
                : fieldEnd);
        int field = header ? sepsBefore + 1 : sepsBefore;
        int within = Math.max(0, Math.min(col - fieldStart - 1, fieldValue.length()));
        if (header && field == 2) {
            return Optional.of(new Hl7Location(name, ordinal, 2, 1, 1, 1, fieldValue, fieldValue));
        }

        String before = fieldValue.substring(0, within);
        int repetition = count(before, d.repetition()) + 1;
        String repBefore = before.substring(before.lastIndexOf(d.repetition()) + 1);
        int component = count(repBefore, d.component()) + 1;
        String compBefore = repBefore.substring(repBefore.lastIndexOf(d.component()) + 1);
        int subcomponent = count(compBefore, d.subcomponent()) + 1;

        String rep = Segment.split(fieldValue, d.repetition()).get(repetition - 1);
        java.util.List<String> comps = Segment.split(rep, d.component());
        String componentValue = component <= comps.size() ? comps.get(component - 1) : "";
        return Optional.of(new Hl7Location(name, ordinal, field, repetition, component, subcomponent, fieldValue,
                componentValue));
    }

    private static Delimiters delimitersOf(String text) {
        int msh = text.startsWith("MSH") ? 0 : Math.max(text.indexOf("\nMSH"), text.indexOf("\rMSH"));
        if (msh < 0) {
            return Delimiters.DEFAULT;
        }
        int start = text.charAt(msh) == 'M' ? msh : msh + 1;
        int end = start;
        while (end < text.length() && !isBreak(text.charAt(end))) {
            end++;
        }
        try {
            return Delimiters.fromHeaderSegment(text.substring(start, end));
        } catch (IllegalArgumentException e) {
            return Delimiters.DEFAULT;
        }
    }

    private static int countSegmentsBefore(String text, int lineStart) {
        int n = 0;
        boolean content = false;
        for (int i = 0; i < lineStart; i++) {
            char c = text.charAt(i);
            if (isBreak(c)) {
                if (content) {
                    n++;
                }
                content = false;
            } else if (!Character.isWhitespace(c)) {
                content = true;
            }
        }
        return n;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static boolean isBreak(char c) {
        return c == '\r' || c == '\n';
    }
}
