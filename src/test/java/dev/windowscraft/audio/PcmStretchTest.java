package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PcmStretchTest {
    private static byte[] tone(long from, int frames, double hz) {
        byte[] b = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short v = (short) Math.round(Math.sin(2 * Math.PI * hz * (from + i) / 48_000.0) * 12_000);
            for (int c = 0; c < 2; c++) {
                b[i * 4 + c * 2] = (byte) v;
                b[i * 4 + c * 2 + 1] = (byte) (v >> 8);
            }
        }
        return b;
    }

    private static short left(byte[] b, int frame) {
        return (short) ((b[frame * 4] & 0xFF) | (b[frame * 4 + 1] << 8));
    }

    @Test
    void unstretchedIsTheSameSoundTwoFramesLate() {
        PcmStretch s = new PcmStretch();
        byte[] out = new byte[480 * 4];
        s.stretch(tone(0, 480, 1000), 480, out, 480);
        s.stretch(tone(480, 480, 1000), 480, out, 480);
        byte[] want = tone(478, 480, 1000);
        for (int i = 0; i < 480; i++) assertEquals(left(want, i), left(out, i));
    }

    @Test
    void stretchingStaysSmoothAcrossChunks() {
        // faster and slower in turn: a 1 kHz tone never jumps more than a sine that's barely
        // faster could (no clicks at the joins)
        PcmStretch s = new PcmStretch();
        byte[] out = new byte[480 * 4];
        long from = 0;
        int last = 0;
        double steepest = 2 * Math.PI * 1000 / 48_000 * 12_000 * 1.01;
        for (int k = 0; k < 40; k++) {
            int in = 480 + (k % 5) - 2;
            s.stretch(tone(from, in, 1000), in, out, 480);
            from += in;
            for (int i = 0; i < 480; i++) {
                int v = left(out, i);
                if (k > 0 || i > 2) assertTrue(Math.abs(v - last) <= steepest + 2, "jump of " + (v - last) + " at chunk " + k + " frame " + i);
                last = v;
            }
        }
    }
}
