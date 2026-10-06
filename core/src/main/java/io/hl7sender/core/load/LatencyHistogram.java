package io.hl7sender.core.load;

/**
 * Latency distribution in fixed memory, for percentiles over millions of samples.
 *
 * <p>Values are recorded in microseconds. Values below 1,024 µs are counted exactly; larger values share buckets
 * of 512 per power of two, so a reported percentile is within 0.2% of the true value. The range covers about
 * 12 days, far beyond any ACK timeout. Not thread-safe: callers synchronize.
 */
final class LatencyHistogram {

    private static final int EXACT = 1024;
    private static final int SUB_BUCKETS = 512;
    private static final int SUB_BITS = 9;
    /** Largest recordable value: 2^40 µs. Larger values are clamped. */
    private static final long MAX_VALUE = (1L << 40) - 1;
    private static final int SIZE = index(MAX_VALUE) + 1;

    private final long[] counts = new long[SIZE];
    private long total;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max;

    void record(long micros) {
        long v = Math.max(0, Math.min(micros, MAX_VALUE));
        counts[index(v)]++;
        total++;
        sum += v;
        min = Math.min(min, v);
        max = Math.max(max, v);
    }

    long count() {
        return total;
    }

    long minMicros() {
        return total == 0 ? 0 : min;
    }

    long maxMicros() {
        return max;
    }

    double meanMicros() {
        return total == 0 ? 0 : (double) sum / total;
    }

    /**
     * The value at or below which {@code percentile}% of samples fall (nearest rank), in microseconds. Returns the
     * midpoint of the bucket, clamped to the recorded min and max; p100 is the exact maximum.
     */
    long percentileMicros(double percentile) {
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(percentile / 100.0 * total));
        if (rank >= total) {
            return max;
        }
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                long mid = lowerBound(i) + (width(i) - 1) / 2;
                return Math.max(minMicros(), Math.min(max, mid));
            }
        }
        return max;
    }

    static int index(long v) {
        if (v < EXACT) {
            return (int) v;
        }
        int msb = 63 - Long.numberOfLeadingZeros(v);
        int shift = msb - SUB_BITS;
        int sub = (int) (v >> shift);
        return EXACT + (shift - 1) * SUB_BUCKETS + (sub - SUB_BUCKETS);
    }

    static long lowerBound(int index) {
        if (index < EXACT) {
            return index;
        }
        int shift = (index - EXACT) / SUB_BUCKETS + 1;
        long sub = (index - EXACT) % SUB_BUCKETS + SUB_BUCKETS;
        return sub << shift;
    }

    static long width(int index) {
        return index < EXACT ? 1 : 1L << ((index - EXACT) / SUB_BUCKETS + 1);
    }
}
