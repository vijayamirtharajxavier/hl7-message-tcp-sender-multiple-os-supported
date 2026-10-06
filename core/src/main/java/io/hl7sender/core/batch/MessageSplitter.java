package io.hl7sender.core.batch;

import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits text containing one or more HL7 v2 messages into individual messages. It accepts:
 *
 * <ul>
 *   <li>messages back to back, separated by CR, LF, CRLF or blank lines (a new message starts at every MSH)</li>
 *   <li>HL7 batch files: {@code FHS/BHS ... BTS/FTS} envelopes, which are removed and whose
 *       trailer counts (BTS-1, FTS-1) are checked</li>
 *   <li>MLLP-framed captures (VT ... FS CR), for example from a network trace</li>
 * </ul>
 */
public final class MessageSplitter {

    private static final char START_BLOCK = 0x0B;
    private static final char END_BLOCK = 0x1C;

    private MessageSplitter() {
    }

    public static SplitResult split(String text) {
        List<String> messages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new SplitResult(messages, warnings, 0, false);
        }
        String cleaned = text.replace(START_BLOCK, '\n').replace(END_BLOCK, '\n');
        if (!cleaned.isEmpty() && cleaned.charAt(0) == '﻿') {
            cleaned = cleaned.substring(1);
        }

        List<String> current = null;
        int ignoredBeforeFirst = 0;
        int batches = 0;
        boolean fileHeader = false;
        int messagesInBatch = 0;
        int batchesInFile = 0;
        int lineNo = 0;
        for (String line : Hl7Text.splitSegments(cleaned)) {
            lineNo++;
            String name = line.length() >= 3 ? line.substring(0, 3) : line;
            switch (name) {
                case "MSH" -> {
                    flush(current, messages);
                    current = new ArrayList<>();
                    current.add(line);
                    messagesInBatch++;
                }
                case "FHS" -> {
                    flush(current, messages);
                    current = null;
                    fileHeader = true;
                }
                case "BHS" -> {
                    flush(current, messages);
                    current = null;
                    batches++;
                    batchesInFile++;
                    messagesInBatch = 0;
                }
                case "BTS" -> {
                    flush(current, messages);
                    current = null;
                    checkCount(line, 1, messagesInBatch, "BTS-1 (batch message count)", lineNo, warnings);
                    messagesInBatch = 0;
                }
                case "FTS" -> {
                    flush(current, messages);
                    current = null;
                    checkCount(line, 1, batchesInFile, "FTS-1 (file batch count)", lineNo, warnings);
                }
                default -> {
                    if (current == null) {
                        ignoredBeforeFirst++;
                    } else {
                        current.add(line);
                    }
                }
            }
        }
        flush(current, messages);
        if (ignoredBeforeFirst > 0) {
            warnings.add(ignoredBeforeFirst + " line(s) outside any message were ignored");
        }
        return new SplitResult(messages, warnings, batches, fileHeader);
    }

    private static void flush(List<String> segments, List<String> into) {
        if (segments != null && !segments.isEmpty()) {
            into.add(String.join(String.valueOf(Hl7Text.SEGMENT_SEPARATOR), segments) + Hl7Text.SEGMENT_SEPARATOR);
        }
    }

    private static void checkCount(String trailer, int field, int actual, String label, int lineNo,
                                   List<String> warnings) {
        if (trailer.length() < 4) {
            return;
        }
        char sep = trailer.charAt(3);
        List<String> parts = Segment.split(trailer, sep);
        String declared = parts.size() > field ? parts.get(field).trim() : "";
        if (declared.isEmpty()) {
            return;
        }
        try {
            int expected = Integer.parseInt(declared);
            if (expected != actual) {
                warnings.add("Line " + lineNo + ": " + label + " says " + expected + " but " + actual + " found");
            }
        } catch (NumberFormatException e) {
            warnings.add("Line " + lineNo + ": " + label + " is not a number: '" + declared + "'");
        }
    }

    /** Builds a batch file ({@code FHS/BHS ... BTS/FTS}) from wire-format messages, with correct counts. */
    public static String toBatchFile(List<String> messages) {
        Delimiters d = Delimiters.DEFAULT;
        String enc = d.encodingCharacters();
        StringBuilder sb = new StringBuilder();
        sb.append("FHS|").append(enc).append('\r');
        sb.append("BHS|").append(enc).append('\r');
        for (String m : messages) {
            sb.append(Hl7Text.normalize(m));
        }
        sb.append("BTS|").append(messages.size()).append('\r');
        sb.append("FTS|1").append('\r');
        return sb.toString();
    }
}
