/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Compact Schröder / Freeverb-style reverb: 8 damped combs in parallel into 4 series all-passes. */
public final class Reverb {
    private static final int[] COMB_TUNING = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_TUNING = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;
    private static final float ALLPASS_FEEDBACK = 0.5f, ROOM_SCALE = 0.28f, ROOM_OFFSET = 0.7f, DAMP_SCALE = 0.4f, GAIN = 0.015f;

    private final CombFilter[] combsL, combsR;
    private final AllpassFilter[] allpassL, allpassR;
    public float lastL, lastR;

    public Reverb(float sampleRate) {
        float scale = sampleRate / 44100f;
        combsL = new CombFilter[COMB_TUNING.length];
        combsR = new CombFilter[COMB_TUNING.length];
        for (int i = 0; i < COMB_TUNING.length; i++) {
            combsL[i] = new CombFilter(scaled(COMB_TUNING[i], scale));
            combsR[i] = new CombFilter(scaled(COMB_TUNING[i] + STEREO_SPREAD, scale));
        }
        allpassL = new AllpassFilter[ALLPASS_TUNING.length];
        allpassR = new AllpassFilter[ALLPASS_TUNING.length];
        for (int i = 0; i < ALLPASS_TUNING.length; i++) {
            allpassL[i] = new AllpassFilter(scaled(ALLPASS_TUNING[i], scale), ALLPASS_FEEDBACK);
            allpassR[i] = new AllpassFilter(scaled(ALLPASS_TUNING[i] + STEREO_SPREAD, scale), ALLPASS_FEEDBACK);
        }
    }

    private static int scaled(int n, float scale) { return Math.max(1, Math.round(n * scale)); }

    /** roomSize 0 = tight .. 1 = cavernous; damping 0 = bright/hard .. 1 = dark/soft. */
    public void updateParams(float roomSize, float damping) {
        float feedback = Math.max(0f, Math.min(1f, roomSize)) * ROOM_SCALE + ROOM_OFFSET;
        float damp = Math.max(0f, Math.min(1f, damping)) * DAMP_SCALE;
        for (CombFilter c : combsL) { c.feedback = feedback; c.damp = damp; }
        for (CombFilter c : combsR) { c.feedback = feedback; c.damp = damp; }
    }

    public void process(float input) {
        float fed = input * GAIN, l = 0f, r = 0f;
        for (int i = 0; i < combsL.length; i++) {
            l += combsL[i].process(fed);
            r += combsR[i].process(fed);
        }
        for (AllpassFilter ap : allpassL) l = ap.process(l);
        for (AllpassFilter ap : allpassR) r = ap.process(r);
        lastL = l;
        lastR = r;
    }

    public void reset() {
        for (CombFilter c : combsL) c.reset();
        for (CombFilter c : combsR) c.reset();
        for (AllpassFilter a : allpassL) a.reset();
        for (AllpassFilter a : allpassR) a.reset();
    }

    private static final class CombFilter {
        private final float[] buf;
        private int idx;
        private float store;
        float feedback, damp;

        CombFilter(int size) { buf = new float[size]; }

        float process(float x) {
            float out = buf[idx];
            store = out * (1f - damp) + store * damp;
            buf[idx] = x + store * feedback;
            if (++idx >= buf.length) idx = 0;
            return out;
        }

        void reset() { java.util.Arrays.fill(buf, 0f); store = 0f; idx = 0; }
    }

    private static final class AllpassFilter {
        private final float[] buf;
        private final float feedback;
        private int idx;

        AllpassFilter(int size, float feedback) { buf = new float[size]; this.feedback = feedback; }

        float process(float x) {
            float bufout = buf[idx];
            float out = -x + bufout;
            buf[idx] = x + bufout * feedback;
            if (++idx >= buf.length) idx = 0;
            return out;
        }

        void reset() { java.util.Arrays.fill(buf, 0f); idx = 0; }
    }
}
