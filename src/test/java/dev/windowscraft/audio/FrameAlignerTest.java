package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Replays how sound reaches the game from the capture helper: 10 ms blocks of quiet speech-like
 * sound, written in two pieces whenever the block contains a newline byte (that's what a
 * line-buffered stdout does), read back in whatever pieces the pipe hands over.
 */
class FrameAlignerTest {
    static byte[] speech(int frames) {
        byte[] b = new byte[frames * 4];
        Random r = new Random(1);
        for (int i = 0; i < frames; i++) {
            short v = (short) (3000 * Math.sin(i * 0.05) * Math.sin(i * 0.0007) + r.nextGaussian() * 30);
            b[i * 4] = (byte) v; b[i * 4 + 1] = (byte) (v >> 8);
            b[i * 4 + 2] = (byte) v; b[i * 4 + 3] = (byte) (v >> 8);
        }
        return b;
    }

    /** The pieces a reader sees: line-buffered writes, and sometimes reads in between them. */
    static java.util.List<byte[]> pipe(byte[] stream, Random r) {
        java.util.List<byte[]> reads = new java.util.ArrayList<>();
        for (int at = 0; at < stream.length; at += 1920) {
            int end = Math.min(stream.length, at + 1920);
            int nl = -1;
            for (int i = end - 1; i >= at; i--) if (stream[i] == '\n') { nl = i; break; }
            if (nl >= 0 && r.nextInt(50) == 0) { // the reader woke up between the two writes
                reads.add(java.util.Arrays.copyOfRange(stream, at, nl + 1));
                reads.add(java.util.Arrays.copyOfRange(stream, nl + 1, end));
            } else {
                reads.add(java.util.Arrays.copyOfRange(stream, at, end));
            }
        }
        return reads;
    }

    static int garbageFrames(byte[] sent, byte[] got) {
        int bad = 0;
        for (int f = 0; f + 3 < Math.min(sent.length, got.length); f += 4) {
            short a = (short) ((got[f + 1] << 8) | (got[f] & 0xFF)), b = (short) ((got[f + 3] << 8) | (got[f + 2] & 0xFF));
            if (Math.abs(a) > 8000 || a != b) bad++; // the test sound never goes above 3100 and is the same in both ears
        }
        return bad;
    }

    @Test
    void oldWayMakesLoudGarbage() {
        byte[] sent = speech(48000 * 10);
        PcmRing ring = new PcmRing(sent.length + 4096);
        for (byte[] piece : pipe(sent, new Random(7))) ring.write(piece, 0, piece.length);
        byte[] got = new byte[ring.available()];
        ring.read(got, 0, got.length);
        int bad = garbageFrames(sent, got);
        System.out.println("Before the fix: " + bad + " garbage frames in 10 s");
        org.junit.jupiter.api.Assertions.assertTrue(bad > 0, "the old path should show the problem");
    }

    @Test
    void alignedPathIsBitExact() {
        byte[] sent = speech(48000 * 10);
        PcmRing ring = new PcmRing(sent.length + 4096);
        FrameAligner al = new FrameAligner();
        for (byte[] piece : pipe(sent, new Random(7))) al.feed(piece, 0, piece.length, ring::write);
        // and some truly random read sizes too
        Random r = new Random(3);
        PcmRing ring2 = new PcmRing(sent.length + 4096);
        FrameAligner al2 = new FrameAligner();
        for (int at = 0; at < sent.length; ) {
            int n = Math.min(sent.length - at, 1 + r.nextInt(5000));
            al2.feed(sent, at, n, ring2::write);
            at += n;
        }
        for (PcmRing rg : new PcmRing[]{ring, ring2}) {
            byte[] got = new byte[rg.available()];
            rg.read(got, 0, got.length);
            assertEquals(sent.length, got.length);
            org.junit.jupiter.api.Assertions.assertArrayEquals(sent, got);
        }
    }
}
