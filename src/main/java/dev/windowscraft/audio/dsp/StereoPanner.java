/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Constant-power stereo pan for the "speakers" output profile. One instance per emitter. */
public final class StereoPanner {
    private static final double HALF_PI = Math.PI / 2.0;
    public float lastL, lastR;

    public void pan(float sample, double azimuthRad) {
        double t = (clamp(azimuthRad, -HALF_PI, HALF_PI) / HALF_PI + 1.0) / 2.0; // 0 = left .. 1 = right
        double angle = t * HALF_PI;
        lastL = (float) (sample * Math.cos(angle));
        lastR = (float) (sample * Math.sin(angle));
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
