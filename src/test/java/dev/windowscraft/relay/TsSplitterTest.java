package dev.windowscraft.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TsSplitterTest {
    /** A 5 s FFmpeg stream with a 1 s GOP (made by the build machine's ffmpeg, if present). */
    @Test
    void findsOneKeyframePerGopAndKeepsEveryByte() throws Exception {
        Path ts = Path.of(System.getProperty("java.io.tmpdir"), "wc-tstest.ts");
        Process p;
        try {
            p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30",
                    "-f", "lavfi", "-i", "sine=f=1000:r=48000", "-t", "5", "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-g", "30", "-bf", "0",
                    "-c:a", "aac", "-f", "mpegts", "-mpegts_flags", "+resend_headers", ts.toString()).inheritIO().start();
        } catch (Exception e) {
            Assumptions.abort("ffmpeg not installed");
            return;
        }
        Assumptions.assumeTrue(p.waitFor() == 0, "ffmpeg failed");
        byte[] data = Files.readAllBytes(ts);
        TsSplitter split = new TsSplitter();
        int keys = 0;
        long total = 0;
        // feed in awkward sizes to exercise packets split across reads
        for (int i = 0; i < data.length; ) {
            int n = Math.min(1000 + (i % 777), data.length - i);
            byte[] buf = java.util.Arrays.copyOfRange(data, i, i + n);
            List<RelayServer.Chunk> out = split.feed(buf, n);
            for (RelayServer.Chunk c : out) { total += c.data().length; if (c.keyframe()) keys++; }
            i += n;
        }
        assertEquals(data.length - data.length % 188, total, "every whole packet passes through");
        assertTrue(keys >= 5 && keys <= 6, "one keyframe per second, got " + keys);
    }
}
