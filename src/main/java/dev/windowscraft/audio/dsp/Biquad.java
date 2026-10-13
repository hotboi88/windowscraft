/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Direct Form I biquad, coefficients from the RBJ Audio EQ Cookbook. */
public final class Biquad {
    public enum Type { LOW_PASS, HIGH_PASS, HIGH_SHELF }

    private float b0 = 1f, b1, b2, a1, a2;
    private float x1, x2, y1, y2;

    public void configure(Type type, float sampleRate, float freqHz, float q) { configure(type, sampleRate, freqHz, q, 0f); }

    public void configure(Type type, float sampleRate, float freqHz, float q, float gainDb) {
        double w0 = 2.0 * Math.PI * Math.max(0.0001f, Math.min(0.499f, freqHz / sampleRate));
        double cosW0 = Math.cos(w0), sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * q);
        double b0d, b1d, b2d, a0d, a1d, a2d;
        switch (type) {
            case LOW_PASS -> {
                b0d = (1.0 - cosW0) / 2.0; b1d = 1.0 - cosW0; b2d = (1.0 - cosW0) / 2.0;
                a0d = 1.0 + alpha; a1d = -2.0 * cosW0; a2d = 1.0 - alpha;
            }
            case HIGH_PASS -> {
                b0d = (1.0 + cosW0) / 2.0; b1d = -(1.0 + cosW0); b2d = (1.0 + cosW0) / 2.0;
                a0d = 1.0 + alpha; a1d = -2.0 * cosW0; a2d = 1.0 - alpha;
            }
            default -> { // HIGH_SHELF
                double a = Math.pow(10.0, gainDb / 40.0);
                double s = 1.0;
                double alphaS = sinW0 / 2.0 * Math.sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0);
                double twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alphaS;
                b0d = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha);
                b1d = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0);
                b2d = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha);
                a0d = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha;
                a1d = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0);
                a2d = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha;
            }
        }
        b0 = (float) (b0d / a0d); b1 = (float) (b1d / a0d); b2 = (float) (b2d / a0d);
        a1 = (float) (a1d / a0d); a2 = (float) (a2d / a0d);
    }

    public float process(float x) {
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        return y;
    }

    public void reset() { x1 = x2 = y1 = y2 = 0f; }
}
