/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio;

import dev.windowscraft.audio.AcousticModel.Environment;
import dev.windowscraft.audio.AcousticModel.ListenerPose;
import dev.windowscraft.audio.AcousticModel.Quality;
import dev.windowscraft.audio.AcousticModel.SourcePlane;
import dev.windowscraft.audio.AcousticModel.SourceState;
import dev.windowscraft.audio.dsp.Biquad;
import dev.windowscraft.audio.dsp.EmitterLayout;
import dev.windowscraft.audio.dsp.Limiter;
import dev.windowscraft.audio.dsp.LoudnessMeter;
import dev.windowscraft.audio.dsp.ParamSmoother;
import dev.windowscraft.audio.dsp.ParametricBinaural;
import dev.windowscraft.audio.dsp.Reverb;
import dev.windowscraft.audio.dsp.StereoPanner;
import dev.windowscraft.audio.dsp.Vec3;

/**
 * Per-display DSP graph: renders an S16LE stereo block as an area-source, direction-aware binaural
 * (or speaker-pan) mix, in place, with distance roll-off, directivity, raytraced occlusion, air
 * absorption, reverb, loudness normalisation and a limiter.
 */
public final class AudioRenderChain {
    private static final float TARGET_LUFS = -14f, MAX_LOUDNESS_BOOST_DB = 15f, MAX_LOUDNESS_CUT_DB = 12f,
            MAX_LOUDNESS_SLEW_DB_PER_SEC = 1f, GAIN_SMOOTH_SECONDS = 0.08f, AZIMUTH_SMOOTH_SECONDS = 0.06f,
            OCCLUSION_SMOOTH_SECONDS = 0.10f, REVERB_SMOOTH_SECONDS = 0.15f, MAX_CUTOFF_HZ = 18000f,
            MIN_OCCLUSION_CUTOFF_HZ = 550f, AIR_ABSORPTION_REF_DISTANCE = 16f,
            AIR_ABSORPTION_MIN_CUTOFF_HZ = 6000f, REVERB_MAX_DECAY_SECONDS = 3.0f;

    private final float sampleRate;
    private final AcousticsEngine engine;
    private volatile SourceState state;

