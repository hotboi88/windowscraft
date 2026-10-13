package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The playback timing corrections (skip or repeat 10 ms) must not click. */
class CrossfadeTest {
    private static final int FRAMES = 960, ADJ = 480, FADE = 192;

    private static byte[] sine(long start, int frames) {
        byte[] b = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short v = (short) (20000 * Math.sin(2 * Math.PI * 440.0 * (start + i) / 48000.0));
            b[i * 4] = (byte) v; b[i * 4 + 1] = (byte) (v >> 8);
            b[i * 4 + 2] = (byte) v; b[i * 4 + 3] = (byte) (v >> 8);
        }
        return b;
    }

    private static int maxStep(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        int max = 0, prev = (short) ((all[1] << 8) | (all[0] & 0xFF));
        for (int i = 4; i < all.length; i += 4) {
            int v = (short) ((all[i + 1] << 8) | (all[i] & 0xFF));
            max = Math.max(max, Math.abs(v - prev));
            prev = v;
        }
        return max;
    }

    // a 440 Hz sine at 20000 moves at most ~1150 per sample; a click jumps by thousands more
    private static final int SMOOTH = 1400;

    @Test
    void skippingTenMillisecondsIsSmooth() {
        byte[] played = sine(0, FRAMES);
        byte[] next = sine(FRAMES, FRAMES);
        SpatialAudioPlayer.crossfade(Arrays.copyOf(next, FADE * 4), next, ADJ);
        byte[] tail = Arrays.copyOfRange(next, ADJ * 4, next.length);
        assertTrue(maxStep(played, tail) < SMOOTH, "skip seam step " + maxStep(played, tail));
    }

    @Test
    void repeatingTenMillisecondsIsSmooth() {
        byte[] played = sine(0, FRAMES);
        byte[] next = sine(FRAMES, FRAMES);
        byte[] extra = Arrays.copyOf(next, ADJ * 4);
        byte[] continuation = Arrays.copyOfRange(next, ADJ * 4, (ADJ + FADE) * 4);
        SpatialAudioPlayer.crossfade(continuation, next, 0);
        byte[] firstPart = new byte[played.length + extra.length];
        System.arraycopy(played, 0, firstPart, 0, played.length);
        System.arraycopy(extra, 0, firstPart, played.length, extra.length);
        assertTrue(maxStep(firstPart, next) < SMOOTH, "repeat seam step " + maxStep(firstPart, next));
    }

    @Test
    void withoutTheCrossfadeItWouldClick() {
        byte[] played = sine(0, FRAMES);
        byte[] next = sine(FRAMES, FRAMES);
        byte[] tail = Arrays.copyOfRange(next, ADJ * 4 + 4 * 17, next.length); // arbitrary cut, no fade
        assertTrue(maxStep(played, tail) > SMOOTH * 2, "the test can tell a click from a smooth seam");
    }
}
