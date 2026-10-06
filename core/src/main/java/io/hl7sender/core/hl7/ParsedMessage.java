package io.hl7sender.core.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A lightweight, lenient view of an HL7 v2 message: segments and fields, with no version-specific
 * structure. It never fails on unknown segments or versions, so it is used for display,
 * correlation and editing. Use {@link io.hl7sender.core.hl7.validation.MessageValidator} for
 * schema-level checks.
 */
public final class ParsedMessage {

    private final List<Segment> segments;
    private final Delimiters delimiters;

    private ParsedMessage(List<Segment> segments, Delimiters delimiters) {
        this.segments = List.copyOf(segments);
        this.delimiters = delimiters;
    }

    /**
     * Parses message text. Line endings may be CR, LF or CRLF.
     *
     * @throws Hl7FormatException if the text is empty or does not start with an MSH segment
     */
    public static ParsedMessage parse(String text) {
        List<String> lines = Hl7Text.splitSegments(text);
        if (lines.isEmpty()) {
            throw new Hl7FormatException("Message is empty");
        }
        String first = lines.get(0);
        if (!first.startsWith("MSH") || first.length() < 8) {
            throw new Hl7FormatException("Message must start with an MSH segment");
        }
        Delimiters delimiters = Delimiters.fromHeaderSegment(first);
        List<Segment> segments = new ArrayList<>(lines.size());
        for (String line : lines) {
            segments.add(new Segment(line, delimiters));
        }
        return new ParsedMessage(segments, delimiters);
    }

    public List<Segment> segments() {
        return segments;
    }

    public Delimiters delimiters() {
        return delimiters;
    }

    public Segment msh() {
        return segments.get(0);
    }

    /** The first segment named {@code name}, if any. */
    public Optional<Segment> first(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** All segments named {@code name}, in message order. */
    public List<Segment> all(String name) {
        return segments.stream().filter(s -> s.name().equals(name)).toList();
    }

    /** Summary of the MSH segment. */
    public MessageHeader header() {
        Segment msh = msh();
        return new MessageHeader(
                msh.component(3, 1),
                msh.component(4, 1),
                msh.component(5, 1),
                msh.component(6, 1),
                msh.field(7),
                msh.component(9, 1),
                msh.component(9, 2),
                msh.component(9, 3),
                msh.field(10),
                msh.component(11, 1),
                msh.component(12, 1),
                msh.field(15),
                msh.field(16),
                msh.field(18));
    }
}
