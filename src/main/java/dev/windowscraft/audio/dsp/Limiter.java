/*
 * Originally ported from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors; rewritten as a lookahead
 * limiter for WindowsCraft. Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/**
 * Lookahead peak limiter: the sound is delayed by 2 ms so the gain can come down smoothly
 * <em>before</em> a loud peak arrives, instead of the peak being chopped off (which is what made
 * boosted drums and voices crackle). The gain never jumps — it ramps over the lookahead going
 * down and over ~80 ms coming back up — so it adds no clicks of its own.
 *
 * <p>How: each sample's needed gain (ceiling / peak) goes through a running minimum over the
 * lookahead window, then a moving average of the same length. Any average of values that are
 * all at most the gain a peak needs is itself at most that, so by the time the delayed peak comes
 * out the gain is low enough — and it got there in a straight, smooth ramp.
 */
public final class Limiter {
    public static final int LOOKAHEAD = 96; // 2 ms at 48 kHz
    private final float ceiling, releaseCoeff;
    private final float[] delayL = new float[LOOKAHEAD], delayR = new float[LOOKAHEAD];
    private final float[] minRing = new float[LOOKAHEAD];  // ring of running-minimum values for the average
    // monotonic deque (indices into an ever-increasing sample count) for the running minimum
    private final long[] dqIdx = new long[LOOKAHEAD + 2];
    private final float[] dqVal = new float[LOOKAHEAD + 2];
    private int dqHead, dqSize;
    private long n;
    private double minSum;
    private float gain = 1f;
    public float lastL, lastR;

    public Limiter(float sampleRate) { this(sampleRate, 0.891f /* -1 dBFS */); }

    public Limiter(float sampleRate, float ceiling) {
        this.ceiling = ceiling;
        this.releaseCoeff = (float) Math.exp(-1f / (0.080f * sampleRate));
        reset();
    }

    public void process(float l, float r) {
        int slot = (int) (n % LOOKAHEAD);
        float peak = Math.max(Math.abs(l), Math.abs(r));
        float need = peak > ceiling ? ceiling / peak : 1f;
        // running minimum of "need" over the last LOOKAHEAD + 1 samples (so it covers the sample leaving the delay)
        while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % dqVal.length] >= need) dqSize--;
        dqIdx[(dqHead + dqSize) % dqIdx.length] = n;
        dqVal[(dqHead + dqSize) % dqVal.length] = need;
        dqSize++;
        while (dqIdx[dqHead] < n - LOOKAHEAD) { dqHead = (dqHead + 1) % dqIdx.length; dqSize--; }
        float runMin = dqVal[dqHead];
        // moving average of the running minimum: a straight ramp down ahead of the peak
        minSum += runMin - minRing[slot];
        minRing[slot] = runMin;
        float avg = (float) (minSum / LOOKAHEAD);
        // coming back up slowly (no pumping); going down exactly as planned
        gain = avg < gain ? avg : avg + (gain - avg) * releaseCoeff;
        // output the sample that entered LOOKAHEAD samples ago
        float outL = delayL[slot], outR = delayR[slot];
        delayL[slot] = l;
        delayR[slot] = r;
        lastL = Math.max(-ceiling, Math.min(ceiling, outL * gain));
        lastR = Math.max(-ceiling, Math.min(ceiling, outR * gain));
        n++;
        if ((n & 0xFFFF) == 0) recomputeSum(); // keep float rounding from drifting
    }

    private void recomputeSum() {
        double s = 0;
        for (float v : minRing) s += v;
        minSum = s;
    }

    public void reset() {
        java.util.Arrays.fill(delayL, 0f);
        java.util.Arrays.fill(delayR, 0f);
        java.util.Arrays.fill(minRing, 1f);
        minSum = LOOKAHEAD;
        dqHead = 0;
        dqSize = 0;
        n = 0;
        gain = 1f;
    }
}
