package io.hl7sender.core.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.queue.AckPolicy.Action;
import io.hl7sender.core.send.SendOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolicyTest {

    @Test
    void backoffDoublesAndCaps() {
        RetryPolicy p = new RetryPolicy(10, 1_000, 10_000, 0);
        Random r = new Random(1);
        assertThat(p.delayAfter(1, r)).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.delayAfter(2, r)).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.delayAfter(3, r)).isEqualTo(Duration.ofSeconds(4));
        assertThat(p.delayAfter(4, r)).isEqualTo(Duration.ofSeconds(8));
        assertThat(p.delayAfter(5, r)).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.delayAfter(500, r)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterStaysWithinBoundsAndNeverExceedsMax() {
        RetryPolicy p = new RetryPolicy(0, 1_000, 3_000, 0.2);
        Random r = new Random(42);
        for (int i = 0; i < 1000; i++) {
            assertThat(p.delayAfter(1, r).toMillis()).isBetween(800L, 1200L);
            assertThat(p.delayAfter(9, r).toMillis()).isBetween(2400L, 3000L);
        }
    }

    @Test
    void attemptLimits() {
        assertThat(new RetryPolicy(3, 0, 0, 0).allowsAnotherAttempt(2)).isTrue();
        assertThat(new RetryPolicy(3, 0, 0, 0).allowsAnotherAttempt(3)).isFalse();
        assertThat(new RetryPolicy(0, 0, 0, 0).allowsAnotherAttempt(1_000_000)).isTrue();
        assertThatThrownBy(() -> new RetryPolicy(1, 10, 5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 1, 5, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ackPolicyDefaults() {
        AckPolicy p = AckPolicy.DEFAULT;
        assertThat(p.actionFor(SendOutcome.ACCEPTED)).isEqualTo(Action.COMPLETE);
        assertThat(p.actionFor(SendOutcome.SENT_NO_ACK)).isEqualTo(Action.COMPLETE);
        assertThat(p.actionFor(SendOutcome.APPLICATION_ERROR)).isEqualTo(Action.DEAD_LETTER);
        assertThat(p.actionFor(SendOutcome.VALIDATION_FAILED)).isEqualTo(Action.DEAD_LETTER);
        assertThat(p.actionFor(SendOutcome.APPLICATION_REJECT)).isEqualTo(Action.RETRY);
        assertThat(p.actionFor(SendOutcome.COMMIT_REJECT)).isEqualTo(Action.DEAD_LETTER);
        assertThat(p.actionFor(SendOutcome.COMMIT_ERROR)).isEqualTo(Action.RETRY);
        assertThat(p.actionFor(SendOutcome.ACK_TIMEOUT)).isEqualTo(Action.RETRY);
        assertThat(p.actionFor(SendOutcome.CONNECTION_FAILED)).isEqualTo(Action.RETRY);
    }

    @Test
    void ackPolicyOverridesRoundTrip() {
        AckPolicy p = AckPolicy.DEFAULT
                .with(SendOutcome.APPLICATION_REJECT, Action.DEAD_LETTER)
                .with(SendOutcome.APPLICATION_ERROR, Action.RETRY);
        assertThat(p.actionFor(SendOutcome.APPLICATION_REJECT)).isEqualTo(Action.DEAD_LETTER);
        assertThat(p.actionFor(SendOutcome.APPLICATION_ERROR)).isEqualTo(Action.RETRY);
        assertThat(AckPolicy.decode(p.encode())).isEqualTo(p);
        // Commit codes have their own entries, independent of AE and AR.
        AckPolicy commit = p.with(SendOutcome.COMMIT_REJECT, Action.RETRY)
                .with(SendOutcome.COMMIT_ERROR, Action.DEAD_LETTER);
        assertThat(commit.actionFor(SendOutcome.COMMIT_REJECT)).isEqualTo(Action.RETRY);
        assertThat(commit.actionFor(SendOutcome.COMMIT_ERROR)).isEqualTo(Action.DEAD_LETTER);
        assertThat(commit.actionFor(SendOutcome.APPLICATION_REJECT)).isEqualTo(Action.DEAD_LETTER);
        assertThat(AckPolicy.decode(commit.encode())).isEqualTo(commit);
        assertThat(AckPolicy.decode("garbage,NOPE=RETRY,ACCEPTED=RETRY")).isEqualTo(AckPolicy.DEFAULT);
        assertThat(p.with(SendOutcome.APPLICATION_REJECT, Action.RETRY)
                .with(SendOutcome.APPLICATION_ERROR, Action.DEAD_LETTER)).isEqualTo(AckPolicy.DEFAULT);
    }

    @Test
    void successAndValidationCannotBeOverridden() {
        assertThatThrownBy(() -> AckPolicy.DEFAULT.with(SendOutcome.ACCEPTED, Action.RETRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AckPolicy.DEFAULT.with(SendOutcome.VALIDATION_FAILED, Action.RETRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AckPolicy.DEFAULT.with(SendOutcome.ACK_TIMEOUT, Action.COMPLETE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void circuitBreakerOpensHalfOpensAndCloses() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(new CircuitBreakerSettings(3, 1_000), clock);
        assertThat(b.recordFailure()).isFalse();
        assertThat(b.recordFailure()).isFalse();
        assertThat(b.recordFailure()).isTrue();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.millisUntilAllowed()).isEqualTo(1_000);

        clock.advance(1_000);
        assertThat(b.millisUntilAllowed()).isZero();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // A failed probe re-opens immediately.
        assertThat(b.recordFailure()).isTrue();
        assertThat(b.millisUntilAllowed()).isEqualTo(1_000);

        clock.advance(1_000);
        assertThat(b.millisUntilAllowed()).isZero();
        b.recordSuccess();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.consecutiveFailures()).isZero();
    }

    @Test
    void disabledCircuitBreakerNeverOpens() {
        CircuitBreaker b = new CircuitBreaker(new CircuitBreakerSettings(0, 1_000), new MutableClock());
        for (int i = 0; i < 100; i++) {
            assertThat(b.recordFailure()).isFalse();
        }
        assertThat(b.millisUntilAllowed()).isZero();
    }

    /** A clock that only moves when told to. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