    private final LoudnessMeter loudness;
    private final Limiter limiter;
    /** Tames the boost (volume × loudness make-up) on the source itself, before walls and distance:
     *  a limiter at the end would quietly undo their damping whenever the volume is turned up. */
    private final Limiter boostLimiter;
    private final ParametricBinaural leftBinaural, rightBinaural;
    private final StereoPanner leftPanner = new StereoPanner(), rightPanner = new StereoPanner();
    private final ParamSmoother distanceGainL = new ParamSmoother(GAIN_SMOOTH_SECONDS, 1f);
    private final ParamSmoother distanceGainR = new ParamSmoother(GAIN_SMOOTH_SECONDS, 1f);
    private final ParamSmoother directivitySmoother = new ParamSmoother(GAIN_SMOOTH_SECONDS, 1f);
    private final ParamSmoother azimuthL = new ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f);
    private final ParamSmoother azimuthR = new ParamSmoother(AZIMUTH_SMOOTH_SECONDS, 0f);
    private final Biquad occlusionFilterL = new Biquad(), occlusionFilterR = new Biquad();
    private final Reverb reverb;
    private final ParamSmoother occlusionCutoff = new ParamSmoother(OCCLUSION_SMOOTH_SECONDS, MAX_CUTOFF_HZ);
    private final ParamSmoother occlusionGain = new ParamSmoother(GAIN_SMOOTH_SECONDS, 1f);
    private final ParamSmoother reverbWet = new ParamSmoother(REVERB_SMOOTH_SECONDS, 0f);
    private float[] floatL = new float[0], floatR = new float[0];
    private float lastMakeup = 1f;
    // last block's values, so every gain and position glides across the block instead of
    // stepping at its start (steps every 20 ms are an audible "zipper" crackle)
    private boolean primed;
    private float prevGL, prevGR, prevAzL, prevAzR, prevUser, prevOcc, prevWet;

    AudioRenderChain(float sampleRate, AcousticsEngine engine) {
        this.sampleRate = sampleRate;
        this.engine = engine;
        this.loudness = new LoudnessMeter(sampleRate);
        this.limiter = new Limiter(sampleRate);
        // -5 dBFS: leaves room for the two emitters adding up in each ear, so the final limiter
        // (a safety net) almost never has to act and walls / distance keep their full effect
        this.boostLimiter = new Limiter(sampleRate, 0.56f);
        this.leftBinaural = new ParametricBinaural(sampleRate);
        this.rightBinaural = new ParametricBinaural(sampleRate);
        this.reverb = new Reverb(sampleRate);
    }

    /** Publishes the latest geometry / mix state; called from the game thread. */
    public void updateState(SourceState s) { state = s; }

    /**
     * Processes {@code len} bytes of S16LE stereo PCM in place. {@code legacyGain} is the plain
     * volume used when spatial audio is off or no geometry has arrived yet.
     */
    public void process(byte[] buf, int len, double legacyGain) {
        SourceState st = state;
        Quality tier = engine.quality();
        boolean normalize = engine.normalization();
        if (tier == Quality.OFF || st == null) {
            if (normalize) applyNormalizedGain(buf, len, legacyGain); else applyLegacyGain(buf, len, legacyGain);
            return;
        }
        int frames = len / 4;
        if (frames <= 0) return;
        ensureCapacity(frames);
        decode(buf, frames);

        ListenerPose listener = engine.listener();
        SourcePlane plane = st.plane();
        Vec3 center = new Vec3(plane.centerX(), plane.centerY(), plane.centerZ());
        Vec3 normal = new Vec3(plane.normalX(), plane.normalY(), plane.normalZ());
        Vec3 uAxis = new Vec3(plane.uAxisX(), plane.uAxisY(), plane.uAxisZ());
        Vec3 listenerPos = new Vec3(listener.x(), listener.y(), listener.z());
        Vec3 listenerForward = new Vec3(listener.forwardX(), listener.forwardY(), listener.forwardZ());
        Vec3 listenerUp = new Vec3(listener.upX(), listener.upY(), listener.upZ());
        Vec3 rightRaw = listenerForward.cross(listenerUp);
        // looking straight up/down with a world-up vector: right is undefined; any horizontal one will do
        Vec3 listenerRight = rightRaw.length() < 1e-6 ? new Vec3(1, 0, 0) : rightRaw.normalized();

        float dtBlock = frames / sampleRate;
        // full volume within ~8 blocks (a comfortable viewing distance), fading beyond that
        double refDistance = Math.max(8.0, Math.sqrt(plane.width() * plane.height()) * 0.75);
        Vec3 toCenterDir = listenerPos.minus(center).normalized();
        float directivity = directivitySmoother.next((float) EmitterLayout.directivityGain(normal, toCenterDir), dtBlock);

        Vec3 leftEmitter = EmitterLayout.leftEmitter(center, uAxis, plane.width());
        Vec3 rightEmitter = EmitterLayout.rightEmitter(center, uAxis, plane.width());
        // beyond FADE_START the screen fades out completely by MAX_AUDIBLE (no boost brings it back)
        float reach = farFade(listenerPos.minus(center).length());
        float gL = reach * distanceGainL.next((float) EmitterLayout.distanceGain(listenerPos.minus(leftEmitter).length(), refDistance), dtBlock) * directivity;
        float gR = reach * distanceGainR.next((float) EmitterLayout.distanceGain(listenerPos.minus(rightEmitter).length(), refDistance), dtBlock) * directivity;
        float azL = azimuthL.next(azimuthOf(leftEmitter, listenerPos, listenerForward, listenerRight), dtBlock);
        float azR = azimuthR.next(azimuthOf(rightEmitter, listenerPos, listenerForward, listenerRight), dtBlock);

        boolean binaural = engine.binaural() && tier != Quality.BASIC;
        if (binaural) {
            leftBinaural.updateParams(azL, frames);
            rightBinaural.updateParams(azR, frames);
        }
        boolean advanced = tier == Quality.ADVANCED;
        float userGain = st.muted() ? 0f : st.userVolume();
        float makeupFrom = lastMakeup;
        float makeupStep = (nextMakeup(normalize, dtBlock) - makeupFrom) / frames;
        boolean limit = advanced || normalize;

        Environment env = st.environment();
        float occ = Math.max(0f, Math.min(1f, env.occlusion()));
        float cutoffTarget;
        // occlusion: 0..1 of AcousticsProbe.MAX_WALL_DB of damping by blocks in between
        float wallDb = occ * AcousticsProbe.MAX_WALL_DB;
        if (advanced) {
            // one solid wall already muffles fully; more walls only make it quieter
            float occCutoff = MAX_CUTOFF_HZ * (float) Math.pow(MIN_OCCLUSION_CUTOFF_HZ / MAX_CUTOFF_HZ, Math.min(1f, wallDb / 12f));
            float centerDist = Math.max(AIR_ABSORPTION_REF_DISTANCE, (float) listenerPos.minus(center).length());
            float airCutoff = Math.max(AIR_ABSORPTION_MIN_CUTOFF_HZ, MAX_CUTOFF_HZ * (AIR_ABSORPTION_REF_DISTANCE / centerDist));
            cutoffTarget = Math.min(occCutoff, airCutoff);
        } else {
            cutoffTarget = MAX_CUTOFF_HZ;
        }
        float cutoff = occlusionCutoff.next(cutoffTarget, dtBlock);
        occlusionFilterL.configure(Biquad.Type.LOW_PASS, sampleRate, cutoff, 0.707f);
        occlusionFilterR.configure(Biquad.Type.LOW_PASS, sampleRate, cutoff, 0.707f);
        float occTarget = wallDb >= AcousticsProbe.MAX_WALL_DB - 0.01f ? 0f : (float) Math.pow(10, -wallDb / 20.0);
        float occGain = occlusionGain.next(advanced ? occTarget : 1f, dtBlock);

        float reverbTargetWet = advanced ? Math.max(0f, Math.min(1f, env.reverbWetGain())) : 0f;
        float wetGain = reverbWet.next(reverbTargetWet, dtBlock);
        boolean reverbActive = wetGain > 1e-3f || reverbTargetWet > 1e-3f;
        if (reverbActive) {
            reverb.updateParams(Math.max(0f, Math.min(1f, env.reverbDecaySeconds() / REVERB_MAX_DECAY_SECONDS)), env.reverbDamping());
        }

        if (!primed) {
            primed = true;
            prevGL = gL; prevGR = gR; prevAzL = azL; prevAzR = azR; prevUser = userGain; prevOcc = occGain; prevWet = wetGain;
        }
        float dtSample = 1f / sampleRate, inv = 1f / frames;
        for (int i = 0; i < frames; i++) {
            float t = (i + 1) * inv; // 0 → 1 across the block
            float rawL = floatL[i], rawR = floatR[i];
            loudness.observe(rawL, rawR, dtSample);
            float makeup = makeupFrom + makeupStep * (i + 1);
            float boost = lerp(prevUser, userGain, t) * makeup;
            float bl = rawL * boost, br = rawR * boost;
            if (limit) {
                boostLimiter.process(bl, br);
                bl = boostLimiter.lastL;
                br = boostLimiter.lastR;
            }
            float srcL = advanced ? occlusionFilterL.process(bl) : bl;
            float srcR = advanced ? occlusionFilterR.process(br) : br;
            float common = lerp(prevOcc, occGain, t);
            float l = srcL * lerp(prevGL, gL, t) * common;
            float r = srcR * lerp(prevGR, gR, t) * common;
            float outL, outR;
            if (binaural) {
                leftBinaural.renderSample(l);
                rightBinaural.renderSample(r);
                outL = leftBinaural.lastL + rightBinaural.lastL;
                outR = leftBinaural.lastR + rightBinaural.lastR;
            } else {
                leftPanner.pan(l, lerp(prevAzL, azL, t));
                rightPanner.pan(r, lerp(prevAzR, azR, t));
                outL = leftPanner.lastL + rightPanner.lastL;
                outR = leftPanner.lastR + rightPanner.lastR;
            }
            if (reverbActive) {
                float wet = lerp(prevWet, wetGain, t);
                reverb.process((l + r) * 0.5f);
                outL += reverb.lastL * wet;
                outR += reverb.lastR * wet;
            }
            if (limit) {
                limiter.process(outL, outR);
                outL = limiter.lastL;
                outR = limiter.lastR;
            }
            floatL[i] = outL;
            floatR[i] = outR;
        }
        prevGL = gL; prevGR = gR; prevAzL = azL; prevAzR = azR; prevUser = userGain; prevOcc = occGain; prevWet = wetGain;
        encode(buf, frames);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Screens are heard up to 4 chunks away: full roll-off until 48 blocks, then fading to silence at 64. */
    public static final double FADE_START = 48, MAX_AUDIBLE = 64;

    static float farFade(double distance) {
        if (distance <= FADE_START) return 1f;
        if (distance >= MAX_AUDIBLE) return 0f;
        double t = (distance - FADE_START) / (MAX_AUDIBLE - FADE_START);
        return (float) (0.5 + 0.5 * Math.cos(Math.PI * t));
    }

    public void reset() {
        primed = false;
        loudness.restart();
        limiter.reset();
        boostLimiter.reset();
        leftBinaural.reset();
        rightBinaural.reset();
        distanceGainL.snap(1f);
        distanceGainR.snap(1f);
        directivitySmoother.snap(1f);
        azimuthL.snap(0f);
        azimuthR.snap(0f);
        occlusionFilterL.reset();
        occlusionFilterR.reset();
        reverb.reset();
        occlusionCutoff.snap(MAX_CUTOFF_HZ);
        occlusionGain.snap(1f);
        reverbWet.snap(0f);
    }

    private float nextMakeup(boolean normalize, float dtBlock) {
        float makeup = normalize
                ? loudness.makeupGain(TARGET_LUFS, MAX_LOUDNESS_BOOST_DB, MAX_LOUDNESS_CUT_DB, MAX_LOUDNESS_SLEW_DB_PER_SEC, dtBlock)
                : 1f;
        lastMakeup = makeup;
        return makeup;
    }

    private float prevPlainVolume = -1f;

    private void applyNormalizedGain(byte[] buf, int len, double gain) {
        int frames = len / 4;
        if (frames <= 0) return;
        ensureCapacity(frames);
        decode(buf, frames);
        float makeupFrom = lastMakeup;
        float makeupStep = (nextMakeup(true, frames / sampleRate) - makeupFrom) / frames;
        float dtSample = 1f / sampleRate, volume = (float) gain;
        float volFrom = prevPlainVolume < 0 ? volume : prevPlainVolume;
        prevPlainVolume = volume;
        for (int i = 0; i < frames; i++) {
            float rawL = floatL[i], rawR = floatR[i];
            loudness.observe(rawL, rawR, dtSample);
            float scale = lerp(volFrom, volume, (i + 1) / (float) frames) * (makeupFrom + makeupStep * (i + 1));
            limiter.process(rawL * scale, rawR * scale);
            floatL[i] = limiter.lastL;
            floatR[i] = limiter.lastR;
        }
        encode(buf, frames);
    }

    private void ensureCapacity(int frames) {
        if (floatL.length < frames) {
            floatL = new float[frames];
            floatR = new float[frames];
        }
    }

    private void decode(byte[] buf, int frames) {
        int idx = 0;
        for (int i = 0; i < frames; i++) {
            int lLo = buf[idx] & 0xFF, lHi = buf[idx + 1];
            int rLo = buf[idx + 2] & 0xFF, rHi = buf[idx + 3];
            floatL[i] = ((lHi << 8) | lLo) / 32768f;
            floatR[i] = ((rHi << 8) | rLo) / 32768f;
            idx += 4;
        }
    }

    private void encode(byte[] buf, int frames) {
        int idx = 0;
        for (int i = 0; i < frames; i++) {
            int l = Math.max(-32768, Math.min(32767, (int) (floatL[i] * 32768f)));
            int r = Math.max(-32768, Math.min(32767, (int) (floatR[i] * 32768f)));
            buf[idx] = (byte) l; buf[idx + 1] = (byte) (l >> 8);
            buf[idx + 2] = (byte) r; buf[idx + 3] = (byte) (r >> 8);
            idx += 4;
        }
    }

    private static void applyLegacyGain(byte[] buf, int len, double gain) {
        if (Math.abs(gain - 1.0) < 1e-5) return;
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (buf[i + 1] << 8) | (buf[i] & 0xFF);
            int scaled = Math.max(-32768, Math.min(32767, (int) (sample * gain)));
            buf[i] = (byte) scaled;
            buf[i + 1] = (byte) (scaled >> 8);
        }
    }

    private static float azimuthOf(Vec3 emitter, Vec3 listenerPos, Vec3 forward, Vec3 right) {
        Vec3 rel = emitter.minus(listenerPos);
        float az = (float) Math.atan2(rel.dot(right), rel.dot(forward));
        return Float.isFinite(az) ? az : 0f; // never let NaN into the filters (it would stick forever)
    }
}
