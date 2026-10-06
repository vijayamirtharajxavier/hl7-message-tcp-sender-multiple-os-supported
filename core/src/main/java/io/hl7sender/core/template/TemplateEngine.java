package io.hl7sender.core.template;

import io.hl7sender.core.hl7.ControlIdGenerator;
import io.hl7sender.core.hl7.FieldPath;
import io.hl7sender.core.hl7.ParsedMessage;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${VARIABLE}} placeholders in message templates, so one template can produce many
 * realistic, unique messages.
 *
 * <table>
 *   <caption>Built-in variables</caption>
 *   <tr><td>{@code ${NOW}} / {@code ${NOW:pattern}}</td><td>current time, {@code yyyyMMddHHmmss} or a
 *       {@link DateTimeFormatter} pattern</td></tr>
 *   <tr><td>{@code ${TODAY}}</td><td>{@code yyyyMMdd}</td></tr>
 *   <tr><td>{@code ${SEQ}} / {@code ${SEQ:6}}</td><td>sequence number, optionally zero-padded</td></tr>
 *   <tr><td>{@code ${UUID}}</td><td>random UUID</td></tr>
 *   <tr><td>{@code ${CONTROL_ID}}</td><td>unique 20-character control ID</td></tr>
 *   <tr><td>{@code ${RANDOM:n}}</td><td>n random digits</td></tr>
 *   <tr><td>{@code ${RANDOM_MRN}}</td><td>8-digit medical record number</td></tr>
 *   <tr><td>{@code ${RANDOM_FIRST_NAME}}, {@code ${RANDOM_LAST_NAME}}</td><td>fictitious names</td></tr>
 *   <tr><td>{@code ${RANDOM_DOB}}</td><td>date of birth {@code yyyyMMdd}, 1930–2020</td></tr>
 *   <tr><td>{@code ${RANDOM_SEX}}</td><td>{@code F} or {@code M}</td></tr>
 *   <tr><td>{@code ${IN:PID-3.1}}</td><td>a value from the message being answered (responder follow-ups and
 *       custom responses only); see {@link FieldPath} for the syntax</td></tr>
 * </table>
 *
 * <p>Each variable (with the same argument) has <b>one value per message</b>. For example, the same
 * MRN used in PID-3 and in PV1-19 is kept consistent within a message, and new values are generated
 * for the next message. User-defined variables can be supplied per call. Write {@code $${} for a
 * literal {@code ${}. Unknown variables are left unchanged and reported.
 *
 * <p>Thread-safe.
 */
public final class TemplateEngine {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\$\\{|\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> FIRST_NAMES = List.of("ALEX", "JORDAN", "TAYLOR", "MORGAN", "CASEY",
            "RILEY", "JAMIE", "AVERY", "PARKER", "QUINN", "ROWAN", "SAGE", "DAKOTA", "EMERSON", "HAYDEN");
    private static final List<String> LAST_NAMES = List.of("TESTPATIENT", "SAMPLE", "DEMO", "EXAMPLE",
            "FICTIONAL", "PLACEHOLDER", "MOCKINGTON", "TESTWELL", "DUMMYSON", "SYNTHETIC");

    /**
     * Result of an expansion.
     *
     * @param text      the expanded text
     * @param unknown   variable names that were not recognised (left unchanged in the text)
     */
    public record Expansion(String text, Set<String> unknown) {
    }

    private final Clock clock;
    private final RandomGenerator random;
    private final ControlIdGenerator controlIds;
    private final AtomicLong sequence = new AtomicLong();

    public TemplateEngine() {
        this(Clock.systemDefaultZone(), RandomGenerator.getDefault());
    }

    public TemplateEngine(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
        this.controlIds = new ControlIdGenerator(clock);
    }

    /** True if {@code text} contains at least one {@code ${...}} placeholder. */
    public static boolean hasVariables(String text) {
        return text != null && text.contains("${");
    }

    /** Expands one message, advancing {@code ${SEQ}}. */
    public Expansion expand(String template, Map<String, String> userVariables) {
        return expand(template, userVariables, null, true);
    }

    public Expansion expand(String template) {
        return expand(template, Map.of(), null, true);
    }

    /**
     * Expands one message that answers {@code inbound}: {@code ${IN:path}} takes values from it, e.g.
     * {@code ${IN:PID-3.1}} or {@code ${IN:MSH-10}}.
     */
    public Expansion expand(String template, ParsedMessage inbound) {
        return expand(template, Map.of(), inbound, true);
    }

    /** Expands for display/validation without advancing {@code ${SEQ}}. */
    public Expansion preview(String template, Map<String, String> userVariables) {
        return expand(template, userVariables, null, false);
    }

    /** Restarts {@code ${SEQ}} so the next message gets 1. */
    public void resetSequence() {
        sequence.set(0);
    }

    private Expansion expand(String template, Map<String, String> user, ParsedMessage inbound, boolean advance) {
        if (template == null) {
            return new Expansion("", Set.of());
        }
        Map<String, String> values = new HashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now(clock);
        long[] seq = {-1};
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (m.find()) {
            if (m.group().equals("$${")) {
                m.appendReplacement(out, Matcher.quoteReplacement("${"));
                continue;
            }
            String name = m.group(1);
            String arg = m.group(2);
            String key = name + (arg == null ? "" : ":" + arg);
            String value = values.get(key);
            if (value == null) {
                value = resolve(name.toUpperCase(Locale.ROOT), arg, user.get(name), inbound, now, seq, advance);
                if (value == null) {
                    unknown.add(name);
                    value = m.group();
                } else {
                    values.put(key, value);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return new Expansion(out.toString(), Set.copyOf(unknown));
    }

    private String resolve(String name, String arg, String userValue, ParsedMessage inbound, LocalDateTime now,
                           long[] seq, boolean advance) {
        if (userValue != null) {
            return userValue;
        }
        return switch (name) {
            case "IN" -> inbound == null || arg == null || !FieldPath.isValid(arg) ? null
                    : FieldPath.parse(arg).valueIn(inbound);
            case "NOW" -> arg == null ? TIMESTAMP.format(now) : format(now, arg);
            case "TODAY" -> DATE.format(now);
            case "SEQ" -> {
                if (seq[0] < 0) {
                    seq[0] = advance ? sequence.incrementAndGet() : sequence.get() + 1;
                }
                yield arg == null ? String.valueOf(seq[0]) : pad(seq[0], arg);
            }
            case "UUID" -> new UUID(random.nextLong(), random.nextLong()).toString();
            case "CONTROL_ID" -> controlIds.next();
            case "RANDOM" -> digits(parseWidth(arg, 6));
            case "RANDOM_MRN" -> digits(8);
            case "RANDOM_FIRST_NAME" -> FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
            case "RANDOM_LAST_NAME" -> LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
            case "RANDOM_DOB" -> DATE.format(LocalDate.of(1930, 1, 1).plusDays(random.nextInt(32_870)));
            case "RANDOM_SEX" -> random.nextBoolean() ? "F" : "M";
            default -> null;
        };
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    private static String format(LocalDateTime now, String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern).format(now);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pad(long value, String width) {
        int w = parseWidth(width, 0);
        String s = String.valueOf(value);
        return s.length() >= w ? s : "0".repeat(w - s.length()) + s;
    }

    private static int parseWidth(String arg, int fallback) {
        if (arg == null) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(arg.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Names of all built-in variables, for help text and editor completion. */
    public static List<String> builtInVariables() {
        return new ArrayList<>(List.of("NOW", "NOW:yyyyMMdd", "TODAY", "SEQ", "SEQ:6", "UUID", "CONTROL_ID",
                "RANDOM:6", "RANDOM_MRN", "RANDOM_FIRST_NAME", "RANDOM_LAST_NAME", "RANDOM_DOB", "RANDOM_SEX",
                "IN:PID-3.1"));
    }
}
