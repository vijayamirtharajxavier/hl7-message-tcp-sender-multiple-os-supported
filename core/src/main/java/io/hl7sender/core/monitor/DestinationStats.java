package io.hl7sender.core.monitor;

import io.hl7sender.core.send.SendOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Delivery statistics for one destination over a time window, from the attempt history.
 *
 * @param destinationId  destination
 * @param windowStart    start of the window (inclusive)
 * @param windowEnd      end of the window
 * @param outcomes       number of finished attempts per outcome
 * @param avgLatencyMs   mean round-trip time (send to ACK) of attempts that got a response, or -1 if none
 * @param maxLatencyMs   longest round-trip time, or -1 if none
 * @param acceptedPerMinute accepted messages in each minute of the window, oldest first
 */
public record DestinationStats(long destinationId, Instant windowStart, Instant windowEnd,
                               Map<SendOutcome, Integer> outcomes, long avgLatencyMs, long maxLatencyMs,
                               List<Integer> acceptedPerMinute) {

    public DestinationStats {
        EnumMap<SendOutcome, Integer> copy = new EnumMap<>(SendOutcome.class);
        copy.putAll(outcomes);
        outcomes = Collections.unmodifiableMap(copy);
        acceptedPerMinute = List.copyOf(acceptedPerMinute);
    }

    public int count(SendOutcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    /** All finished attempts. */
    public int attempts() {
        return outcomes.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Delivered: AA/CA, or sent in no-ACK mode. */
    public int accepted() {
        return count(SendOutcome.ACCEPTED) + count(SendOutcome.SENT_NO_ACK);
    }

    /** Negative acknowledgments: AE/CE and AR/CR. */
    public int nacks() {
        return count(SendOutcome.APPLICATION_ERROR) + count(SendOutcome.APPLICATION_REJECT);
    }

    /** Attempts with no usable ACK: timeouts, connection and protocol failures, wrong or invalid ACKs. */
    public int failures() {
        return attempts() - accepted() - nacks();
    }

    /** Share of attempts that were accepted, 0..1, or -1 if there were none. */
    public double acceptRate() {
        int n = attempts();
        return n == 0 ? -1 : (double) accepted() / n;
    }

    /** Share of attempts answered with AE/AR (or CE/CR), 0..1, or -1 if there were none. */
    public double nackRate() {
        int n = attempts();
        return n == 0 ? -1 : (double) nacks() / n;
    }

    /** Accepted messages per minute, averaged over the window. */
    public double throughputPerMinute() {
        double minutes = Math.max(1, Duration.between(windowStart, windowEnd).toSeconds()) / 60.0;
        return accepted() / minutes;
    }
}
