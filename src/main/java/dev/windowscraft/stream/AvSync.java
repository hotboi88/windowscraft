package dev.windowscraft.stream;

/**
 * Lip sync for someone else's screen: how long to hold its pictures back so they show when their
 * sound is heard.
 *
 * <p>Both arrive stamped by the sharer's clock. Sound takes the longer way (the sharer's cushion
 * against uneven capture, the AAC encoder, this PC's playback reserve and sound card) — about a
 * third of a second more than the picture since the picture's delays were cut (Step 0). The gap
 * is measured, not assumed: for each stream, arrival time minus timestamp (the two PCs' clocks
 * differ, but by the same for both streams, so it cancels), median over the last few seconds;
 * then what this PC adds to each (sound still queued to the sound card; the picture's decode and
 * upload).
 *
 * <p>Pictures are only held while that's wanted: sound is playing, and nobody here is using the
 * screen (pointing at it, sitting at it — then the quickest picture matters more). The first
 * time, it goes straight there (one short pause as you start watching); after that the hold
 * builds up slowly (pictures ~2 % slower for a few seconds: not visible) and drops at once.
 * {@code windowscraft.lipSync=off}: never held.
 */
final class AvSync {
    static final boolean ENABLED = !"off".equals(System.getProperty("windowscraft.lipSync"));
    /** The picture's own way after it's let through: decode + upload + the frame it shows in (Step 0: ~8 + 12 + ~8 ms). */
    static final double PICTURE_AFTER_MS = 28;
    private static final double MAX_MS = 600, RAMP_MS_PER_S = 20;
    private static final int WINDOW = 96;

    private final double[] video = new double[WINDOW], sound = new double[WINDOW];
    private int vn, sn, vi, si;
    private long ref = Long.MIN_VALUE;
    private double holdMs;
    private long lastStepNanos;
    private boolean settled; // (the first time it's wanted and measured: straight there, once)

    /** A packet of the picture (video) or sound started arriving now with this 90 kHz timestamp. Any thread. */
    synchronized void arrived(boolean isVideo, long pts90k, long nanos) {
        if (ref == Long.MIN_VALUE) ref = pts90k;
        long d = (pts90k - ref) & ((1L << 33) - 1);
        if (d >= 1L << 32) d -= 1L << 33; // (timestamps wrap every 26.5 h)
        double off = nanos / 1e6 - d / 90.0;
        if (isVideo) { video[vi] = off; vi = (vi + 1) % WINDOW; vn = Math.min(WINDOW, vn + 1); }
        else { sound[si] = off; si = (si + 1) % WINDOW; sn = Math.min(WINDOW, sn + 1); }
    }

    /** How much later than the picture the sound reaches this PC (ms), or NaN until both have been seen a while. */
    synchronized double arrivalGapMs() {
        if (vn < 16 || sn < 16) return Double.NaN;
        return median(sound, sn) - median(video, vn);
    }

    private static double median(double[] a, int n) {
        double[] c = java.util.Arrays.copyOf(a, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }

    /**
     * Once in a while (the feed thread): the hold to use from now on. {@code soundQueuedMs}: sound
     * written but not yet heard (NaN: no sound device); {@code wanted}: sound playing and nobody
     * here using the screen.
     */
    synchronized double step(boolean wanted, double soundQueuedMs, long now) {
        double target = 0;
        double gap = arrivalGapMs();
        if (ENABLED && wanted && !Double.isNaN(gap) && !Double.isNaN(soundQueuedMs))
            target = Math.max(0, Math.min(MAX_MS, gap + soundQueuedMs - PICTURE_AFTER_MS));
        double dt = lastStepNanos == 0 ? 0 : Math.min(1, (now - lastStepNanos) / 1e9);
        lastStepNanos = now;
        if (target < holdMs) holdMs = target; // (less: at once — someone started using it)
        else if (!settled && target > 0) holdMs = target; // (just started watching: one short pause, then in sync)
        else holdMs = Math.min(target, holdMs + RAMP_MS_PER_S * dt);
        if (target > 0) settled = true;
        return holdMs;
    }

    synchronized double holdMs() { return holdMs; }

    /** The 90 kHz timestamp of a packet's PES header if this TS packet starts one (else -1). */
    static long ptsIfStart(byte[] b, int o) {
        if ((b[o + 1] & 0x40) == 0) return -1;
        int afc = (b[o + 3] >> 4) & 0x3;
        if ((afc & 0x1) == 0) return -1;
        int p = o + 4;
        if ((afc & 0x2) != 0) p += 1 + (b[o + 4] & 0xFF);
        if (p + 14 > o + 188 || b[p] != 0 || b[p + 1] != 0 || b[p + 2] != 1 || (b[p + 7] & 0x80) == 0) return -1;
        p += 9;
        return ((long) ((b[p] >> 1) & 0x07) << 30) | ((long) (b[p + 1] & 0xFF) << 22) | ((long) ((b[p + 2] & 0xFF) >> 1) << 15)
                | ((long) (b[p + 3] & 0xFF) << 7) | ((b[p + 4] & 0xFF) >> 1);
    }
}
