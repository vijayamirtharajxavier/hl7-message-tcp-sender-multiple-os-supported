package io.hl7sender.core.ack;

import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.Segment;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses acknowledgment messages leniently. Only MSH and MSA are required, so ACKs from Mirth,
 * NiFi, EHRs and hand-written listeners are all accepted. ERR segments are read in both the
 * v2.5+ layout (ERR-2..ERR-8) and the older ERR-1 layout.
 */
public final class AckParser {

    private AckParser() {
    }

    public static ParsedAck parse(String response) throws AckParseException {
        ParsedMessage msg;
        try {
            msg = ParsedMessage.parse(response);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            throw new AckParseException("Response is not an HL7 message: " + e.getMessage());
        }
        Segment msa = msg.first("MSA")
                .orElseThrow(() -> new AckParseException("Response has no MSA segment"));
        String rawCode = msa.field(1);
        AckCode code = AckCode.parse(rawCode)
                .orElseThrow(() -> new AckParseException("Unknown acknowledgment code in MSA-1: '" + rawCode + "'"));

        List<AckError> errors = new ArrayList<>();
        for (Segment err : msg.all("ERR")) {
            errors.add(parseErr(err));
        }
        return new ParsedAck(
                code,
                msa.field(2),
                msa.field(3),
                msg.msh().field(9),
                msg.msh().field(10),
                errors,
                Hl7Text.normalize(response));
    }

    private static AckError parseErr(Segment err) {
        Delimiters d = err.delimiters();
        boolean modern = !err.field(2).isEmpty() || !err.field(3).isEmpty() || !err.field(4).isEmpty();
        if (modern) {
            String location = location(err.component(2, 1), err.component(2, 2), err.component(2, 3));
            String text = firstNonEmpty(err.field(8), err.component(3, 2), err.field(7), err.component(5, 2));
            String code = firstNonEmpty(err.component(3, 1), err.component(5, 1));
            return new AckError(location, code, text, err.component(4, 1), err.raw());
        }
        // Pre-2.5: ERR-1 = segment^sequence^field^code&text&system
        String codedError = err.component(1, 4);
        List<String> sub = Segment.split(codedError, d.subcomponent());
        String location = location(err.component(1, 1), err.component(1, 2), err.component(1, 3));
        return new AckError(location, sub.get(0), sub.size() > 1 ? sub.get(1) : "", "", err.raw());
    }

    private static String location(String segment, String sequence, String field) {
        if (segment.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(segment);
        if (!field.isEmpty()) {
            sb.append('-').append(field);
        }
        if (!sequence.isEmpty() && !"1".equals(sequence)) {
            sb.append(" (occurrence ").append(sequence).append(')');
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
