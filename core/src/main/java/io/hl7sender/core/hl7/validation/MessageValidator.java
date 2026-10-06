package io.hl7sender.core.hl7.validation;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.Segment;
import io.hl7sender.core.hl7.validation.ValidationIssue.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates an HL7 v2 message in up to three passes.
 *
 * <ol>
 *   <li><b>Structure</b>: checks that every receiver depends on (MSH first, delimiters, message type,
 *       version, segment names). Failures are errors and stop the send.</li>
 *   <li><b>HAPI</b>: parses the message against the declared version's structures and checks data
 *       types. Failures are warnings, because receivers such as Mirth often accept messages that are
 *       not strictly conformant.</li>
 *   <li><b>Profile</b> (optional): usage, cardinality and length rules from a {@link ConformanceProfile}.</li>
 * </ol>
 *
 * <p>{@link ValidationLevel} controls how much runs and what blocks: LENIENT runs only the structure
 * pass, STANDARD reports HAPI and profile findings as warnings, and STRICT makes them errors.
 */
public final class MessageValidator {

    private static final Pattern SEGMENT_NAME = Pattern.compile("[A-Z][A-Z0-9]{2}");

    private final HapiContext hapi;

    public MessageValidator() {
        this.hapi = new DefaultHapiContext();
        this.hapi.setValidationContext(ValidationContextFactory.defaultValidation());
    }

    /** Standard validation: structural errors block, HAPI findings are warnings. */
    public ValidationReport validate(String text) {
        return validate(text, ValidationLevel.STANDARD, null);
    }

    /**
     * Validates at the given level, optionally against a conformance profile.
     *
     * @param profile profile to check against, or {@code null}
     */
    public ValidationReport validate(String text, ValidationLevel level, ConformanceProfile profile) {
        List<ValidationIssue> issues = new ArrayList<>();
        String wire = Hl7Text.normalize(text);
        if (wire.isEmpty()) {
            issues.add(ValidationIssue.error(Source.STRUCTURE, "Message is empty"));
            return new ValidationReport(Optional.empty(), 0, issues);
        }

        ParsedMessage parsed;
        try {
            parsed = ParsedMessage.parse(wire);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            issues.add(ValidationIssue.error(Source.STRUCTURE, e.getMessage()));
            return new ValidationReport(Optional.empty(), Hl7Text.splitSegments(wire).size(), issues);
        }

        checkStructure(parsed, issues);
        boolean structureOk = issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        if (structureOk && level != ValidationLevel.LENIENT) {
            boolean strict = level == ValidationLevel.STRICT;
            Message message = checkWithHapi(wire, issues, strict);
            if (message != null && profile != null) {
                checkProfile(message, profile, issues, strict);
            }
        }
        issues.sort((a, b) -> a.severity().compareTo(b.severity()));
        return new ValidationReport(Optional.of(parsed.header()), parsed.segments().size(), issues);
    }

    private static void checkStructure(ParsedMessage parsed, List<ValidationIssue> issues) {
        Delimiters d = parsed.delimiters();
        String all = "" + d.field() + d.component() + d.repetition() + d.escape() + d.subcomponent();
        if (all.chars().distinct().count() != all.length()) {
            issues.add(ValidationIssue.error(Source.STRUCTURE,
                    "MSH-1/MSH-2 delimiters must all be different, found '" + all + "'"));
        }
        MessageHeader h = parsed.header();
        if (h.messageCode().isEmpty()) {
            issues.add(ValidationIssue.error(Source.STRUCTURE, "MSH-9 (message type) is required"));
        }
        if (h.version().isEmpty()) {
            issues.add(ValidationIssue.error(Source.STRUCTURE, "MSH-12 (version ID) is required"));
        }
        if (h.controlId().isEmpty()) {
            issues.add(ValidationIssue.warning(Source.STRUCTURE,
                    "MSH-10 (message control ID) is empty; ACKs cannot be matched unless it is generated"));
        }
        if (h.processingId().isEmpty()) {
            issues.add(ValidationIssue.warning(Source.STRUCTURE, "MSH-11 (processing ID) is empty"));
        }
        int n = 0;
        for (Segment s : parsed.segments()) {
            n++;
            if (!SEGMENT_NAME.matcher(s.name()).matches()) {
                issues.add(ValidationIssue.error(Source.STRUCTURE,
                        "Segment " + n + " has an invalid name '" + abbreviate(s.name()) + "'"));
            }
        }
        if (parsed.all("MSH").size() > 1) {
            issues.add(ValidationIssue.error(Source.STRUCTURE,
                    "More than one MSH segment: send one message at a time"));
        }
    }

    /** Parses with HAPI, recording problems. Returns the parsed message, or null if HAPI could not parse it. */
    private Message checkWithHapi(String wire, List<ValidationIssue> issues, boolean strict) {
        try {
            PipeParser parser = hapi.getPipeParser();
            synchronized (hapi) {
                return parser.parse(wire);
            }
        } catch (HL7Exception e) {
            issues.add(issue(strict, Source.HAPI, describe(e)));
        } catch (RuntimeException e) {
            issues.add(issue(strict, Source.HAPI, "HAPI could not parse the message: " + e.getMessage()));
        }
        return null;
    }

    private static void checkProfile(Message message, ConformanceProfile profile, List<ValidationIssue> issues,
                                     boolean strict) {
        try {
            for (String problem : profile.check(message)) {
                issues.add(issue(strict, Source.PROFILE, stripExceptionPrefix(problem)));
            }
        } catch (HL7Exception | RuntimeException e) {
            issues.add(issue(strict, Source.PROFILE, "Profile check failed: " + e.getMessage()));
        }
    }

    private static ValidationIssue issue(boolean error, Source source, String message) {
        return error ? ValidationIssue.error(source, message) : ValidationIssue.warning(source, message);
    }

    private static String describe(HL7Exception e) {
        String message = stripExceptionPrefix(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            String causeMessage = stripExceptionPrefix(cause.getMessage());
            if (!message.contains(causeMessage)) {
                message = message + ": " + causeMessage;
            }
        }
        return message;
    }

    /** Removes {@code some.package.FooException: } prefixes that HAPI puts in its messages. */
    private static String stripExceptionPrefix(String message) {
        return message.replaceAll("(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z]\\w*Exception:\\s*", "");
    }

    private static String abbreviate(String s) {
        return s.length() <= 10 ? s : s.substring(0, 10) + "...";
    }
}
