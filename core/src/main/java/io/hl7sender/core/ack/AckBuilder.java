package io.hl7sender.core.ack;

import io.hl7sender.core.hl7.ControlIdGenerator;
import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.Hl7Timestamps;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.Segment;
import java.time.Clock;

/** Builds acknowledgments for received messages. Used by the test listener. */
public final class AckBuilder {

    /** HL7 table 0357: application internal error. */
    public static final String APPLICATION_INTERNAL_ERROR = "207";

    private final Clock clock;
    private final ControlIdGenerator controlIds;

    public AckBuilder() {
        this(Clock.systemDefaultZone());
    }

    public AckBuilder(Clock clock) {
        this.clock = clock;
        this.controlIds = new ControlIdGenerator(clock);
    }

    /**
     * Builds an acknowledgment for {@code original}. Sending and receiving application/facility are
     * swapped, and MSA-2 echoes the original MSH-10. An ERR segment is added for error and reject codes.
     *
     * @param controlIdOverride value for MSA-2, or {@code null} to echo the original control ID
     */
    public String build(ParsedMessage original, AckCode code, String text, String controlIdOverride) {
        Delimiters d = original.delimiters();
        Segment msh = original.msh();
        MessageHeader h = original.header();
        char f = d.field();
        String trigger = h.triggerEvent();
        String msgType = "ACK" + (trigger.isEmpty() ? "" : d.component() + trigger) + d.component() + "ACK";
        String version = h.version().isEmpty() ? "2.5.1" : msh.field(12);
        String processing = h.processingId().isEmpty() ? "P" : msh.field(11);

        StringBuilder sb = new StringBuilder();
        sb.append("MSH").append(f).append(d.encodingCharacters())
                .append(f).append(msh.field(5))
                .append(f).append(msh.field(6))
                .append(f).append(msh.field(3))
                .append(f).append(msh.field(4))
                .append(f).append(Hl7Timestamps.now(clock))
                .append(f)
                .append(f).append(msgType)
                .append(f).append(controlIds.next())
                .append(f).append(processing)
                .append(f).append(version)
                .append(Hl7Text.SEGMENT_SEPARATOR);
        String controlId = controlIdOverride != null ? controlIdOverride : h.controlId();
        sb.append("MSA").append(f).append(code.name()).append(f).append(controlId);
        if (text != null && !text.isEmpty()) {
            sb.append(f).append(text);
        }
        sb.append(Hl7Text.SEGMENT_SEPARATOR);
        if (code.category() != AckCode.Category.ACCEPT) {
            sb.append(errSegment(d, h.version(), text)).append(Hl7Text.SEGMENT_SEPARATOR);
        }
        return sb.toString();
    }

    /**
     * Builds a reject for input that could not be parsed as HL7 at all. The default delimiters and
     * v2.5.1 are used, and MSA-2 is left empty because there is no control ID to echo.
     */
    public String buildForUnparseable(AckCode code, String text) {
        String msh = "MSH|^~\\&|||||" + Hl7Timestamps.now(clock) + "||ACK|" + controlIds.next() + "|P|2.5.1";
        return build(ParsedMessage.parse(msh), code, text, "");
    }

    private static String errSegment(Delimiters d, String version, String text) {
        char f = d.field();
        char c = d.component();
        String description = text == null || text.isEmpty() ? "Application internal error" : text;
        if (isAtLeast25(version)) {
            // ERR|||207^Application internal error^HL70357|E||||<user message>
            return "ERR" + f + f + f + APPLICATION_INTERNAL_ERROR + c + "Application internal error" + c + "HL70357"
                    + f + "E" + f + f + f + f + description;
        }
        // ERR|^^^207&<text>&HL70357
        char s = d.subcomponent();
        return "ERR" + f + c + c + c + APPLICATION_INTERNAL_ERROR + s + description + s + "HL70357";
    }

    static boolean isAtLeast25(String version) {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 2 || (major == 2 && minor >= 5);
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
