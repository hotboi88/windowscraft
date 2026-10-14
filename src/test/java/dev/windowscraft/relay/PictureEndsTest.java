package dev.windowscraft.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class PictureEndsTest {
    static final int VIDEO = 0x100, AUDIO = 0x101;

    /** A video packet (PES) split into TS packets the way FFmpeg's muxer does: padded out at the end. */
    static byte[] pes(int pid, int payloadBytes, boolean sized, int[] cc, long pts) {
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        int len = sized ? 3 + 5 + payloadBytes : 0;
        es.write(0); es.write(0); es.write(1); es.write(0xE0); es.write(len >> 8); es.write(len & 0xFF);
        es.write(0x80); es.write(0x80); es.write(5);
        es.write((int) (0x21 | ((pts >> 29) & 0x0E))); es.write((int) (pts >> 22) & 0xFF); es.write((int) (0x01 | ((pts >> 14) & 0xFE)));
        es.write((int) (pts >> 7) & 0xFF); es.write((int) (0x01 | ((pts << 1) & 0xFE)));
        for (int i = 0; i < payloadBytes; i++) es.write(0x42);
        byte[] d = es.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = 0;
        boolean first = true;
        while (at < d.length) {
            byte[] p = new byte[188];
            p[0] = 0x47;
            p[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8));
            p[2] = (byte) pid;
            int left = d.length - at;
            if (left >= 184) {
                p[3] = (byte) (0x10 | cc[0]);
                System.arraycopy(d, at, p, 4, 184);
                at += 184;
            } else {
                p[3] = (byte) (0x30 | cc[0]);
                int af = 183 - left;
                p[4] = (byte) af;
                if (af > 0) p[5] = 0;
                for (int i = 6; i < 5 + af; i++) p[i] = (byte) 0xFF;
                System.arraycopy(d, at, p, 5 + af, left);
                at += left;
            }
            cc[0] = (cc[0] + 1) & 15;
            first = false;
            out.write(p, 0, 188);
        }
        return out.toByteArray();
    }

    static int[] ccs(byte[] ts, int pid) {
        java.util.List<Integer> l = new java.util.ArrayList<>();
        for (int o = 0; o + 188 <= ts.length; o += 188)
            if ((((ts[o + 1] & 0x1F) << 8) | (ts[o + 2] & 0xFF)) == pid) l.add(ts[o + 3] & 0x0F);
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    @Test
    void bigPictureGetsATrailingPacketAndStaysContinuous() {
        int[] vcc = {5}, acc = {0};
        PictureEnds ends = new PictureEnds();
        byte[] big = pes(VIDEO, 70_000, false, vcc, 90_000);       // too big to say its length
        byte[] sound = pes(AUDIO, 300, true, acc, 90_000);
        byte[] out = ends.process(cat(big, sound), VIDEO);
        assertEquals(big.length + sound.length + 188, out.length, "one packet added");
        byte[] last = java.util.Arrays.copyOfRange(out, out.length - 188, out.length);
        assertEquals(VIDEO, ((last[1] & 0x1F) << 8) | (last[2] & 0xFF));
        assertTrue((last[1] & 0x40) != 0, "starts a new video packet");
        int[] c = ccs(out, VIDEO);
        for (int i = 1; i < c.length; i++) assertEquals((c[i - 1] + 1) & 15, c[i], "continuous at " + i);
        // the next picture's packets are moved on by one
        byte[] next = pes(VIDEO, 70_000, false, vcc, 91_500);
        byte[] out2 = ends.process(next.clone(), VIDEO);
        int[] c2 = ccs(out2, VIDEO);
        assertEquals((c[c.length - 1] + 1) & 15, c2[0], "carries on after the added packet");
    }

    @Test
    void sizedPicturesAndPartsAreLeftAlone() {
        int[] vcc = {0};
        PictureEnds ends = new PictureEnds();
        byte[] small = pes(VIDEO, 20_000, true, vcc, 90_000);
        assertSame(small, ends.process(small, VIDEO), "a picture that says its length: untouched");
        // a big one arriving in two runs: nothing added until its end has come
        byte[] big = pes(VIDEO, 70_000, false, vcc, 91_500);
        byte[] first = java.util.Arrays.copyOfRange(big, 0, 188 * 100);
        byte[] rest = java.util.Arrays.copyOfRange(big, 188 * 100, big.length);
        byte[] a = ends.process(first, VIDEO);
        assertEquals(first.length, a.length);
        byte[] b = ends.process(rest, VIDEO);
        assertEquals(rest.length + 188, b.length);
        // and the next big one's start right behind it (it ends the earlier one itself): nothing added
        int[] vcc2 = {0};
        PictureEnds e2 = new PictureEnds();
        byte[] p1 = pes(VIDEO, 70_000, false, vcc2, 90_000), p2 = pes(VIDEO, 70_000, false, vcc2, 91_500);
        byte[] both = cat(p1, java.util.Arrays.copyOfRange(p2, 0, 188 * 3));
        assertEquals(both.length, e2.process(both, VIDEO).length);
    }

    @Test
    void theAddedPacketIsWellFormed() {
        byte[] p = PictureEnds.nudge(0x100, 7, 123_456);
        assertEquals(188, p.length);
        assertEquals(0x47, p[0] & 0xFF);
        assertEquals(7, p[3] & 0x0F);
        int payload = 5 + (p[4] & 0xFF);
        assertArrayEquals(new byte[]{0, 0, 1, (byte) 0xE0}, java.util.Arrays.copyOfRange(p, payload, payload + 4));
        int len = ((p[payload + 4] & 0xFF) << 8) | (p[payload + 5] & 0xFF);
        assertEquals(188 - payload - 6, len, "says exactly how long it is");
        assertEquals('Q', p[payload + 14]);
        assertTrue(PictureEnds.padded(p, 0));
    }
}
