package io.hl7sender.core.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes responder rules as JSON: an array of {@link ResponseRule} objects. The same file works with
 * {@code hl7send listen --rules} and the Test Listener tab's Import/Export.
 *
 * <pre>
 * [ { "name": "Orders get results", "messageType": "ORM^O01", "mode": "ACCEPT",
 *     "followUp": { "destination": "Mirth", "delayMs": 2000,
 *                   "template": "MSH|^~\\&amp;|LAB|...|ORU^R01|${CONTROL_ID}|P|2.5.1\rPID|1||${IN:PID-3.1}..." } },
 *   { "name": "Reject unknown facility", "messageType": "ADT", "field": "MSH-4",
 *     "pattern": "(?!HOSP).*", "mode": "REJECT", "responseText": "Unknown facility" } ]
 * </pre>
 */
public final class ResponseRules {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<ResponseRule>> LIST = new TypeReference<>() {
    };

    private ResponseRules() {
    }

    /** Parses rules. Invalid rules are reported with the rule's problem, e.g. a bad pattern. */
    public static List<ResponseRule> parse(String json) throws IOException {
        try {
            List<ResponseRule> rules = JSON.readValue(json, LIST);
            return rules == null ? List.of() : List.copyOf(rules);
        } catch (ValueInstantiationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Invalid rule: " + cause.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new IOException("Not a rules file: " + e.getOriginalMessage(), e);
        }
    }

    public static List<ResponseRule> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static String toJson(List<ResponseRule> rules) {
        try {
            return JSON.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path file, List<ResponseRule> rules) throws IOException {
        Files.writeString(file, toJson(rules), StandardCharsets.UTF_8);
    }
}
