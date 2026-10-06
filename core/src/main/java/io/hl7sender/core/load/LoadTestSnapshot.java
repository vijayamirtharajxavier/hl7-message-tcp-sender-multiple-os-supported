package io.hl7sender.core.load;

import io.hl7sender.core.send.SendOutcome;
import java.util.List;
import java.util.Map;

/**
 * Progress of a running load test, or its final result.
 *
 * @param elapsedMillis time since the test started
 * @param sent          messages written (including those still waiting for a response)
 * @param completed     messages with a final outcome
 * @param outcomes      completed messages by outcome
 * @param latency       latency over all completed messages so far
 * @param throughput    completed messages per second over the whole test so far
 * @param timeline      one entry per whole second so far
 * @param finished      true for the final snapshot
 */
public record LoadTestSnapshot(
        long elapsedMillis,
        long sent,
        long completed,
        Map<SendOutcome, Long> outcomes,
        LatencyStats latency,
        double throughput,
        List<Second> timeline,
        boolean finished) {

    /**
     * Counts for one second of the test.
     *
     * @param second        seconds since the start (0 = the first second)
     * @param completed     messages completed in this second
     * @param failed        of those, the ones that were not accepted
     * @param meanLatencyMs average latency of this second's messages
     * @param maxLatencyMs  slowest message in this second
     */
    public record Second(long second, long completed, long failed, double meanLatencyMs, double maxLatencyMs) {
    }

    /** Messages accepted (AA/CA, or sent without an ACK). */
    public long accepted() {
        return outcomes.getOrDefault(SendOutcome.ACCEPTED, 0L) + outcomes.getOrDefault(SendOutcome.SENT_NO_ACK, 0L);
    }

    /** Completed messages that were not accepted. */
    public long failed() {
        return completed - accepted();
    }

    /** Share of completed messages that were not accepted, 0–100. */
    public double errorPercent() {
        return completed == 0 ? 0 : Math.round(failed() * 10_000.0 / completed) / 100.0;
    }
}
