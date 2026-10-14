package dev.windowscraft.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PtsWatchTest {
    @Test
    void agesFromTheWallClockAcrossTheWrap() {
        long now = 1_760_000_000_000L; // ms since 1970
        long pts = Math.floorMod((now - 40) * 90, 1L << 33); // captured 40 ms ago
        assertEquals(40_000_000L, PtsWatch.ageNanos(pts, now));
        // just after the 33-bit wrap: still 40 ms
        long wrapMs = ((1L << 33) / 90) * 7 + 10; // 10 ms past a wrap (in 90 kHz terms, near enough)
        long pts2 = Math.floorMod((wrapMs - 40) * 90, 1L << 33);
        assertEquals(40_000_000L, PtsWatch.ageNanos(pts2, wrapMs));
        // a picture "from the future" (another PC's clock a little ahead) is a negative age
        assertEquals(-25_000_000L, PtsWatch.ageNanos(Math.floorMod((now + 25) * 90, 1L << 33), now));
        // a stream that doesn't use the wall clock (starts at 0) is recognised as such
        assertEquals(Long.MIN_VALUE, PtsWatch.ageNanos(90_000L * 3, now));
    }

    @Test
    void findsOnePtsPerPictureInARealStream() throws Exception {
        Path ts = Path.of(System.getProperty("java.io.tmpdir"), "wc-ptstest.ts");
        Process p;
        try {
            p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30",
                    "-f", "lavfi", "-i", "sine=f=1000:r=48000", "-t", "4", "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-g", "30", "-bf", "0",
                    "-c:a", "aac", "-f", "mpegts", "-mpegts_flags", "+resend_headers", ts.toString()).inheritIO().start();
        } catch (Exception e) {
            Assumptions.abort("ffmpeg not installed");
            return;
        }
        Assumptions.assumeTrue(p.waitFor() == 0, "ffmpeg failed");
        byte[] data = Files.readAllBytes(ts);
        PtsWatch w = new PtsWatch();
        List<Long> seen = new ArrayList<>();
        for (int i = 0; i < data.length; ) { // awkward read sizes: packets split across reads
            int n = Math.min(997 + (i % 501), data.length - i);
            w.feed(data, i, n, seen::add);
            i += n;
        }
        assertEquals(120, seen.size(), "4 s at 30 fps");
        for (int i = 1; i < seen.size(); i++) assertEquals(3000, seen.get(i) - seen.get(i - 1), "1/30 s apart at 90 kHz");
        assertTrue(seen.get(0) >= 0);
    }
}
