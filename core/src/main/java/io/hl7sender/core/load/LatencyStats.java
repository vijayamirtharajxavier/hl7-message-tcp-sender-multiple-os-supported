package io.hl7sender.core.load;

/**
 * Latency summary in milliseconds: time from writing a message to receiving its complete response (or the
 * timeout). Connection setup is not included.
 *
 * @param count samples
 * @param min   fastest
 * @param mean  average
 * @param p50   median
 * @param p90   90th percentile
 * @param p95   95th percentile
 * @param p99   99th percentile
 * @param max   slowest
 */
public record LatencyStats(long count, double min, double mean, double p50, double p90, double p95, double p99,
                           double max) {

    public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0, 0, 0, 0, 0, 0);

    static LatencyStats of(LatencyHistogram h) {
        if (h.count() == 0) {
            return EMPTY;
        }
        return new LatencyStats(h.count(), ms(h.minMicros()), round(h.meanMicros() / 1000.0),
                ms(h.percentileMicros(50)), ms(h.percentileMicros(90)), ms(h.percentileMicros(95)),
                ms(h.percentileMicros(99)), ms(h.maxMicros()));
    }

    private static double ms(long micros) {
        return round(micros / 1000.0);
    }

    private static double round(double ms) {
        return Math.round(ms * 100.0) / 100.0;
    }
}
