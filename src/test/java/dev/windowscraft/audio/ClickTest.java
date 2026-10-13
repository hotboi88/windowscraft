package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.windowscraft.audio.AcousticModel.Environment;
import dev.windowscraft.audio.AcousticModel.ListenerPose;
import dev.windowscraft.audio.AcousticModel.Quality;
import dev.windowscraft.audio.AcousticModel.SourcePlane;
import dev.windowscraft.audio.AcousticModel.SourceState;
import dev.windowscraft.audio.dsp.Biquad;
import org.junit.jupiter.api.Test;

/**
 * Listens for clicks and pops the way an ear would: the test signal has nothing above 2 kHz, so
 * anything the chain puts above 7 kHz is distortion (clipping, a jump in a delay, a gain step).
 * Each 5 ms window's high-frequency level is compared with the signal's level; a window that
 * stands out is a click.
 */
class ClickTest {
    static final int RATE = 48000, BLOCK = 960;

    /** A loud, punchy, low-frequency "song": a chord plus a kick drum four times a second. */
    static float[] music(int frames, double chord, double kick) {
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            double t = i / (double) RATE;
            double v = chord * (Math.sin(2 * Math.PI * 220 * t) + 0.7 * Math.sin(2 * Math.PI * 277.2 * t) + 0.5 * Math.sin(2 * Math.PI * 329.6 * t)) / 2.2;
            double since = t % 0.25;
            double attack = Math.min(1, since / 0.002);                        // 2 ms rise: no click of its own
            double env = 0.5 - 0.5 * Math.cos(Math.PI * attack);
            v += kick * env * Math.exp(-since / 0.06) * Math.sin(2 * Math.PI * (60 + 90 * Math.exp(-since / 0.02)) * since);
            s[i] = (float) v;
        }
        return s;
    }

    static byte[] pcm(float[] l, float[] r, int from, int n) {
        byte[] b = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            int vl = Math.max(-32768, Math.min(32767, Math.round(l[from + i] * 32767f)));
            int vr = Math.max(-32768, Math.min(32767, Math.round(r[from + i] * 32767f)));
            b[i * 4] = (byte) vl; b[i * 4 + 1] = (byte) (vl >> 8);
            b[i * 4 + 2] = (byte) vr; b[i * 4 + 3] = (byte) (vr >> 8);
        }
        return b;
    }

    static float sample(byte[] b, int frame, int ch) {
        int o = frame * 4 + ch * 2;
        return ((short) ((b[o + 1] << 8) | (b[o] & 0xFF))) / 32768f;
    }

    /** Result of listening to one channel. */
    record Verdict(int clicks, double worstDb, double clippedPct) {
        @Override public String toString() { return clicks + " click windows, worst " + Math.round(worstDb) + " dB, clipped " + String.format("%.3f", clippedPct) + "%"; }
    }

    /** Counts 5 ms windows whose >7 kHz content is louder than -50 dB relative to the signal. */
    static Verdict listen(float[] out, int skip) {
        Biquad h1 = new Biquad(), h2 = new Biquad(), h3 = new Biquad();
        h1.configure(Biquad.Type.HIGH_PASS, RATE, 7000, 0.54f);
        h2.configure(Biquad.Type.HIGH_PASS, RATE, 7000, 1.31f);
        h3.configure(Biquad.Type.HIGH_PASS, RATE, 7000, 0.707f);
        double total = 0;
        int n = 0, clipped = 0;
        for (int i = skip; i < out.length; i++) { total += out[i] * out[i]; n++; if (Math.abs(out[i]) >= 0.999f) clipped++; }
        double sigRms = Math.sqrt(total / Math.max(1, n)) + 1e-9;
        int win = RATE / 200, clicks = 0;
        double worst = -200, acc = 0;
        int c = 0;
        for (int i = 0; i < out.length; i++) {
            float h = h3.process(h2.process(h1.process(out[i])));
            if (i < skip) continue;
            acc += h * h;
            if (++c == win) {
                double db = 20 * Math.log10(Math.sqrt(acc / win) / sigRms + 1e-12);
                worst = Math.max(worst, db);
                if (db > -50) clicks++;
                acc = 0;
                c = 0;
            }
        }
        return new Verdict(clicks, worst, 100.0 * clipped / Math.max(1, n));
    }

    static SourceState screen(float volume) {
        return new SourceState(new SourcePlane(0, 0, -5, 0, 0, 1, 1, 0, 0, 0, 1, 0, 4, 2), volume, false, Environment.OPEN_AIR);
    }

    /** Runs a chain over the song; {@code yawAt} gives the listener's yaw (radians) at a time. */
    static float[][] run3d(float volume, double chord, double kick, java.util.function.DoubleUnaryOperator yawAt, double seconds) {
        AcousticsEngine e = new AcousticsEngine(RATE);
        e.setQuality(Quality.ADVANCED);
        e.setBinaural(true);
        e.setNormalization(true);
        AudioRenderChain chain = new AudioRenderChain(RATE, e);
        chain.updateState(screen(volume));
        int frames = (int) (seconds * RATE) / BLOCK * BLOCK;
        float[] in = music(frames, chord, kick);
        float[] l = new float[frames], r = new float[frames];
        for (int b = 0; b < frames; b += BLOCK) {
            double yaw = yawAt.applyAsDouble(b / (double) RATE);
            e.setListener(new ListenerPose(0, 0, 0, -Math.sin(yaw), 0, -Math.cos(yaw), 0, 1, 0));
            byte[] buf = pcm(in, in, b, BLOCK);
            chain.process(buf, buf.length, 1.0);
            for (int i = 0; i < BLOCK; i++) { l[b + i] = sample(buf, i, 0); r[b + i] = sample(buf, i, 1); }
        }
        return new float[][]{l, r};
    }

    @Test
    void boostedSoundDoesNotCrackle() {
        // the case you get after turning the volume all the way up: 500 %
        float[][] out = run3d(5f, 0.5, 0.6, t -> 0, 6);
        Verdict v = listen(out[0], RATE * 2);
        System.out.println("3D at 500 %: " + v);
        assertTrue(v.worstDb() < -60, "crackle when boosted: " + v);
    }

    @Test
    void turningYourHeadDoesNotClick() {
        // looking left and right across the screen quickly (a full swing every second)
        float[][] out = run3d(1f, 0.4, 0.0, t -> 1.2 * Math.sin(2 * Math.PI * t), 6);
        Verdict vl = listen(out[0], RATE * 2), vr = listen(out[1], RATE * 2);
        System.out.println("3D turning: L " + vl + " / R " + vr);
        assertTrue(vl.worstDb() < -60 && vr.worstDb() < -60, "clicks while turning: " + vl + " / " + vr);
    }

    @Test
    void directBoostDoesNotCrackle() throws Exception {
        SpatialAudioPlayer p = new SpatialAudioPlayer(java.util.UUID.randomUUID());
        int frames = 6 * RATE / BLOCK * BLOCK;
        float[] in = music(frames, 0.5, 0.6);
        float[] out = new float[frames];
        java.lang.reflect.Method m = SpatialAudioPlayer.class.getDeclaredMethod("directGain", byte[].class, int.class, double.class);
        m.setAccessible(true);
        for (int b = 0; b < frames; b += BLOCK) {
            byte[] buf = pcm(in, in, b, BLOCK);
            m.invoke(p, buf, buf.length, 5.0);
            for (int i = 0; i < BLOCK; i++) out[b + i] = sample(buf, i, 0);
        }
        p.close();
        Verdict v = listen(out, RATE);
        System.out.println("Direct at 500 %: " + v);
        assertTrue(v.worstDb() < -60, "crackle when boosted (direct): " + v);
    }

    @Test
    void cleanSignalIsClean() {
        float[] in = music(4 * RATE, 0.5, 0.6);
        Verdict v = listen(in, RATE);
        System.out.println("Input itself: " + v);
        assertTrue(v.clicks() == 0, "the test signal itself must be clean: " + v);
    }
}
