package dev.windowscraft.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvSyncTest {
    /** Pictures arrive 20 ms after their time, sound 150 ms after (sender clock 5 s off ours): gap 130 ms. */
    static AvSync measured() {
        AvSync s = new AvSync();
        long clockOff = 5_000_000_000L, base = (1L << 33) - 90_000 * 3; // (timestamps wrap around in the middle)
        for (int i = 0; i < 200; i++) {
            long ptsV = (base + i * 1500L) & ((1L << 33) - 1), ptsA = (base + i * 1920L) & ((1L << 33) - 1);
            s.arrived(true, ptsV, clockOff + i * 1500L * 1_000_000_000L / 90_000 + 20_000_000L + (i % 3) * 1_000_000L);
            s.arrived(false, ptsA, clockOff + i * 1920L * 1_000_000_000L / 90_000 + 150_000_000L + (i % 5) * 1_000_000L);
        }
        return s;
    }

    @Test
    void measuresHowMuchLaterTheSoundArrives() {
        assertEquals(130, measured().arrivalGapMs(), 4);
    }

    @Test
    void holdsPicturesBySoundWayMinusPictureWayAndRampsUpSlowly() {
        AvSync s = measured();
        long t = 1_000_000_000L;
        // 130 (arrival gap) + 100 (sound still queued here) - the picture's own way after release
        double target = 130 + 100 - AvSync.PICTURE_AFTER_MS;
        double h = s.step(true, 100, t);
        assertEquals(target, h, 4, "starting to watch: straight there");
        h = s.step(false, 100, t + 250_000_000L);
        assertEquals(0, h, 1e-9, "someone uses it: at once");
        h = s.step(true, 100, t + 500_000_000L);
        assertTrue(h < 10, "back to watching: builds up slowly (" + h + ")");
        for (int i = 3; i <= 45; i++) h = s.step(true, 100, t + i * 250_000_000L);
        assertEquals(target, h, 4, "reached after ~10 s");
    }

    @Test
    void neverWithoutSoundDeviceOrBeforeMeasuring() {
        AvSync s = measured();
        for (int i = 0; i < 100; i++) assertEquals(0, s.step(true, Double.NaN, i * 250_000_000L), 1e-9);
        AvSync fresh = new AvSync();
        for (int i = 0; i < 100; i++) assertEquals(0, fresh.step(true, 100, i * 250_000_000L), 1e-9);
        assertTrue(Double.isNaN(fresh.arrivalGapMs()));
    }

    @Test
    void readsTimestampsOfPacketStarts() {
        byte[] p = new byte[188];
        p[0] = 0x47; p[1] = 0x41; p[2] = 0x00; p[3] = 0x10;
        long pts = 8_589_934_000L; // (near the 33-bit top)
        int q = 4;
        p[q + 2] = 1; p[q + 3] = (byte) 0xE0; p[q + 6] = (byte) 0x80; p[q + 7] = (byte) 0x80; p[q + 8] = 5;
        p[q + 9] = (byte) (0x21 | ((pts >> 29) & 0x0E)); p[q + 10] = (byte) (pts >> 22); p[q + 11] = (byte) (0x01 | ((pts >> 14) & 0xFE));
        p[q + 12] = (byte) (pts >> 7); p[q + 13] = (byte) (0x01 | ((pts << 1) & 0xFE));
        assertEquals(pts, AvSync.ptsIfStart(p, 0));
        p[1] = 0x01; // not a start
        assertEquals(-1, AvSync.ptsIfStart(p, 0));
    }
}
