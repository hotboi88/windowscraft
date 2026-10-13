/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/**
 * Real-time loudness estimator and slow makeup-gain controller, in the spirit of ITU-R BS.1770 /
 * EBU R128. Silence and passages far below the running level are gated out of the estimate.
 */
public final class LoudnessMeter {
    private static final float INTEGRATION_SECONDS = 8f, MOMENTARY_SECONDS = 0.4f, GATE_MEAN_SQUARE = 1.2e-6f,
            RELATIVE_GATE = 0.0316f, MIN_HEARD_SECONDS = 0.4f, SETTLE_SECONDS = 3f, SETTLE_SLEW_DB_PER_SECOND = 10f;

    private final Biquad shelf = new Biquad(), highPass = new Biquad();
    private final float momentaryAlpha, integrationAlpha;
    private float momentary = 1e-9f, meanSquare = 1e-9f, heardSeconds;
    private final ParamSmoother gainDbSmoother = new ParamSmoother(0.5f);

    public LoudnessMeter(float sampleRate) {
        shelf.configure(Biquad.Type.HIGH_SHELF, sampleRate, 1500f, 0.7f, 4f);
        highPass.configure(Biquad.Type.HIGH_PASS, sampleRate, 60f, 0.5f);
        momentaryAlpha = 1f - (float) Math.exp(-1f / (MOMENTARY_SECONDS * sampleRate));
        integrationAlpha = 1f - (float) Math.exp(-1f / (INTEGRATION_SECONDS * sampleRate));
    }

    public void observe(float sampleL, float sampleR, float dtPerSample) {
        float mono = (sampleL + sampleR) * 0.5f;
        float weighted = highPass.process(shelf.process(mono));
        float square = weighted * weighted;
        momentary += (square - momentary) * momentaryAlpha;
        if (momentary < GATE_MEAN_SQUARE || momentary < meanSquare * RELATIVE_GATE) return;
        heardSeconds += dtPerSample;
        float alpha = heardSeconds < INTEGRATION_SECONDS ? dtPerSample / heardSeconds : integrationAlpha;
        meanSquare += (square - meanSquare) * alpha;
    }

    public float loudnessLufs() { return -0.691f + 10f * (float) Math.log10(Math.max(meanSquare, 1e-9f)); }

    public float makeupGain(float targetLufs, float maxBoostDb, float maxCutDb, float maxSlewDbPerSecond, float dtSeconds) {
        float current = gainDbSmoother.value();
        if (heardSeconds < MIN_HEARD_SECONDS) return dbToLinear(current);
        float desiredDb = Math.max(-maxCutDb, Math.min(maxBoostDb, targetLufs - loudnessLufs()));
        float slew = heardSeconds < SETTLE_SECONDS ? Math.max(maxSlewDbPerSecond, SETTLE_SLEW_DB_PER_SECOND) : maxSlewDbPerSecond;
        float maxStep = slew * dtSeconds;
        float next = Math.max(-maxStep, Math.min(maxStep, desiredDb - current)) + current;
        gainDbSmoother.snap(next);
        return dbToLinear(next);
    }

    public void restart() {
        shelf.reset();
        highPass.reset();
        momentary = 1e-9f;
        meanSquare = 1e-9f;
        heardSeconds = 0f;
    }

    public void reset() {
        restart();
        gainDbSmoother.snap(0f);
    }

    private static float dbToLinear(float db) { return (float) Math.pow(10.0, db / 20.0); }
}
