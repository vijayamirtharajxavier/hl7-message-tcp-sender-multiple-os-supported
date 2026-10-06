package io.hl7sender.core.listener;

import io.hl7sender.core.hl7.FieldPath;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How the responder answers messages that match: a condition on the message type and optionally one field, and
 * the response to give. Rules are checked in order and the first match wins; messages that match no rule get the
 * listener's default response.
 *
 * @param name           shown in the received-messages list, e.g. {@code Orders get results}
 * @param messageType    MSH-9 pattern with {@code *} and {@code ?} wildcards: {@code ORM^O01}, {@code ADT^A0*}, or
 *                       just {@code ADT} for every ADT event; empty matches every message
 * @param field          optional field to test, e.g. {@code PID-3.1} or {@code MSH-4} (see {@link FieldPath});
 *                       empty for none
 * @param pattern        regular expression the whole field value must match; empty means the field must not be
 *                       empty
 * @param mode           the response
 * @param delayMs        wait this long before responding
 * @param responseText   MSA-3 text; for errors also the ERR text
 * @param customResponse the whole response for {@link ResponseMode#CUSTOM}, a template that may use
 *                       {@code ${IN:MSH-10}} and the other template variables
 * @param followUp       a message to send back after responding, or {@code null}
 */
public record ResponseRule(
        String name,
        String messageType,
        String field,
        String pattern,
        ResponseMode mode,
        int delayMs,
        String responseText,
        String customResponse,
        FollowUp followUp) {

    /**
     * A message sent back to the other system after the response: for example an ORU^R01 result for each
     * ORM^O01 order. It is queued to a destination, so it is retried like any other message.
     *
     * @param destination name of the destination to queue it to
     * @param delayMs     wait this long after responding
     * @param template    the message, a template that may use {@code ${IN:path}} to copy values from the message
     *                    being answered
     */
    public record FollowUp(String destination, int delayMs, String template) {

        public FollowUp {
            destination = destination == null ? "" : destination.trim();
            template = template == null ? "" : template;
            if (destination.isEmpty()) {
                throw new IllegalArgumentException("Choose the destination for the follow-up message");
            }
            if (template.isBlank()) {
                throw new IllegalArgumentException("The follow-up message is empty");
            }
            if (delayMs < 0) {
                throw new IllegalArgumentException("The follow-up delay cannot be negative");
            }
        }
    }

    public ResponseRule {
        name = name == null || name.isBlank() ? "Rule" : name.trim();
        messageType = messageType == null ? "" : messageType.trim();
        field = field == null ? "" : field.trim();
        pattern = pattern == null ? "" : pattern;
        mode = mode == null ? ResponseMode.ACCEPT : mode;
        responseText = responseText == null ? "" : responseText;
        customResponse = customResponse == null ? "" : customResponse;
        if (delayMs < 0) {
            throw new IllegalArgumentException("The delay cannot be negative");
        }
        if (!field.isEmpty()) {
            FieldPath.parse(field);
        }
        if (!pattern.isEmpty()) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Rule '" + name + "': invalid pattern: " + e.getDescription(), e);
            }
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Rule '" + name + "': a pattern needs a field to test");
            }
        }
        if (mode == ResponseMode.CUSTOM && customResponse.isBlank()) {
            throw new IllegalArgumentException("Rule '" + name + "': write the custom response");
        }
    }

    /** A rule that answers every message of {@code messageType} with {@code mode}. */
    public static ResponseRule of(String name, String messageType, ResponseMode mode) {
        return new ResponseRule(name, messageType, "", "", mode, 0, "", "", null);
    }

    public ResponseRule withField(String fieldPath, String regex) {
        return new ResponseRule(name, messageType, fieldPath, regex, mode, delayMs, responseText, customResponse,
                followUp);
    }

    public ResponseRule withDelay(int ms) {
        return new ResponseRule(name, messageType, field, pattern, mode, ms, responseText, customResponse, followUp);
    }

    public ResponseRule withResponseText(String text) {
        return new ResponseRule(name, messageType, field, pattern, mode, delayMs, text, customResponse, followUp);
    }

    public ResponseRule withCustomResponse(String template) {
        return new ResponseRule(name, messageType, field, pattern, ResponseMode.CUSTOM, delayMs, responseText,
                template, followUp);
    }

    public ResponseRule withFollowUp(FollowUp f) {
        return new ResponseRule(name, messageType, field, pattern, mode, delayMs, responseText, customResponse, f);
    }

    /** A compiled rule, ready to test messages. */
    Compiled compile() {
        return new Compiled(this, messageType.isEmpty() ? null : glob(messageType),
                field.isEmpty() ? null : FieldPath.parse(field), pattern.isEmpty() ? null : Pattern.compile(pattern));
    }

    private static Pattern glob(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (!text.contains("^")) {
            // "ADT" means every ADT event.
            sb.append("(\\^.*)?");
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** One line for lists, e.g. {@code ORM^O01, PID-3.1 ~ 9.* -> Reject (AR / CR), then ORU to Mirth}. */
    public String describe() {
        StringBuilder sb = new StringBuilder(messageType.isEmpty() ? "any message" : messageType);
        if (!field.isEmpty()) {
            sb.append(", ").append(field).append(pattern.isEmpty() ? " present" : " ~ " + pattern);
        }
        sb.append(" -> ").append(mode.label());
        if (delayMs > 0) {
            sb.append(" after ").append(delayMs).append(" ms");
        }
        if (followUp != null) {
            sb.append(", then a message to ").append(followUp.destination());
        }
        return sb.toString();
    }

    /** A rule with its patterns compiled. */
    record Compiled(ResponseRule rule, Pattern type, FieldPath fieldPath, Pattern value) {

        public boolean matches(ParsedMessage message) {
            if (type != null) {
                MessageHeader h = message.header();
                if (!type.matcher(h.messageType().toUpperCase(Locale.ROOT)).matches()) {
                    return false;
                }
            }
            if (fieldPath != null) {
                String v = fieldPath.valueIn(message);
                return value == null ? !v.isEmpty() : value.matcher(v).matches();
            }
            return true;
        }
    }
}
