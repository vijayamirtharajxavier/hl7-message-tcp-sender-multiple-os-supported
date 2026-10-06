package io.hl7sender.core.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One segment of an HL7 message, split into fields.
 *
 * <p>Fields are numbered the HL7 way. For MSH, MSH-1 is the field separator itself and MSH-2 is the
 * encoding characters, so {@code field(n)} returns the same value an interface engine would report.
 */
public final class Segment {

    private final String name;
    private final String raw;
    private final Delimiters delimiters;
    /** Fields indexed by HL7 field number; index 0 holds the segment name. */
    private final List<String> fields;

    Segment(String raw, Delimiters delimiters) {
        this.raw = raw;
        this.delimiters = delimiters;
        String[] parts = raw.split(Pattern.quote(String.valueOf(delimiters.field())), -1);
        this.name = parts[0];
        List<String> list = new ArrayList<>(parts.length + 1);
        list.add(parts[0]);
        if (Delimiters.isHeaderName(name)) {
            list.add(String.valueOf(delimiters.field()));
        }
        for (int i = 1; i < parts.length; i++) {
            list.add(parts[i]);
        }
        this.fields = List.copyOf(list);
    }

    public String name() {
        return name;
    }

    public String raw() {
        return raw;
    }

    public Delimiters delimiters() {
        return delimiters;
    }

    /** Highest field number present in this segment. */
    public int fieldCount() {
        return fields.size() - 1;
    }

    /** Returns field {@code n} (1-based), or an empty string when the field is absent. */
    public String field(int n) {
        if (n < 1 || n >= fields.size()) {
            return "";
        }
        return fields.get(n);
    }

    /** Returns component {@code c} (1-based) of the first repetition of field {@code n}. */
    public String component(int n, int c) {
        if (Delimiters.isHeaderName(name) && n <= 2) {
            return c == 1 ? field(n) : "";
        }
        String firstRepetition = split(field(n), delimiters.repetition()).get(0);
        List<String> components = split(firstRepetition, delimiters.component());
        return c >= 1 && c <= components.size() ? components.get(c - 1) : "";
    }

    /** Splits {@code value} on {@code separator}, keeping empty parts. Always returns at least one element. */
    public static List<String> split(String value, char separator) {
        return List.of(value.split(Pattern.quote(String.valueOf(separator)), -1));
    }

    @Override
    public String toString() {
        return raw;
    }
}
