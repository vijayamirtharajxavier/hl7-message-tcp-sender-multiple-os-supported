package io.hl7sender.core.hl7;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites individual MSH fields in raw message text and leaves every other byte alone. Working on
 * the text directly, instead of re-encoding through a parser, keeps the message exactly as the user
 * wrote it, including custom Z-segments and non-standard content.
 */
public final class MshEditor {

    private MshEditor() {
    }

    /**
     * Returns {@code message} (normalized to CR separators) with MSH field {@code fieldNumber} set to
     * {@code value}. Missing trailing fields are added as needed.
     *
     * @param fieldNumber HL7 field number, 3 or greater (MSH-1 and MSH-2 are the delimiters)
     */
    public static String setField(String message, int fieldNumber, String value) {
        if (fieldNumber < 3) {
            throw new IllegalArgumentException("MSH-1 and MSH-2 cannot be rewritten");
        }
        List<String> segments = new ArrayList<>(Hl7Text.splitSegments(message));
        if (segments.isEmpty() || !segments.get(0).startsWith("MSH")) {
            throw new Hl7FormatException("Message must start with an MSH segment");
        }
        String msh = segments.get(0);
        Delimiters delimiters = Delimiters.fromHeaderSegment(msh);
        String sep = String.valueOf(delimiters.field());
        List<String> parts = new ArrayList<>(Arrays.asList(msh.split(Pattern.quote(sep), -1)));
        // parts[0] = "MSH", parts[1] = MSH-2, so MSH-n lives at parts[n - 1].
        int index = fieldNumber - 1;
        while (parts.size() <= index) {
            parts.add("");
        }
        parts.set(index, value);
        segments.set(0, String.join(sep, parts));
        return String.join(String.valueOf(Hl7Text.SEGMENT_SEPARATOR), segments) + Hl7Text.SEGMENT_SEPARATOR;
    }
}
