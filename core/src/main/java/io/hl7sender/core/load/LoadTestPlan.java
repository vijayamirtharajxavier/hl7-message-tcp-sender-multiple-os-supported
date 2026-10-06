package io.hl7sender.core.load;

import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.tls.TlsOptions;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What a load test sends, where, and how hard.
 *
 * @param target            receiver address, timeouts and character set
 * @param tls               TLS settings, or {@code null} for plain TCP
 * @param messages          messages to send in turn; each may be a template with {@code ${...}} variables, expanded
 *                          once per message
 * @param connections       concurrent connections (virtual users), each sending one message at a time
 * @param ratePerSecond     target total rate across all connections; 0 sends as fast as the receiver answers
 * @param messageCount      stop after this many messages; 0 for no limit (then {@code duration} is required)
 * @param duration          stop after this long; {@link Duration#ZERO} for no limit (then {@code messageCount} is
 *                          required)
 * @param ackMode           wait for an ACK per message, or send without one
 * @param generateControlId give every message a new MSH-10 (needed to match ACKs when templates repeat)
 */
public record LoadTestPlan(
        MllpClientConfig target,
        TlsOptions tls,
        List<String> messages,
        int connections,
        double ratePerSecond,
        long messageCount,
        Duration duration,
        AckMode ackMode,
        boolean generateControlId) {

    /** Upper limit on connections, to protect both ends from a typo. */
    public static final int MAX_CONNECTIONS = 1000;

    public LoadTestPlan {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(ackMode, "ackMode");
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("Give at least one message to send");
        }
        if (connections < 1 || connections > MAX_CONNECTIONS) {
            throw new IllegalArgumentException("Connections must be between 1 and " + MAX_CONNECTIONS);
        }
        if (ratePerSecond < 0 || Double.isNaN(ratePerSecond) || Double.isInfinite(ratePerSecond)) {
            throw new IllegalArgumentException("The rate must be 0 (unlimited) or more");
        }
        if (messageCount < 0 || duration.isNegative()) {
            throw new IllegalArgumentException("The message count and duration cannot be negative");
        }
        if (messageCount == 0 && duration.isZero()) {
            throw new IllegalArgumentException("Give a message count, a duration, or both");
        }
    }

    /** A plan with defaults: one connection, as fast as possible, waiting for ACKs, new MSH-10 per message. */
    public static LoadTestPlan of(MllpClientConfig target, List<String> messages, long messageCount) {
        return new LoadTestPlan(target, null, messages, 1, 0, messageCount, Duration.ZERO, AckMode.EXPECT_ACK, true);
    }

    /** Like {@link #of}, but runs for {@code duration} with no message limit. */
    public static LoadTestPlan forDuration(MllpClientConfig target, List<String> messages, Duration duration) {
        return new LoadTestPlan(target, null, messages, 1, 0, 0, duration, AckMode.EXPECT_ACK, true);
    }

    public LoadTestPlan withConnections(int n) {
        return new LoadTestPlan(target, tls, messages, n, ratePerSecond, messageCount, duration, ackMode,
                generateControlId);
    }

    public LoadTestPlan withRate(double perSecond) {
        return new LoadTestPlan(target, tls, messages, connections, perSecond, messageCount, duration, ackMode,
                generateControlId);
    }

    public LoadTestPlan withLimits(long count, Duration maxDuration) {
        return new LoadTestPlan(target, tls, messages, connections, ratePerSecond, count, maxDuration, ackMode,
                generateControlId);
    }

    public LoadTestPlan withAckMode(AckMode mode) {
        return new LoadTestPlan(target, tls, messages, connections, ratePerSecond, messageCount, duration, mode,
                generateControlId);
    }

    public LoadTestPlan withTls(TlsOptions options) {
        return new LoadTestPlan(target, options, messages, connections, ratePerSecond, messageCount, duration, ackMode,
                generateControlId);
    }

    /** One line for logs and reports, e.g. {@code 10 connections, 500 msg/s, 10000 messages -> host:2575}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(connections).append(connections == 1 ? " connection, " : " connections, ");
        sb.append(ratePerSecond > 0 ? trim(ratePerSecond) + " msg/s" : "unlimited rate");
        if (messageCount > 0) {
            sb.append(", ").append(messageCount).append(" messages");
        }
        if (!duration.isZero()) {
            sb.append(messageCount > 0 ? " or " : ", ").append(duration.toSeconds()).append(" s");
        }
        sb.append(" -> ").append(target.address());
        if (tls != null) {
            sb.append(" (TLS)");
        }
        return sb.toString();
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
