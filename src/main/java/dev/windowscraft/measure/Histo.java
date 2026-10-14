package dev.windowscraft.measure;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A fixed-size histogram of non-negative values (log-linear buckets, about 3 % wide), cheap enough
 * to record into from any thread on every frame: one array increment and a few atomics, no
 * allocation. Values above 2^40 (about 18 minutes in nanoseconds) count in the top bucket.
 */
public final class Histo {
    private static final int SUB = 5, SUBS = 1 << SUB, MAX_BITS = 40;
    static final int BUCKETS = (MAX_BITS - SUB + 1) * SUBS + SUBS;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong n = new AtomicLong(), sum = new AtomicLong();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE), max = new AtomicLong(Long.MIN_VALUE);

    static int index(long v) {
        if (v < SUBS) return (int) Math.max(0, v);
        int msb = 63 - Long.numberOfLeadingZeros(v);
        if (msb > MAX_BITS) return BUCKETS - 1;
        long m = v >>> (msb - SUB); // the top SUB+1 bits: [SUBS, 2*SUBS)
        return (msb - SUB + 1) * SUBS + (int) (m - SUBS);
    }

    /** The lowest value that lands in bucket {@code i}. */
    static long lower(int i) {
        if (i < SUBS) return i;
        int g = i / SUBS, r = i % SUBS;
        return (long) (SUBS + r) << (g - 1);
    }

    /** One past the highest value that lands in bucket {@code i}. */
    static long upper(int i) {
        if (i < SUBS) return i + 1;
        int g = i / SUBS, r = i % SUBS;
        return (long) (SUBS + r + 1) << (g - 1);
    }

    public void record(long v) {
        if (v < 0) v = 0;
        counts.incrementAndGet(index(v));
        n.incrementAndGet();
        sum.addAndGet(v);
        long m;
        while (v < (m = min.get()) && !min.compareAndSet(m, v)) { }
        while (v > (m = max.get()) && !max.compareAndSet(m, v)) { }
    }

    public long count() { return n.get(); }
    public long sum() { return sum.get(); }
    public long min() { return n.get() == 0 ? 0 : min.get(); }
    public long max() { return n.get() == 0 ? 0 : max.get(); }
    public double mean() { long c = n.get(); return c == 0 ? 0 : sum.get() / (double) c; }

    /** The value below which a share {@code q} (0–1) of the recorded values lie (bucket midpoint, clamped to min/max). */
    public double quantile(double q) {
        long total = n.get();
        if (total == 0) return 0;
        long rank = (long) Math.ceil(q * total);
        if (rank < 1) rank = 1;
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            long c = counts.get(i);
            if (c == 0) continue;
            seen += c;
            if (seen >= rank) {
                double mid = (lower(i) + upper(i) - 1) / 2.0;
                return Math.max(min(), Math.min(max(), mid));
            }
        }
        return max();
    }

    /** The mean of the highest {@code share} (e.g. 0.01) of the values — "1 % lows" for frame times. */
    public double meanOfTop(double share) {
        long total = n.get();
        if (total == 0) return 0;
        long want = Math.max(1, (long) Math.ceil(share * total)), got = 0;
        double acc = 0;
        for (int i = BUCKETS - 1; i >= 0 && got < want; i--) {
            long c = counts.get(i);
            if (c == 0) continue;
            long take = Math.min(c, want - got);
            double mid = Math.max(min(), Math.min(max(), (lower(i) + upper(i) - 1) / 2.0));
            acc += mid * take;
            got += take;
        }
        return acc / got;
    }
}
