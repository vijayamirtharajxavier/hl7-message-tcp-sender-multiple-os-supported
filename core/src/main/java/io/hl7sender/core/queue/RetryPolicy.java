package io.hl7sender.core.queue;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter: {@code delay(n) = min(maxDelay, baseDelay × 2^(n-1)) × (1 ± jitter)},
 * where {@code n} is the number of attempts made so far.
 *
 * @param maxAttempts total attempts before a message is dead-lettered; {@code 0} means retry forever
 * @param baseDelayMs delay after the first failed attempt
 * @param maxDelayMs  upper bound for any single delay
 * @param jitter      random spread as a fraction, between 0 and 1 (0.2 = ±20%)
 */
public record RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, double jitter) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(10, 2_000, 300_000, 0.2);

    public RetryPolicy {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts cannot be negative");
        }
        if (baseDelayMs < 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("Delays must satisfy 0 <= baseDelay <= maxDelay");
        }
        if (jitter < 0 || jitter > 1 || Double.isNaN(jitter)) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
    }

    public boolean unlimited() {
        return maxAttempts == 0;
    }

    /** True if another attempt is allowed after {@code attemptsMade} attempts. */
    public boolean allowsAnotherAttempt(int attemptsMade) {
        return unlimited() || attemptsMade < maxAttempts;
    }

    /** Delay before the next attempt, after {@code attemptsMade} (≥ 1) failed attempts. */
    public Duration delayAfter(int attemptsMade, RandomGenerator random) {
        int exponent = Math.max(0, Math.min(attemptsMade - 1, 30));
        double raw = Math.min((double) maxDelayMs, baseDelayMs * Math.pow(2, exponent));
        double factor = jitter == 0 ? 1 : 1 + (random.nextDouble() * 2 - 1) * jitter;
        long millis = Math.round(Math.min(maxDelayMs, raw * factor));
        return Duration.ofMillis(Math.max(0, millis));
    }
}
