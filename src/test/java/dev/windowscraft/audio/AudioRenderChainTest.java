package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.windowscraft.audio.AcousticModel.Environment;
import dev.windowscraft.audio.AcousticModel.ListenerPose;
import dev.windowscraft.audio.AcousticModel.Quality;
import dev.windowscraft.audio.AcousticModel.SourcePlane;
import dev.windowscraft.audio.AcousticModel.SourceState;
import org.junit.jupiter.api.Test;

/** Ported from Dream Displays' AudioRenderChainTest, plus spatial checks for our use. */
class AudioRenderChainTest {
    private static final float RATE = 48000f;
    private static final int BLOCK = 960; // 20 ms

    private static AcousticsEngine engine(Quality q, boolean binaural, boolean normalization) {
        AcousticsEngine e = new AcousticsEngine(RATE);
        e.setQuality(q);
        e.setBinaural(binaural);
        e.setNormalization(normalization);
        return e;
    }

    private static SourceState state(Environment env) {
        // 4×2 screen at z = -5 facing +z (toward a listener at the origin looking -z); u = viewer's right
        return new SourceState(new SourcePlane(0, 0, -5, 0, 0, 1, 1, 0, 0, 0, 1, 0, 4, 2), 1f, false, env);
    }

    private static byte[] tone(double amplitude) {
        byte[] buf = new byte[BLOCK * 4];
        for (int i = 0; i < BLOCK; i++) {
            short v = (short) (Short.MAX_VALUE * amplitude * Math.sin(2 * Math.PI * 440.0 * i / RATE));
            buf[i * 4] = (byte) v; buf[i * 4 + 1] = (byte) (v >> 8);
            buf[i * 4 + 2] = (byte) v; buf[i * 4 + 3] = (byte) (v >> 8);
        }
        return buf;
    }

    private static double rms(byte[] buf, int channel) {
        double sum = 0;
        int n = buf.length / 4;
        for (int i = 0; i < n; i++) {
            int o = i * 4 + channel * 2;
            double s = ((buf[o + 1] << 8) | (buf[o] & 0xFF)) / 32768.0;
            sum += s * s;
        }
        return Math.sqrt(sum / n);
    }

    private static double settle(AudioRenderChain chain, double amp, int channel) {
        double last = 0;
        for (int i = 0; i < 300; i++) { byte[] b = tone(amp); chain.process(b, b.length, 1.0); last = rms(b, channel); }
        return last;
    }

    @Test
    void offTierAppliesPlainGain() {
        AudioRenderChain chain = new AudioRenderChain(RATE, engine(Quality.OFF, true, false));
        byte[] buf = {(byte) 0x10, (byte) 0x27, (byte) 0xC0, (byte) 0xE0}; // 10000, -8000
        chain.process(buf, buf.length, 0.5);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x13, (byte) 0x60, (byte) 0xF0}, buf); // 5000, -4000
    }

    @Test
    void spatialChainStaysInRange() {
        AudioRenderChain chain = new AudioRenderChain(RATE, engine(Quality.ADVANCED, true, false));
        chain.updateState(state(new Environment(0.5f, 2f, 0.6f, 0.3f)));
        for (int k = 0; k < 50; k++) {
            byte[] b = tone(0.9);
            chain.process(b, b.length, 1.0);
            assertTrue(rms(b, 0) < 1.0 && !Double.isNaN(rms(b, 0)));
        }
    }

    @Test
    void normalizationEvensOutLevels() {
        for (Quality q : new Quality[]{Quality.OFF, Quality.ADVANCED}) {
            AudioRenderChain a = new AudioRenderChain(RATE, engine(q, false, true));
            AudioRenderChain b = new AudioRenderChain(RATE, engine(q, false, true));
            a.updateState(state(Environment.OPEN_AIR));
            b.updateState(state(Environment.OPEN_AIR));
            double apartDb = 20 * Math.log10(settle(b, 0.6, 0) / settle(a, 0.06, 0));
            assertTrue(apartDb < 2.0, q + ": 20 dB apart ended " + apartDb + " dB apart");
        }
    }

    @Test
    void screenToTheRightIsLouderOnTheRight() {
        AcousticsEngine e = engine(Quality.ADVANCED, true, false);
        AudioRenderChain chain = new AudioRenderChain(RATE, e);
        chain.updateState(state(Environment.OPEN_AIR));
        e.setListener(new ListenerPose(0, 0, 0, -1, 0, 0, 0, 1, 0)); // facing -x: the screen (at -z) is on your right
        double left = 0, right = 0;
        for (int i = 0; i < 100; i++) { byte[] b = tone(0.3); chain.process(b, b.length, 1.0); left = rms(b, 0); right = rms(b, 1); }
        assertTrue(right > left * 1.2, "right " + right + " vs left " + left);
    }

    @Test
    void distanceAndWallsMakeItQuieter() {
        AcousticsEngine e = engine(Quality.ADVANCED, true, false);
        AudioRenderChain near = new AudioRenderChain(RATE, e);
        near.updateState(state(Environment.OPEN_AIR));
        double nearRms = settle(near, 0.3, 0);

        AcousticsEngine far = engine(Quality.ADVANCED, true, false);
        far.setListener(new ListenerPose(0, 0, 40, 0, 0, -1, 0, 1, 0));
        AudioRenderChain farChain = new AudioRenderChain(RATE, far);
        farChain.updateState(state(Environment.OPEN_AIR));
        assertTrue(settle(farChain, 0.3, 0) < nearRms * 0.5, "45 blocks away should be much quieter");

        AudioRenderChain walled = new AudioRenderChain(RATE, e);
        walled.updateState(state(new Environment(1f, 0f, 0f, 0f)));
        assertTrue(settle(walled, 0.3, 0) < nearRms * 0.75, "a wall in between should muffle it");
    }

    @Test
    void lookingStraightUpOrDownStaysAudible() {
        // a world-up "up" vector with a straight-up gaze used to make NaN that silenced the stream for good
        AcousticsEngine e = engine(Quality.ADVANCED, true, true);
        AudioRenderChain chain = new AudioRenderChain(RATE, e);
        chain.updateState(state(Environment.OPEN_AIR));
        e.setListener(new ListenerPose(0, 0, 0, 0, 1, 0, 0, 1, 0));
        for (int i = 0; i < 50; i++) { byte[] b = tone(0.3); chain.process(b, b.length, 1.0); }
        e.setListener(new ListenerPose(0, 0, 0, 0, 0, -1, 0, 1, 0)); // look back at the screen
        double level = settle(chain, 0.3, 0);
        assertTrue(level > 0.01, "still audible after looking up (rms " + level + ")");
    }

    @Test
    void delayLineNeverReadsOutOfBounds() {
        dev.windowscraft.audio.dsp.FractionalDelayLine d = new dev.windowscraft.audio.dsp.FractionalDelayLine(64);
        for (int i = 0; i < 1000; i++) {
            d.push(i);
            for (float delay : new float[]{0f, 1e-9f, 0.999999f, 62f, 63f, 1000f, -1f, Float.NaN}) d.read(delay);
        }
    }
}
