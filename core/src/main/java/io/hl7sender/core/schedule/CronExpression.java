package io.hl7sender.core.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A standard five-field cron expression: {@code minute hour day-of-month month day-of-week}.
 *
 * <ul>
 *   <li>Each field is {@code *}, a number, a range {@code 1-5}, a list {@code 1,15,30}, or a step {@code *}{@code /15}
 *       or {@code 8-18/2}.</li>
 *   <li>Months may be written {@code JAN}-{@code DEC} and days {@code SUN}-{@code SAT}; Sunday is 0 or 7.</li>
 *   <li>{@code @hourly}, {@code @daily} (or {@code @midnight}), {@code @weekly}, {@code @monthly} and
 *       {@code @yearly} (or {@code @annually}) are accepted.</li>
 *   <li>As in cron, if both day-of-month and day-of-week are restricted, a day matching either one fires.</li>
 * </ul>
 *
 * <p>Times are evaluated in a time zone. When clocks go forward, a time that does not exist is skipped; when they go
 * back, a repeated time fires once.
 */
public final class CronExpression {

    private static final Map<String, String> MACROS = Map.of(
            "@hourly", "0 * * * *",
            "@daily", "0 0 * * *",
            "@midnight", "0 0 * * *",
            "@weekly", "0 0 * * 0",
            "@monthly", "0 0 1 * *",
            "@yearly", "0 0 1 1 *",
            "@annually", "0 0 1 1 *");
    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG",
            "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    /** Longest search: every schedule fires at least once in a four-year cycle (29 February on a given weekday
     *  can take up to 28 years, which is also covered). */
    private static final int MAX_YEARS = 30;

    private final String text;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;

    private CronExpression(String text, BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months,
                           BitSet daysOfWeek, boolean anyDayOfMonth, boolean anyDayOfWeek) {
        this.text = text;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.anyDayOfMonth = anyDayOfMonth;
        this.anyDayOfWeek = anyDayOfWeek;
    }

    /**
     * Parses an expression.
     *
     * @throws IllegalArgumentException with the problem, if it is not valid
     */
    public static CronExpression parse(String expression) {
        String original = expression == null ? "" : expression.trim();
        String expanded = MACROS.getOrDefault(original.toLowerCase(Locale.ROOT), original);
        String[] f = expanded.split("\\s+");
        if (f.length != 5 || expanded.isEmpty()) {
            throw new IllegalArgumentException("'" + original + "' is not a cron expression: give five fields "
                    + "(minute hour day-of-month month day-of-week), e.g. '0 7 * * 1-5', or @daily");
        }
        BitSet dow = field(f[4], "day of week", 0, 7, DAYS);
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        CronExpression cron = new CronExpression(original,
                field(f[0], "minute", 0, 59, null),
                field(f[1], "hour", 0, 23, null),
                field(f[2], "day of month", 1, 31, null),
                field(f[3], "month", 1, 12, MONTHS),
                dow,
                f[2].equals("*") || f[2].equals("?"),
                f[4].equals("*") || f[4].equals("?"));
        if (cron.next(ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))).isEmpty()) {
            throw new IllegalArgumentException("'" + original + "' never fires (no such date)");
        }
        return cron;
    }

    /** True if {@code expression} is valid. */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static BitSet field(String text, String name, int min, int max, List<String> names) {
        BitSet bits = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Empty value in the " + name + " field '" + text + "'");
            }
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name, 1, max, null);
            }
            int from;
            int to;
            if (range.equals("*") || range.equals("?")) {
                from = min;
                to = max;
            } else {
                int dash = range.indexOf('-');
                if (dash > 0) {
                    from = number(range.substring(0, dash), name, min, max, names);
                    to = number(range.substring(dash + 1), name, min, max, names);
                    if (to < from) {
                        throw new IllegalArgumentException("Range " + range + " in the " + name
                                + " field goes backwards");
                    }
                } else {
                    from = number(range, name, min, max, names);
                    to = slash >= 0 ? max : from;
                }
            }
            for (int v = from; v <= to; v += step) {
                bits.set(v);
            }
        }
        return bits;
    }

    private static int number(String text, String name, int min, int max, List<String> names) {
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (names != null) {
            int i = names.indexOf(t);
            if (i >= 0) {
                return names == MONTHS ? i + 1 : i;
            }
        }
        try {
            int v = Integer.parseInt(t);
            if (v < min || v > max) {
                throw new IllegalArgumentException("'" + text + "' is outside " + min + "-" + max + " in the " + name
                        + " field");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a valid " + name, e);
        }
    }

    /** The first time strictly after {@code after} (at whole minutes) that matches, or empty if there is none. */
    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        ZoneId zone = after.getZone();
        LocalDateTime t = after.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate limit = t.toLocalDate().plusYears(MAX_YEARS);
        while (!t.toLocalDate().isAfter(limit)) {
            if (!months.get(t.getMonthValue())) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
                continue;
            }
            if (!dayMatches(t.toLocalDate())) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            if (!hours.get(t.getHour())) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                continue;
            }
            if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1);
                continue;
            }
            // Map the local time to an instant. In a spring-forward gap the local time does not exist: skip it.
            List<java.time.ZoneOffset> offsets = zone.getRules().getValidOffsets(t);
            if (offsets.isEmpty()) {
                t = t.plusMinutes(1);
                continue;
            }
            ZonedDateTime candidate = ZonedDateTime.ofLocal(t, zone, offsets.get(0));
            if (candidate.isAfter(after)) {
                return Optional.of(candidate);
            }
            // In an autumn overlap the earlier offset comes first; a time already passed is not repeated.
            t = t.plusMinutes(1);
        }
        return Optional.empty();
    }

    /** The next {@code n} times after {@code after}, for previews. */
    public List<ZonedDateTime> nextTimes(ZonedDateTime after, int n) {
        List<ZonedDateTime> out = new ArrayList<>(n);
        ZonedDateTime t = after;
        for (int i = 0; i < n; i++) {
            Optional<ZonedDateTime> next = next(t);
            if (next.isEmpty()) {
                break;
            }
            out.add(next.get());
            t = next.get();
        }
        return out;
    }

    private boolean dayMatches(LocalDate d) {
        boolean dom = daysOfMonth.get(d.getDayOfMonth());
        boolean dow = daysOfWeek.get(d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : d.getDayOfWeek().getValue());
        if (anyDayOfMonth && anyDayOfWeek) {
            return true;
        }
        if (anyDayOfMonth) {
            return dow;
        }
        if (anyDayOfWeek) {
            return dom;
        }
        return dom || dow;
    }

    @Override
    public String toString() {
        return text;
    }
}
