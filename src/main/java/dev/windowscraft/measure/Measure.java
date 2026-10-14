package dev.windowscraft.measure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 0 measurements: timings, counts and resource use along the whole path of a screen, off by
 * default. While off, every measuring point costs one read of {@link #ON}; nothing is recorded,
 * timed, sampled or written.
 *
 * <p>Switched on by {@code /windowscraft measure start} (client), {@code /windowscraft-server
 * measure start} (server console or an operator), or for automated runs by the JVM property
 * {@code windowscraft.measure=SECONDS[@DELAY]}: start after DELAY seconds, then write a report every
 * SECONDS (each window on its own, the numbers reset in between) until the game closes.
 *
 * <p>Reports (text for people, JSON for scripts) go to {@code windowscraft/measurements/} in the
 * game folder. They hold numbers and stream/encoder facts only — no window titles, names or paths.
 */
public final class Measure {
    private static final Logger LOG = LoggerFactory.getLogger("WindowsCraft measure");

    /** Read on every measuring point: false (the default) means do nothing at all. */
    public static volatile boolean ON;

    /** A timing or a value; recorded as a long, shown divided by {@code scale} in {@code unit}. */
    public static final class Metric {
        final String name, unit, what;
        final double scale;
        volatile Histo h = new Histo();

        Metric(String name, String unit, double scale, String what) {
            this.name = name;
            this.unit = unit;
            this.scale = scale;
            this.what = what;
        }

        public void record(long v) {
            if (!ON) return;
            h.record(v);
            RECORDS.incrementAndGet();
        }

        /** Records the time since {@code startNanos} (a {@link System#nanoTime()}). */
        public void since(long startNanos) {
            if (!ON || startNanos == 0) return;
            h.record(System.nanoTime() - startNanos);
            RECORDS.incrementAndGet();
        }

        public Histo histo() { return h; }
    }

    /** Something that happened, counted. */
    public static final class Counter {
        final String name, what;
        final AtomicLong n = new AtomicLong();

        Counter(String name, String what) {
            this.name = name;
            this.what = what;
        }

        public void add(long k) {
            if (!ON) return;
            n.addAndGet(k);
            RECORDS.incrementAndGet();
        }

        public void inc() { add(1); }
        public long get() { return n.get(); }
    }

    private static final Map<String, Metric> METRICS = new ConcurrentHashMap<>();
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, String> NOTES = new ConcurrentHashMap<>();
    private static final AtomicLong RECORDS = new AtomicLong();
    private static final List<Runnable> ON_START = new CopyOnWriteArrayList<>();

    /** A timing recorded in nanoseconds, shown in milliseconds. */
    public static Metric ms(String name, String what) {
        return METRICS.computeIfAbsent(name, k -> new Metric(k, "ms", 1e6, what));
    }

    /** A plain value (shown as recorded, or divided by {@code scale}). */
    public static Metric value(String name, String unit, double scale, String what) {
        return METRICS.computeIfAbsent(name, k -> new Metric(k, unit, scale, what));
    }

    public static Counter counter(String name, String what) {
        return COUNTERS.computeIfAbsent(name, k -> new Counter(k, what));
    }

    /** A fact for the report (an encoder, a stream's size…); the newest one per key is kept. */
    public static void note(String key, String value) {
        if (ON) NOTES.put(key, value);
    }

    // ---- the game's own frames (render thread) ----
    private static final Metric FRAME = ms("frame.interval", "one game frame to the next (render thread) — FPS and 1 % lows");
    public static final Metric POINTING = ms("frame.wc_pointing", "WindowsCraft's per-frame pointing/seating work before the world is drawn");
    private static long lastFrame;

    /** Render thread, once per frame: records the frame time; returns now (or 0 while off) for timing what follows. */
    public static long frame() {
        if (!ON) {
            lastFrame = 0;
            return 0;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) FRAME.record(now - lastFrame);
        lastFrame = now;
        return now;
    }

    /** Runs whenever measuring starts (e.g. to note facts that were decided before it was on). */
    public static void onStart(Runnable r) { ON_START.add(r); }

    /** Runs whenever measuring stops (e.g. to switch a helper's timing back off). */
    public static void onStop(Runnable r) { ON_STOP.add(r); }

    private static final List<Runnable> ON_STOP = new CopyOnWriteArrayList<>();

    /**
     * A capture helper's "when captured" stamp (100 ns units of the performance counter) as a
     * {@link System#nanoTime()}, or 0. On Windows both come from the same counter.
     */
    public static long helperNanos(String token) {
        try {
            long t = Long.parseLong(token);
            return t > 0 ? t * 100 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- start / stop -------------------------------------------------------------------------

    private static String side = "client";
    private static Path dir;
    private static long startedNanos, startedWall;
    private static SystemSampler sampler;
    private static Thread autoStop;
    private static double recordCostNs = -1;

    public static synchronized boolean running() { return ON; }

    /** Starts measuring (clears earlier numbers). False if it was already on. */
    public static synchronized boolean start(String sideName, Path outDir) {
        if (ON) return false;
        side = sideName;
        dir = outDir;
        if (recordCostNs < 0) recordCostNs = benchmarkRecord();
        reset();
        startedNanos = System.nanoTime();
        startedWall = System.currentTimeMillis();
        sampler = new SystemSampler(side);
        ON = true;
        sampler.start();
        for (Runnable r : ON_START) {
            try { r.run(); } catch (RuntimeException e) { LOG.debug("onStart hook failed", e); }
        }
        LOG.info("Measuring on ({})", side);
        return true;
    }

    /** Stops measuring and writes the report; returns the text report's path (null if it wasn't on). */
    public static synchronized Path stop() {
        if (!ON) return null;
        Path p = write();
        ON = false;
        for (Runnable r : ON_STOP) {
            try { r.run(); } catch (RuntimeException e) { LOG.debug("onStop hook failed", e); }
        }
        if (sampler != null) sampler.stop();
        sampler = null;
        if (autoStop != null) autoStop.interrupt();
        autoStop = null;
        LOG.info("Measuring off; report: {}", p);
        return p;
    }

    /** Starts measuring and stops by itself after {@code seconds}, writing the report. */
    public static synchronized boolean startFor(String sideName, Path outDir, int seconds, java.util.function.Consumer<Path> done) {
        if (!start(sideName, outDir)) return false;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            Path p = stop();
            if (done != null && p != null) done.accept(p);
        }, "WindowsCraft measure timer");
        t.setDaemon(true);
        autoStop = t;
        t.start();
        return true;
    }

    /** Writes a report of the numbers so far and starts a new window (measuring stays on). */
    public static synchronized Path rollover() { return rollover(null); }

    /** The same, naming the new window's report {@code newSide} (null: unchanged). */
    public static synchronized Path rollover(String newSide) {
        if (!ON) return null;
        Path p = write();
        if (newSide != null) side = newSide;
        reset();
        if (sampler != null) sampler.resetBaseline();
        startedNanos = System.nanoTime();
        startedWall = System.currentTimeMillis();
        return p;
    }

    public static synchronized String status() {
        if (!ON) return "Measuring is off";
        return String.format(Locale.ROOT, "Measuring (%s) for %.0f s so far", side, (System.nanoTime() - startedNanos) / 1e9);
    }

    /**
     * Automated runs: {@code -Dwindowscraft.measure=SECONDS[@DELAY]} starts after DELAY seconds
     * (default 0) and writes a report every SECONDS until the game closes.
     */
    public static void autoFromProperty(String sideName, Path outDir) {
        String p = System.getProperty("windowscraft.measure");
        if (p == null || p.isBlank()) return;
        String[] parts = p.split("@");
        int window, delay;
        try {
            window = Math.max(5, Integer.parseInt(parts[0].trim()));
            delay = parts.length > 1 ? Math.max(0, Integer.parseInt(parts[1].trim())) : 0;
        } catch (NumberFormatException e) {
            LOG.warn("windowscraft.measure should be SECONDS or SECONDS@DELAY, not '{}'", p);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay * 1000L);
                start(sideName, outDir);
                while (true) {
                    Thread.sleep(window * 1000L);
                    rollover();
                }
            } catch (InterruptedException ignored) {
            }
        }, "WindowsCraft measure windows");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(Measure::stop, "WindowsCraft measure exit"));
    }

    private static void reset() {
        for (Metric m : METRICS.values()) m.h = new Histo();
        for (Counter c : COUNTERS.values()) c.n.set(0);
        NOTES.clear();
        RECORDS.set(0);
    }

    /** What one record costs on this PC (ns), to report the measuring's own overhead. */
    private static double benchmarkRecord() {
        Histo h = new Histo();
        int n = 200_000;
        for (int i = 0; i < 20_000; i++) h.record(i * 37L); // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) h.record(i * 7919L & 0xFFFFFF);
        return (System.nanoTime() - t0) / (double) n;
    }

    // ---- the report ---------------------------------------------------------------------------

    private static Path write() {
        double secs = Math.max(1e-3, (System.nanoTime() - startedNanos) / 1e9);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder txt = new StringBuilder();
        StringBuilder json = new StringBuilder();
        txt.append("WindowsCraft measurement (").append(side).append(") — ").append(stamp)
                .append(String.format(Locale.ROOT, ", %.1f s%n", secs));
        txt.append("Times in ms. Columns: median / P95 / P99 / max (n = how many). Ages compare two clocks;\n");
        txt.append("on one PC they're exact, between PCs they include the difference between their clocks.\n\n");
        json.append("{\"side\":\"").append(side).append("\",\"seconds\":").append(fmt(secs)).append(",\"startedMs\":").append(startedWall);

        // metrics, sorted by name (names are grouped by their first part: frame., wall., enc., …)
        TreeMap<String, Metric> sorted = new TreeMap<>(METRICS);
        json.append(",\"metrics\":{");
        boolean first = true;
        String group = "";
        for (Metric m : sorted.values()) {
            Histo h = m.h;
            if (h.count() == 0) continue;
            String g = m.name.contains(".") ? m.name.substring(0, m.name.indexOf('.')) : m.name;
            if (!g.equals(group)) {
                txt.append('\n').append(g).append('\n');
                group = g;
            }
            double s = m.scale;
            txt.append(String.format(Locale.ROOT, "  %-28s %9s / %9s / %9s / %9s %s  n=%d", m.name,
                    num(h.quantile(0.5) / s), num(h.quantile(0.95) / s), num(h.quantile(0.99) / s), num(h.max() / s),
                    m.unit.equals("ms") ? "  " : m.unit, h.count()));
            if (m.name.equals("frame.interval")) {
                double meanMs = h.mean() / s, lowMs = h.meanOfTop(0.01) / s;
                txt.append(String.format(Locale.ROOT, "   → %.1f fps average, 1%% low %.1f fps", 1000 / meanMs, 1000 / lowMs));
            }
            txt.append("\n      ").append(m.what).append('\n');
            if (!first) json.append(',');
            first = false;
            json.append('"').append(m.name).append("\":{\"unit\":\"").append(m.unit).append("\",\"n\":").append(h.count())
                    .append(",\"mean\":").append(fmt(h.mean() / s)).append(",\"p50\":").append(fmt(h.quantile(0.5) / s))
                    .append(",\"p95\":").append(fmt(h.quantile(0.95) / s)).append(",\"p99\":").append(fmt(h.quantile(0.99) / s))
                    .append(",\"min\":").append(fmt(h.min() / s)).append(",\"max\":").append(fmt(h.max() / s))
                    .append(",\"top1mean\":").append(fmt(h.meanOfTop(0.01) / s)).append('}');
        }
        json.append('}');

        txt.append("\ncounts (per second in brackets)\n");
        json.append(",\"counters\":{");
        first = true;
        for (Counter c : new TreeMap<>(COUNTERS).values()) {
            long v = c.n.get();
            if (v == 0) continue;
            txt.append(String.format(Locale.ROOT, "  %-28s %12d  (%.1f/s)  %s%n", c.name, v, v / secs, c.what));
            if (!first) json.append(',');
            first = false;
            json.append('"').append(c.name).append("\":").append(v);
        }
        json.append('}');

        if (!NOTES.isEmpty()) {
            txt.append("\nfacts\n");
            json.append(",\"notes\":{");
            first = true;
            for (var e : new TreeMap<>(NOTES).entrySet()) {
                txt.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                if (!first) json.append(',');
                first = false;
                json.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
            }
            json.append('}');
        }

        long records = RECORDS.get();
        double overheadMsPerSec = records * Math.max(0, recordCostNs) / 1e6 / secs;
        String samplerCost = sampler == null ? "" : String.format(Locale.ROOT, " + sampling %.2f ms of CPU a second", sampler.ownCpuMsPerSecond());
        txt.append(String.format(Locale.ROOT, "%nmeasuring itself: %d records (%.0f/s) × %.0f ns ≈ %.3f ms of CPU a second%s%n",
                records, records / secs, recordCostNs, overheadMsPerSec, samplerCost));
        json.append(",\"overhead\":{\"records\":").append(records).append(",\"recordNs\":").append(fmt(recordCostNs))
                .append(",\"msPerSecond\":").append(fmt(overheadMsPerSec))
                .append(",\"samplerMsPerSecond\":").append(fmt(sampler == null ? 0 : sampler.ownCpuMsPerSecond())).append('}');
        json.append('}');

        try {
            Files.createDirectories(dir);
            Path t = dir.resolve("measure-" + side + "-" + stamp + ".txt");
            for (int i = 2; Files.exists(t); i++) t = dir.resolve("measure-" + side + "-" + stamp + "-" + i + ".txt");
            Files.writeString(t, txt.toString(), StandardCharsets.UTF_8);
            Files.writeString(Path.of(t.toString().replaceAll("\\.txt$", ".json")), json.toString(), StandardCharsets.UTF_8);
            return t;
        } catch (IOException e) {
            LOG.warn("Couldn't write the measurement report: {}", e.toString());
            return null;
        }
    }

    private static String num(double v) {
        if (Math.abs(v) >= 1000) return String.format(Locale.ROOT, "%.0f", v);
        if (Math.abs(v) >= 100) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** Names of every metric and counter (tests). */
    static List<String> names() {
        List<String> out = new ArrayList<>(METRICS.keySet());
        out.addAll(COUNTERS.keySet());
        return out;
    }

    private Measure() {}
}
