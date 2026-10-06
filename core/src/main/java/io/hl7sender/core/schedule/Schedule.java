package io.hl7sender.core.schedule;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Queues a message (or a template, or a batch of messages) to a destination on a cron schedule, for example a
 * test ADT^A08 every weekday at 07:00, or 50 generated orders every hour.
 *
 * @param id            database ID, or 0 for a schedule not yet saved
 * @param name          unique display name
 * @param cron          when to run, see {@link CronExpression}
 * @param zone          time zone the cron expression is evaluated in, e.g. {@code Europe/London}
 * @param destinationId where the messages are queued
 * @param message       the message(s) to queue; may hold several messages or a batch, and {@code ${...}} template
 *                      variables, which get new values on every run
 * @param count         how many copies to queue per run (each expanded separately)
 * @param enabled       false to keep the schedule without running it
 * @param lastRunAt     when it last ran
 * @param lastResult    what happened on the last run, e.g. {@code 3 queued (batch B20260926...)}
 */
public record Schedule(
        long id,
        String name,
        String cron,
        String zone,
        long destinationId,
        String message,
        int count,
        boolean enabled,
        Optional<Instant> lastRunAt,
        String lastResult) {

    /** Most copies per run, to protect the queue from a typo. */
    public static final int MAX_COUNT = 10_000;

    public Schedule {
        name = name == null ? "" : name.trim();
        cron = cron == null ? "" : cron.trim();
        zone = zone == null || zone.isBlank() ? ZoneId.systemDefault().getId() : zone.trim();
        message = message == null ? "" : message;
        lastRunAt = lastRunAt == null ? Optional.empty() : lastRunAt;
        lastResult = lastResult == null ? "" : lastResult;
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give the schedule a name");
        }
        CronExpression.parse(cron);
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone '" + zone + "'", e);
        }
        if (destinationId <= 0) {
            throw new IllegalArgumentException("Choose a destination");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("The message is empty");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("Messages per run must be between 1 and " + MAX_COUNT);
        }
    }

    /** A new, enabled schedule in the computer's time zone. */
    public static Schedule of(String name, String cron, long destinationId, String message) {
        return new Schedule(0, name, cron, null, destinationId, message, 1, true, Optional.empty(), "");
    }

    public Schedule withId(long newId) {
        return new Schedule(newId, name, cron, zone, destinationId, message, count, enabled, lastRunAt, lastResult);
    }

    public Schedule withCount(int n) {
        return new Schedule(id, name, cron, zone, destinationId, message, n, enabled, lastRunAt, lastResult);
    }

    public Schedule withEnabled(boolean on) {
        return new Schedule(id, name, cron, zone, destinationId, message, count, on, lastRunAt, lastResult);
    }

    public Schedule withZone(String zoneId) {
        return new Schedule(id, name, cron, zoneId, destinationId, message, count, enabled, lastRunAt, lastResult);
    }

    public CronExpression expression() {
        return CronExpression.parse(cron);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /** The next run strictly after {@code after}, if the schedule is enabled. */
    public Optional<Instant> nextRunAfter(Instant after) {
        if (!enabled) {
            return Optional.empty();
        }
        return expression().next(ZonedDateTime.ofInstant(after, zoneId())).map(ZonedDateTime::toInstant);
    }

    /** True if the settings that decide when and what to run are the same (ignores the run history). */
    public boolean sameDefinition(Schedule o) {
        return o != null && id == o.id && name.equals(o.name) && cron.equals(o.cron) && zone.equals(o.zone)
                && destinationId == o.destinationId && message.equals(o.message) && count == o.count
                && enabled == o.enabled;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schedule s && sameDefinition(s) && lastRunAt.equals(s.lastRunAt)
                && lastResult.equals(s.lastResult);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, cron, zone, destinationId, message, count, enabled, lastRunAt, lastResult);
    }
}
