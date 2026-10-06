package io.hl7sender.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static ZonedDateTime at(String local, ZoneId zone) {
        return java.time.LocalDateTime.parse(local).atZone(zone);
    }

    private static String next(String cron, String after) {
        return CronExpression.parse(cron).next(at(after, UTC)).orElseThrow().toLocalDateTime().toString();
    }

    @Test
    void everyMinuteAndSimpleTimes() {
        assertThat(next("* * * * *", "2026-01-01T10:15:30")).isEqualTo("2026-01-01T10:16");
        assertThat(next("0 7 * * *", "2026-01-01T06:59")).isEqualTo("2026-01-01T07:00");
        assertThat(next("0 7 * * *", "2026-01-01T07:00")).isEqualTo("2026-01-02T07:00");
        assertThat(next("30 23 31 12 *", "2026-06-01T00:00")).isEqualTo("2026-12-31T23:30");
    }

    @Test
    void listsRangesStepsAndNames() {
        assertThat(next("*/15 * * * *", "2026-01-01T10:16")).isEqualTo("2026-01-01T10:30");
        assertThat(next("0 8-18/2 * * *", "2026-01-01T09:00")).isEqualTo("2026-01-01T10:00");
        assertThat(next("0 8-18/2 * * *", "2026-01-01T18:00")).isEqualTo("2026-01-02T08:00");
        assertThat(next("5,35 * * * *", "2026-01-01T10:06")).isEqualTo("2026-01-01T10:35");
        assertThat(next("0 9 * * MON-FRI", "2026-01-02T10:00")).isEqualTo("2026-01-05T09:00"); // Fri -> Mon
        assertThat(next("0 0 1 JAN,jul *", "2026-02-01T00:00")).isEqualTo("2026-07-01T00:00");
        assertThat(next("0 12 * * 7", "2026-01-01T00:00")).isEqualTo("2026-01-04T12:00"); // 7 = Sunday
        assertThat(next("0 12 * * 0", "2026-01-01T00:00")).isEqualTo("2026-01-04T12:00");
        assertThat(next("10/20 * * * *", "2026-01-01T10:11")).isEqualTo("2026-01-01T10:30");
    }

    @Test
    void macros() {
        assertThat(next("@hourly", "2026-01-01T10:15")).isEqualTo("2026-01-01T11:00");
        assertThat(next("@daily", "2026-01-01T10:15")).isEqualTo("2026-01-02T00:00");
        assertThat(next("@weekly", "2026-01-01T10:15")).isEqualTo("2026-01-04T00:00");
        assertThat(next("@monthly", "2026-01-15T10:15")).isEqualTo("2026-02-01T00:00");
        assertThat(next("@yearly", "2026-01-15T10:15")).isEqualTo("2027-01-01T00:00");
    }

    @Test
    void dayOfMonthOrDayOfWeekWhenBothAreRestricted() {
        // The 13th, or any Friday: after Thu 1 Jan 2026 comes Fri 2 Jan.
        assertThat(next("0 0 13 * 5", "2026-01-01T00:00")).isEqualTo("2026-01-02T00:00");
        // Only the day of month restricted.
        assertThat(next("0 0 13 * *", "2026-01-01T00:00")).isEqualTo("2026-01-13T00:00");
    }

    @Test
    void rareDatesAreFound() {
        assertThat(next("0 0 29 2 *", "2026-03-01T00:00")).isEqualTo("2028-02-29T00:00");
        assertThat(next("0 0 31 * *", "2026-04-01T00:00")).isEqualTo("2026-05-31T00:00");
    }

    @Test
    void daylightSavingGapIsSkippedAndOverlapFiresOnce() {
        CronExpression cron = CronExpression.parse("30 1 * * *");
        // 29 March 2026: London clocks go from 01:00 to 02:00, so 01:30 does not exist that day.
        ZonedDateTime n = cron.next(at("2026-03-28T12:00", LONDON)).orElseThrow();
        assertThat(n.toLocalDateTime().toString()).isEqualTo("2026-03-30T01:30");
        // 25 October 2026: 01:00-02:00 happens twice; 01:30 fires once.
        List<ZonedDateTime> times = cron.nextTimes(at("2026-10-24T12:00", LONDON), 3);
        assertThat(times).extracting(t -> t.toLocalDateTime().toString())
                .containsExactly("2026-10-25T01:30", "2026-10-26T01:30", "2026-10-27T01:30");
        // Every 30 minutes through the repeated hour: 01:00, 01:30 once each, then 02:00.
        List<ZonedDateTime> half = CronExpression.parse("*/30 1-2 * * *").nextTimes(at("2026-10-25T00:45", LONDON), 4);
        assertThat(half).extracting(t -> t.toLocalDateTime().toString())
                .containsExactly("2026-10-25T01:00", "2026-10-25T01:30", "2026-10-25T02:00", "2026-10-25T02:30");
    }

    @Test
    void invalidExpressionsAreExplained() {
        assertThatThrownBy(() -> CronExpression.parse("0 7 * *")).hasMessageContaining("five fields");
        assertThatThrownBy(() -> CronExpression.parse("")).hasMessageContaining("five fields");
        assertThatThrownBy(() -> CronExpression.parse("60 * * * *")).hasMessageContaining("outside 0-59");
        assertThatThrownBy(() -> CronExpression.parse("0 25 * * *")).hasMessageContaining("hour");
        assertThatThrownBy(() -> CronExpression.parse("0 0 * FOO *")).hasMessageContaining("not a valid month");
        assertThatThrownBy(() -> CronExpression.parse("0 0 5-1 * *")).hasMessageContaining("backwards");
        assertThatThrownBy(() -> CronExpression.parse("0 0 30 2 *")).hasMessageContaining("never fires");
        assertThatThrownBy(() -> CronExpression.parse("*/0 * * * *")).hasMessageContaining("outside");
        assertThat(CronExpression.isValid("0 7 * * 1-5")).isTrue();
        assertThat(CronExpression.parse("  @daily ").toString()).isEqualTo("@daily");
    }
}
