package io.hl7sender.core.hl7;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reference to a value in a message, written the way interface engines show it.
 *
 * <ul>
 *   <li>{@code PID-3}: the whole field (all repetitions)</li>
 *   <li>{@code PID-3.1}: component 1 of the first repetition</li>
 *   <li>{@code PID-3.4.2}: subcomponent 2 of component 4</li>
 *   <li>{@code PID-3[2].1}: component 1 of the second repetition</li>
 *   <li>{@code OBX(2)-5}: field 5 of the second OBX segment</li>
 *   <li>{@code MSH-9.2}: the trigger event (MSH-1 is the field separator, as in HL7)</li>
 * </ul>
 *
 * @param segment      segment name, upper case
 * @param occurrence   1-based occurrence of the segment in the message
 * @param field        1-based field number
 * @param repetition   1-based repetition, or 0 for the whole field
 * @param component    1-based component, or 0 for the whole repetition
 * @param subcomponent 1-based subcomponent, or 0 for the whole component
 */
public record FieldPath(String segment, int occurrence, int field, int repetition, int component, int subcomponent) {

    private static final Pattern SYNTAX = Pattern.compile("([A-Za-z][A-Za-z0-9]{2})(?:\\((\\d{1,4})\\))?"
            + "-(\\d{1,4})(?:\\[(\\d{1,4})])?(?:\\.(\\d{1,4})(?:\\.(\\d{1,4}))?)?");

    /**
     * Parses a path.
     *
     * @throws IllegalArgumentException if it is not a valid path
     */
    public static FieldPath parse(String text) {
        Matcher m = SYNTAX.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a field such as PID-3, PID-3.1, PID-3[2].1 "
                    + "or OBX(2)-5");
        }
        int occurrence = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int field = Integer.parseInt(m.group(3));
        int component = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int subcomponent = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        // A component (PID-3.1) without a repetition means the first repetition, as in interface engines.
        int repetition = m.group(4) != null ? Integer.parseInt(m.group(4)) : component > 0 ? 1 : 0;
        if (occurrence < 1 || field < 1 || (m.group(4) != null && repetition < 1)
                || (m.group(5) != null && component < 1) || (m.group(6) != null && subcomponent < 1)) {
            throw new IllegalArgumentException("'" + text + "': numbers in a field path start at 1");
        }
        return new FieldPath(m.group(1).toUpperCase(Locale.ROOT), occurrence, field, repetition, component,
                subcomponent);
    }

    /** True if {@code text} is a valid path. */
    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The value in {@code message}, or an empty string if the segment or field is absent. */
    public String valueIn(ParsedMessage message) {
        List<Segment> segments = message.all(segment);
        if (occurrence > segments.size()) {
            return "";
        }
        Segment s = segments.get(occurrence - 1);
        String value = s.field(field);
        if (repetition == 0 || (Delimiters.isHeaderName(segment) && field <= 2)) {
            return value;
        }
        Delimiters d = message.delimiters();
        List<String> repetitions = Segment.split(value, d.repetition());
        if (repetition > repetitions.size()) {
            return "";
        }
        value = repetitions.get(repetition - 1);
        if (component == 0) {
            return value;
        }
        List<String> components = Segment.split(value, d.component());
        if (component > components.size()) {
            return "";
        }
        value = components.get(component - 1);
        if (subcomponent == 0) {
            return value;
        }
        List<String> subs = Segment.split(value, d.subcomponent());
        return subcomponent > subs.size() ? "" : subs.get(subcomponent - 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(segment);
        if (occurrence > 1) {
            sb.append('(').append(occurrence).append(')');
        }
        sb.append('-').append(field);
        if (repetition > 1) {
            sb.append('[').append(repetition).append(']');
        }
        if (component > 0) {
            sb.append('.').append(component);
            if (subcomponent > 0) {
                sb.append('.').append(subcomponent);
            }
        }
        return sb.toString();
    }
}
