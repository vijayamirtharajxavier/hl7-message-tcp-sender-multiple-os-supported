package io.hl7sender.core.load;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass/fail limits for a load test, so a pipeline can fail when the receiver gets slower or starts rejecting
 * messages. A negative value means "no limit".
 *
 * @param maxErrorPercent most messages (0–100) that may be not accepted
 * @param maxP95Ms        slowest allowed 95th-percentile latency
 * @param maxP99Ms        slowest allowed 99th-percentile latency
 * @param minThroughput   fewest completed messages per second
 */
public record LoadThresholds(double maxErrorPercent, double maxP95Ms, double maxP99Ms, double minThroughput) {

    public static final LoadThresholds NONE = new LoadThresholds(-1, -1, -1, -1);

    public boolean any() {
        return maxErrorPercent >= 0 || maxP95Ms >= 0 || maxP99Ms >= 0 || minThroughput >= 0;
    }

    /** Human-readable reasons the result misses a limit; empty if it meets them all. */
    public List<String> failures(LoadTestSnapshot r) {
        List<String> failures = new ArrayList<>();
        if (maxErrorPercent >= 0 && r.errorPercent() > maxErrorPercent) {
            failures.add("error rate " + r.errorPercent() + "% is above " + maxErrorPercent + "%");
        }
        if (maxP95Ms >= 0 && r.latency().p95() > maxP95Ms) {
            failures.add("p95 latency " + r.latency().p95() + " ms is above " + maxP95Ms + " ms");
        }
        if (maxP99Ms >= 0 && r.latency().p99() > maxP99Ms) {
            failures.add("p99 latency " + r.latency().p99() + " ms is above " + maxP99Ms + " ms");
        }
        if (minThroughput >= 0 && r.throughput() < minThroughput) {
            failures.add("throughput " + r.throughput() + " msg/s is below " + minThroughput + " msg/s");
        }
        if (any() && r.completed() == 0) {
            failures.add("no messages completed");
        }
        return failures;
    }
}
