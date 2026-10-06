package io.hl7sender.core.queue;

import io.hl7sender.core.send.SendOutcome;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the delivery engine does after each {@link SendOutcome}. The defaults follow
 * {@link SendOutcome#retryable()}:
 *
 * <ul>
 *   <li>AA/CA and sent-without-ACK: complete</li>
 *   <li>AE/CE (content error) and validation failures: dead-letter immediately</li>
 *   <li>AR/CR, timeouts, connection and protocol problems, mismatched or invalid ACKs: retry</li>
 * </ul>
 *
 * Retryable outcomes can be changed per destination, for example to dead-letter AR
 * straight away or to retry AE. Success outcomes and validation failures cannot be changed.
 */
public final class AckPolicy {

    /** What to do with a message after an attempt. */
    public enum Action { COMPLETE, RETRY, DEAD_LETTER }

    public static final AckPolicy DEFAULT = new AckPolicy(Map.of());

    private final Map<SendOutcome, Action> overrides;

    private AckPolicy(Map<SendOutcome, Action> overrides) {
        EnumMap<SendOutcome, Action> copy = new EnumMap<>(SendOutcome.class);
        copy.putAll(overrides);
        this.overrides = copy;
    }

    public static Action defaultAction(SendOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED, SENT_NO_ACK -> Action.COMPLETE;
            default -> outcome.retryable() ? Action.RETRY : Action.DEAD_LETTER;
        };
    }

    /** True if the action for {@code outcome} can be changed. */
    public static boolean isConfigurable(SendOutcome outcome) {
        return defaultAction(outcome) != Action.COMPLETE && outcome != SendOutcome.VALIDATION_FAILED;
    }

    public Action actionFor(SendOutcome outcome) {
        return overrides.getOrDefault(outcome, defaultAction(outcome));
    }

    /** Returns a policy with {@code outcome} mapped to {@code action}, which may only be RETRY or DEAD_LETTER. */
    public AckPolicy with(SendOutcome outcome, Action action) {
        if (!isConfigurable(outcome)) {
            throw new IllegalArgumentException(outcome + " cannot be overridden");
        }
        if (action == Action.COMPLETE) {
            throw new IllegalArgumentException("Only RETRY or DEAD_LETTER can be configured");
        }
        EnumMap<SendOutcome, Action> copy = new EnumMap<>(SendOutcome.class);
        copy.putAll(overrides);
        if (action == defaultAction(outcome)) {
            copy.remove(outcome);
        } else {
            copy.put(outcome, action);
        }
        return new AckPolicy(copy);
    }

    /** Compact storage form, e.g. {@code APPLICATION_REJECT=DEAD_LETTER,APPLICATION_ERROR=RETRY}. */
    public String encode() {
        return overrides.entrySet().stream()
                .map(e -> e.getKey().name() + "=" + e.getValue().name())
                .collect(Collectors.joining(","));
    }

    /** Parses {@link #encode()} output. Unknown or invalid entries are ignored. */
    public static AckPolicy decode(String text) {
        AckPolicy policy = DEFAULT;
        if (text == null || text.isBlank()) {
            return policy;
        }
        for (String entry : text.split(",")) {
            String[] kv = entry.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                policy = policy.with(SendOutcome.valueOf(kv[0].trim().toUpperCase(Locale.ROOT)),
                        Action.valueOf(kv[1].trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Skip entries from newer or older versions that no longer apply.
            }
        }
        return policy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AckPolicy other && overrides.equals(other.overrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode();
    }

    @Override
    public String toString() {
        return overrides.isEmpty() ? "AckPolicy[defaults]" : "AckPolicy[" + encode() + "]";
    }
}
