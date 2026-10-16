package dev.windowscraft.audio;

/**
 * Finds sound that was lost on its way in, by when it arrives. The capture hands it over in
 * batches about 10 ms apart, together as long as the time that passed. When a batch comes up
 * short and the ones after don't make up for it, that much sound never came: the capture helper
 * loses a little, now and then, while it turns the app down on a busy PC (wc-audio 0.4.4 looks
 * for the app's sessions on the thread that reads the sound: ~30 ms once a second). Silence of
 * the same length belongs where it went missing, so what comes after stays in step with the
 * picture — closed up, it played early by that much (and the cushion ran dry: a longer gap).
 */
final class LostSound {
    private static final long BATCH_GAP_NANOS = 3_000_000L;
    /** Nothing for this long: the app went quiet (application loopback then sends nothing). */
    private static final long QUIET_NANOS = 200_000_000L;
    /** How many batches it waits for the sound to come after all. */
    private static final int AFTER = 5;
    /** How far back it looks for how short the batches normally are. */
    private static final long BEFORE_NANOS = 2_000_000_000L;
    private static final double LEAST_MS = 5, MOST_MS = 200;
    private static final int N = 512;

    private long t0, last, received, total;
    // each batch's end: when, how far short of the clock the sound was then (ms), and where the
    // batch after it starts (bytes, counted like `total`)
    private final long[] when = new long[N], startOfNext = new long[N];
    private final double[] shortMs = new double[N];
    private long count;

    /** Silence to put in: {@code bytes} of it, {@code back} bytes before the end of what came so far. */
    record Hole(int bytes, int back) {}

    /** {@code len} bytes of sound came in at {@code now}; before they're passed on, any silence that belongs before them. */
    Hole arrived(long now, int len) {
        Hole hole = null;
        if (t0 == 0 || now - last > QUIET_NANOS) {
            t0 = now;
            received = 0;
            count = 0;
        } else if (now - last > BATCH_GAP_NANOS) {
            // the batch before this one is complete
            int i = (int) (count++ % N);
            when[i] = last;
            shortMs[i] = (last - t0) / 1e6 - received * 1000.0 / AudioCapture.BYTES_PER_SECOND;
            startOfNext[i] = total;
            hole = check();
        }
        received += len;
        total += len;
        last = now;
        return hole;
    }

    private Hole check() {
        long j = count - AFTER; // the batch that may have come up short; the ones after it
        if (j < 21) return null; // (getting to know how it comes in)
        double after = Double.MAX_VALUE;
        for (long k = j; k < count; k++) after = Math.min(after, shortMs[(int) (k % N)]);
        double before = Double.MAX_VALUE;
        long since = when[(int) (j % N)] - BEFORE_NANOS;
        for (long k = j - 1; k >= Math.max(0, count - N) && when[(int) (k % N)] >= since; k--) before = Math.min(before, shortMs[(int) (k % N)]);
        double lost = after - before;
        if (before == Double.MAX_VALUE || lost < LEAST_MS) return null;
        int bytes = (int) Math.round(Math.min(lost, MOST_MS) * AudioCapture.BYTES_PER_SECOND / 1000.0) & ~3;
        // it went missing at the start of that short batch
        int back = (int) (total - startOfNext[(int) ((j - 1) % N)]);
        double ms = bytes * 1000.0 / AudioCapture.BYTES_PER_SECOND;
        for (long k = j; k < count; k++) {
            shortMs[(int) (k % N)] -= ms;
            startOfNext[(int) (k % N)] += bytes;
        }
        received += bytes;
        total += bytes;
        return new Hole(bytes, back);
    }
}
