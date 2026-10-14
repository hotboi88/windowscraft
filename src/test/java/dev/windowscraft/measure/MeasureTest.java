package dev.windowscraft.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MeasureTest {
    @Test
    void bucketsCoverEveryValueContiguously() {
        for (int i = 0; i + 1 < Histo.BUCKETS; i++) assertEquals(Histo.upper(i), Histo.lower(i + 1), "bucket " + i);
        long[] probes = {0, 1, 31, 32, 33, 63, 64, 1000, 16_666_667L, 1L << 39, (1L << 40) - 1};
        for (long v : probes) {
            int i = Histo.index(v);
            assertTrue(Histo.lower(i) <= v && v < Histo.upper(i), v + " in bucket " + i);
        }
    }

    @Test
    void quantilesAreWithinThreePercent() {
        Histo h = new Histo();
        for (int i = 1; i <= 100_000; i++) h.record(i * 1000L); // 1 µs … 100 ms, evenly
        assertEquals(50_000_000, h.quantile(0.5), 50_000_000 * 0.03);
        assertEquals(95_000_000, h.quantile(0.95), 95_000_000 * 0.03);
        assertEquals(99_000_000, h.quantile(0.99), 99_000_000 * 0.03);
        assertEquals(100_000_000, h.max());
        assertEquals(1000, h.min());
        // the slowest 1 %: 99.0–100 ms, mean ≈ 99.5 ms
        assertEquals(99_500_000, h.meanOfTop(0.01), 99_500_000 * 0.03);
    }

    @Test
    void frameTimesGiveFpsAndOnePercentLows() {
        Histo h = new Histo();
        for (int i = 0; i < 990; i++) h.record(16_666_667L);
        for (int i = 0; i < 10; i++) h.record(50_000_000L); // ten 50 ms hitches in 1000 frames
        assertEquals(16.67, h.quantile(0.5) / 1e6, 0.5);
        assertEquals(50, h.meanOfTop(0.01) / 1e6, 1.5); // 1 % low = 20 fps
    }

    @Test
    void nothingIsRecordedWhileOffAndAReportIsWritten() throws Exception {
        Measure.Metric m = Measure.ms("test.thing", "a test timing");
        Measure.Counter c = Measure.counter("test.count", "a test count");
        assertFalse(Measure.ON);
        m.record(5_000_000);
        c.inc();
        assertEquals(0, m.histo().count(), "off: nothing recorded");
        assertEquals(0, c.get());

        Path dir = Files.createTempDirectory("wc-measure");
        assertTrue(Measure.start("test", dir));
        assertFalse(Measure.start("test", dir), "already on");
        for (int i = 0; i < 100; i++) m.record(i * 100_000L);
        c.add(7);
        Measure.note("stream abc", "1920×1080@60 h264_nvenc");
        Path report = Measure.stop();
        assertFalse(Measure.ON);
        assertNotNull(report);
        String txt = Files.readString(report);
        assertTrue(txt.contains("test.thing"), txt);
        assertTrue(txt.contains("test.count"), txt);
        assertTrue(txt.contains("1920×1080@60"), txt);
        String json = Files.readString(Path.of(report.toString().replace(".txt", ".json")));
        assertTrue(json.contains("\"test.thing\":{\"unit\":\"ms\",\"n\":100"), json);
        assertTrue(json.contains("\"test.count\":7"), json);
        assertNull(Measure.stop(), "stopping twice does nothing");

        // a new start clears the old numbers
        assertTrue(Measure.start("test", dir));
        assertEquals(0, m.histo().count());
        Measure.stop();
    }
}
