package io.hl7sender.core.queue;

/**
 * @param failureThreshold consecutive failed attempts that open the circuit; {@code 0} disables the breaker
 * @param coolDownMs       how long the circuit stays open before one probe attempt is allowed
 */
public record CircuitBreakerSettings(int failureThreshold, long coolDownMs) {

    public static final CircuitBreakerSettings DEFAULT = new CircuitBreakerSettings(5, 60_000);

    public CircuitBreakerSettings {
        if (failureThreshold < 0 || coolDownMs < 0) {
            throw new IllegalArgumentException("Circuit breaker settings cannot be negative");
        }
    }

    public boolean enabled() {
        return failureThreshold > 0;
    }
}
