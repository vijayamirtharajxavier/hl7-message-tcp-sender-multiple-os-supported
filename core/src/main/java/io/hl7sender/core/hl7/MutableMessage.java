package io.hl7sender.core.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An HL7 message that can be read and changed field by field, using {@link FieldPath}s such as {@code PID-5.1}. Used
 * by transform scripts. Values are written as given (no escaping), so a value may itself contain components, e.g.
 * {@code set("PID-5", "DOE^JANE")}.
 *
 * <p>Setting a field that does not exist yet adds the empty fields, repetitions and components needed, and setting
 * a field of a segment that is not in the message appends that segment. MSH-1 and MSH-2 (the delimiters) cannot be
 * changed.
 */
public final class MutableMessage {

    private final Delimiters delimiters;
    private final List<String> segments;

    private MutableMessage(Delimiters delimiters, List<String> segments) {
        this.delimiters = delimiters;
        this.segments = segments;
    }

    /**
     * Parses message text (CR, LF or CRLF line endings).
     *
     * @throws Hl7FormatException if it does not start with an MSH segment
     */
    public static MutableMessage parse(String text) {
        ParsedMessage parsed = ParsedMessage.parse(text);
        List<String> list = new ArrayList<>();
        for (Segment s : parsed.segments()) {
            list.add(s.raw());
        }
        return new MutableMessage(parsed.delimiters(), list);
    }

    /** The value at {@code path}, or an empty string. */
    public String get(String path) {
        return FieldPath.parse(path).valueIn(ParsedMessage.parse(text()));
    }

    /** MSH-9.1^MSH-9.2, e.g. {@code ADT^A01}. */
    public String type() {
        return ParsedMessage.parse(text()).header().messageType();
    }

    /** How many segments are named {@code name}. */
    public int count(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return (int) segments.stream().filter(s -> segmentName(s).equals(n)).count();
    }

    /** Sets the value at {@code path}. */
    public void set(String path, String value) {
        FieldPath p = FieldPath.parse(path);
        String v = value == null ? "" : value;
        if (p.segment().equals("MSH") && p.field() <= 2) {
            throw new IllegalArgumentException("MSH-1 and MSH-2 hold the delimiters and cannot be changed");
        }
        int index = segmentIndex(p.segment(), p.occurrence());
        if (index < 0) {
            if (count(p.segment()) != p.occurrence() - 1) {
                throw new IllegalArgumentException("There is no " + p.segment() + " segment number "
                        + (p.occurrence() - 1) + " to add number " + p.occurrence() + " after");
            }
            segments.add(p.segment());
            index = segments.size() - 1;
        }
        String seg = segments.get(index);
        boolean msh = p.segment().equals("MSH");
        List<String> fields = split(seg, delimiters.field());
        // For MSH, list index 1 is MSH-2 (MSH-1 is the separator itself), so field n is at index n - 1.
        int fieldIndex = msh ? p.field() - 1 : p.field();
        pad(fields, fieldIndex + 1);
        String field = fields.get(fieldIndex);
        if (p.repetition() == 0) {
            field = v;
        } else {
            List<String> reps = split(field, delimiters.repetition());
            pad(reps, p.repetition());
            String rep = reps.get(p.repetition() - 1);
            if (p.component() == 0) {
                rep = v;
            } else {
                List<String> comps = split(rep, delimiters.component());
                pad(comps, p.component());
                String comp = comps.get(p.component() - 1);
                if (p.subcomponent() == 0) {
                    comp = v;
                } else {
                    List<String> subs = split(comp, delimiters.subcomponent());
                    pad(subs, p.subcomponent());
                    subs.set(p.subcomponent() - 1, v);
                    comp = join(subs, delimiters.subcomponent());
                }
                comps.set(p.component() - 1, comp);
                rep = join(comps, delimiters.component());
            }
            reps.set(p.repetition() - 1, rep);
            field = join(reps, delimiters.repetition());
        }
        fields.set(fieldIndex, field);
        segments.set(index, join(fields, delimiters.field()));
    }

    /** Removes every segment named {@code name}; returns how many were removed. MSH cannot be removed. */
    public int remove(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("MSH")) {
            throw new IllegalArgumentException("The MSH segment cannot be removed");
        }
        int before = segments.size();
        segments.removeIf(s -> segmentName(s).equals(n));
        return before - segments.size();
    }

    /** Appends a segment, e.g. {@code ZPI|1|value}. */
    public void add(String segment) {
        String s = segment == null ? "" : segment.strip();
        if (!s.matches("[A-Za-z][A-Za-z0-9]{2}(\\" + delimiters.field() + ".*)?")) {
            throw new IllegalArgumentException("'" + segment + "' is not a segment such as ZPI|1|value");
        }
        if (segmentName(s).equals("MSH")) {
            throw new IllegalArgumentException("A message has only one MSH segment");
        }
        segments.add(s);
    }

    /** The message with CR segment separators. */
    public String text() {
        return String.join("\r", segments) + "\r";
    }

    @Override
    public String toString() {
        return text();
    }

    private int segmentIndex(String name, int occurrence) {
        int seen = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (segmentName(segments.get(i)).equals(name) && ++seen == occurrence) {
                return i;
            }
        }
        return -1;
    }

    private String segmentName(String segment) {
        int i = segment.indexOf(delimiters.field());
        return (i < 0 ? segment : segment.substring(0, i)).toUpperCase(Locale.ROOT);
    }

    private static List<String> split(String value, char separator) {
        return new ArrayList<>(List.of(value.split(Pattern.quote(String.valueOf(separator)), -1)));
    }

    private static String join(List<String> parts, char separator) {
        return String.join(String.valueOf(separator), parts);
    }

    private static void pad(List<String> list, int size) {
        while (list.size() < size) {
            list.add("");
        }
    }
}
