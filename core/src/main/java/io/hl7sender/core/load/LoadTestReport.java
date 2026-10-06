package io.hl7sender.core.load;

import java.time.Instant;
import java.util.List;

/**
 * The result of a load test.
 *
 * @param plan       what was run, e.g. {@code 10 connections, 500 msg/s, 10000 messages -> host:2575}
 * @param startedAt  when the first message was sent
 * @param result     final counts, latency and timeline
 * @param errors     up to 20 distinct problems, each with how often it happened, e.g.
 *                   {@code ACK_TIMEOUT: No ACK within 30000 ms (x12)}
 * @param cancelled  true if the test was stopped before its limit
 */
public record LoadTestReport(String plan, Instant startedAt, LoadTestSnapshot result, List<String> errors,
                             boolean cancelled) {
}
