/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors; reworked for WindowsCraft so
 * that every parameter changes continuously. Licensed under the GNU Lesser General Public
 * License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/**
 * Renders one mono emitter into a binaural pair: parametric ITD (Woodworth) + ILD (broadband gain
 * and head shadow on the far ear). One instance per emitter.
 *
 * <p>Click-free: each ear has its own delay, shadow amount and gain, all of which pass smoothly
 * through zero when the sound moves from one side to the other (nothing is swapped between ears),
 * and all of them glide sample by sample from one block's value to the next instead of jumping
 * at block boundaries — a jump in a delay is an audible tick, many of them a crackle.
 */
public final class ParametricBinaural {
    private static final double HEAD_RADIUS_M = 0.0875, SPEED_OF_SOUND = 343.0, HALF_PI = Math.PI / 2.0;
    private static final float MAX_ILD_ATTEN = 0.35f, SHADOW_CUTOFF_HZ = 2200f;

    private final float sampleRate;
    private final FractionalDelayLine delayLine = new FractionalDelayLine(64);
    private final Biquad shadowL = new Biquad(), shadowR = new Biquad();
    // per-ear parameters: current (gliding) value, and per-sample step toward this block's target
    private float delayL, delayR, shadeL, shadeR, gainL = 1f, gainR = 1f;
    private float dDelayL, dDelayR, dShadeL, dShadeR, dGainL, dGainR;
    private int glide;
    public float lastL, lastR;

    public ParametricBinaural(float sampleRate) {
        this.sampleRate = sampleRate;
        shadowL.configure(Biquad.Type.LOW_PASS, sampleRate, SHADOW_CUTOFF_HZ, 0.7f);
        shadowR.configure(Biquad.Type.LOW_PASS, sampleRate, SHADOW_CUTOFF_HZ, 0.7f);
    }

    /** Sets where the sound is (0 = ahead, positive = listener's right); reached over the next {@code frames} samples. */
    public void updateParams(double azimuthRad, int frames) {
        double a = StereoPanner.clamp(azimuthRad, -HALF_PI, HALF_PI);
        // positive: the sound is on the right, so it reaches the left ear later
        float itd = (float) ((HEAD_RADIUS_M / SPEED_OF_SOUND) * (a + Math.sin(a)) * sampleRate);
        float side = (float) Math.sin(a);
        float tDelayL = Math.max(0f, itd), tDelayR = Math.max(0f, -itd);
        float tShadeL = Math.max(0f, side), tShadeR = Math.max(0f, -side);
        float tGainL = 1f - MAX_ILD_ATTEN * tShadeL, tGainR = 1f - MAX_ILD_ATTEN * tShadeR;
        glide = Math.max(1, frames);
        dDelayL = (tDelayL - delayL) / glide; dDelayR = (tDelayR - delayR) / glide;
        dShadeL = (tShadeL - shadeL) / glide; dShadeR = (tShadeR - shadeR) / glide;
        dGainL = (tGainL - gainL) / glide; dGainR = (tGainR - gainR) / glide;
    }

    /** Old one-argument form: the change is spread over a 20 ms block. */
    public void updateParams(double azimuthRad) { updateParams(azimuthRad, (int) (sampleRate / 50)); }

    public void renderSample(float sample) {
        if (glide > 0) {
            delayL += dDelayL; delayR += dDelayR;
            shadeL += dShadeL; shadeR += dShadeR;
            gainL += dGainL; gainR += dGainR;
            glide--;
        }
        delayLine.push(sample);
        float l = delayLine.read(delayL), r = delayLine.read(delayR);
        float fl = shadowL.process(l), fr = shadowR.process(r); // always run, so switching sides never starts a cold filter
        lastL = (l + (fl - l) * shadeL) * gainL;
        lastR = (r + (fr - r) * shadeR) * gainR;
    }

    public void reset() {
        delayLine.reset();
        shadowL.reset();
        shadowR.reset();
        delayL = delayR = shadeL = shadeR = 0f;
        gainL = gainR = 1f;
        glide = 0;
    }
}
