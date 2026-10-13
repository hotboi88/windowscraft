/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** One-pole exponential smoother for block-rate control parameters, so updates never zipper. */
public final class ParamSmoother {
    private final float timeConstantSeconds;
    private float value;

    public ParamSmoother(float timeConstantSeconds, float initial) {
        this.timeConstantSeconds = timeConstantSeconds;
        this.value = initial;
    }

    public ParamSmoother(float timeConstantSeconds) { this(timeConstantSeconds, 0f); }

    public float value() { return value; }

    /** Advances toward {@code target} over {@code dtSeconds} and returns the new value. */
    public float next(float target, float dtSeconds) {
        if (dtSeconds <= 0f) return value;
        float alpha = 1f - (float) Math.exp(-dtSeconds / timeConstantSeconds);
        value += (target - value) * alpha;
        return value;
    }

    public void snap(float v) { value = v; }
}
