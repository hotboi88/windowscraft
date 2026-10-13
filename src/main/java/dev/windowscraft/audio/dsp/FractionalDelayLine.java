/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Linear-interpolated delay line for sub-sample ITD delays. */
public final class FractionalDelayLine {
    private final int capacity;
    private final float[] buf;
    private int writeIdx;

    public FractionalDelayLine(int capacity) {
        this.capacity = capacity;
        this.buf = new float[capacity];
    }

    public void push(float x) {
        buf[writeIdx] = x;
        writeIdx = (writeIdx + 1) % capacity;
    }

    /** The sample {@code delaySamples} behind the newest one (0 = newest), interpolated. */
    public float read(float delaySamples) {
        if (!(delaySamples >= 0f)) delaySamples = 0f; // also catches NaN
        float d = Math.min(delaySamples, capacity - 2);
        float pos = writeIdx - 1 - d;
        float wrapped = pos % capacity;
        if (wrapped < 0f) wrapped += capacity;
        if (wrapped >= capacity) wrapped -= capacity; // -1e-9 + capacity rounds to capacity in float
        int i0 = Math.min(capacity - 1, Math.max(0, (int) Math.floor(wrapped)));
        float frac = wrapped - i0;
        int i1 = (i0 + 1) % capacity;
        return buf[i0] * (1f - frac) + buf[i1] * frac;
    }

    public void reset() {
        java.util.Arrays.fill(buf, 0f);
        writeIdx = 0;
    }
}
