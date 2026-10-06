package io.hl7sender.core.hl7;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Text helpers for HL7 v2 messages. HL7 segments are separated by a carriage return. */
public final class Hl7Text {

    /** The HL7 v2 segment terminator. */
    public static final char SEGMENT_SEPARATOR = '\r';

    private Hl7Text() {
    }

    /**
     * Normalizes text typed or pasted by a user, or loaded from a file, into wire format:
     * removes a byte-order mark, turns {@code \r\n} and {@code \n} into {@code \r}, drops blank
     * lines and trailing whitespace on each segment, and ends the message with a single {@code \r}.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        List<String> segments = splitSegments(s);
        if (segments.isEmpty()) {
            return "";
        }
        return String.join(String.valueOf(SEGMENT_SEPARATOR), segments) + SEGMENT_SEPARATOR;
    }

    /** Splits text into non-blank segments, accepting any mix of CR, LF and CRLF line endings. */
    public static List<String> splitSegments(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split("\r\n|\r|\n"))
                .map(Hl7Text::stripTrailing)
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    /** Converts wire format to something readable in a text area: one segment per line. */
    public static String toDisplay(String wire) {
        if (wire == null) {
            return "";
        }
        return String.join("\n", splitSegments(wire));
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
