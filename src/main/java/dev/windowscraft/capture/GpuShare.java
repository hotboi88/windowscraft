package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Direct GPU sharing: runs wc-capture.exe (Windows Graphics Capture → shared D3D11 textures) for
 * one source and tracks which shared texture holds the newest picture. The helper hands the
 * textures to this process as NT handles; the wall imports them into OpenGL as external memory
 * ({@code GpuShareTexture}), so the picture never leaves graphics memory. Only the app's content
 * is shared (no title bar), so a spot on the wall is the same spot in the app.
 */
public final class GpuShare {
    /** Three shared texture handles of one size; replaced when the window is resized. */
    public record Slots(int width, int height, long[] handles, int version, java.util.concurrent.atomic.AtomicBoolean taken) {
        /** Handles nobody imported (a newer size arrived first): give them back to Windows. */
        void closeIfUntaken() {
            if (taken.compareAndSet(false, true)) for (long h : handles) closeHandle(h);
        }
    }

    public static void closeHandle(long h) {
        if (h == 0) return;
        try {
            dev.windowscraft.capture.win.WinHandles.close(h);
        } catch (Throwable ignored) {
        }
    }

    private final SourceInfo source;
    private Process process;
    private volatile Slots slots;
    private volatile int latestSlot = -1;
    /** Pictures announced and not yet handed back (the helper won't write over them). */
    private final java.util.concurrent.ConcurrentLinkedQueue<Integer> announced = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long frameSeq;
    private volatile long lastFrameNanos;
    private volatile String failure;
    private volatile boolean gone;
    private int versions;

    public GpuShare(SourceInfo source) { this.source = source; }

    // measurements (off by default)
    private static final java.util.Set<GpuShare> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final dev.windowscraft.measure.Measure.Metric ANNOUNCE_AGE = dev.windowscraft.measure.Measure.ms("gpu_share.announce_age",
            "direct GPU sharing: captured by Windows → the game hears a new picture is ready");
    private static final dev.windowscraft.measure.Measure.Counter FRAMES = dev.windowscraft.measure.Measure.counter("gpu_share.frames", "pictures announced by the GPU sharing helper");
    /** When each slot's picture was captured (helper stamp as a nanoTime; measuring only). */
    private final long[] capturedAt = new long[3];
    static {
        dev.windowscraft.measure.Measure.onStart(() -> { for (GpuShare s : LIVE) s.send("timing 1"); });
        dev.windowscraft.measure.Measure.onStop(() -> { for (GpuShare s : LIVE) s.send("timing 0"); });
    }

    /** Measuring: when that slot's picture was captured (a nanoTime), or 0. */
    public long capturedAt(int slot) { return slot >= 0 && slot < 3 ? capturedAt[slot] : 0; }

    public static boolean supported(SourceInfo s) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && (s.kind() == SourceInfo.Kind.WINDOW && s.handle() != 0 || s.kind() == SourceInfo.Kind.SCREEN);
    }

    /** Starts the helper on the graphics card with this LUID (null: default card). */
    public synchronized void start(int fps, boolean cursor, int[] luid) {
        if (process != null) return;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(extract().toString());
            if (source.kind() == SourceInfo.Kind.WINDOW) cmd.addAll(List.of("--hwnd", Long.toUnsignedString(source.handle())));
            else cmd.addAll(List.of("--hmonitor", Long.toUnsignedString(dev.windowscraft.capture.win.WinHandles.monitorFor(source))));
            cmd.addAll(List.of("--ext", "--fps", Integer.toString(fps), "--cursor", cursor ? "1" : "0",
                    "--parent-pid", Long.toString(ProcessHandle.current().pid())));
            if (luid != null) cmd.addAll(List.of("--adapter-luid", Integer.toUnsignedString(luid[0]) + ":" + luid[1]));
            if (Boolean.getBoolean("windowscraft.exp.hires")) cmd.add("--hires"); // (Step 0 experiment, dev only)
            process = new ProcessBuilder(cmd).start();
            LIVE.add(this);
            if (dev.windowscraft.measure.Measure.ON) send("timing 1");
            Thread out = new Thread(() -> readOut(process.getInputStream()), "WindowsCraft gpu share");
            out.setDaemon(true);
            out.start();
            Thread err = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        WindowsCraftClient.LOGGER.info("[wc-capture] {}", line);
                        if (line.startsWith("error=")) failure = line.substring(6);
                    }
                } catch (IOException ignored) {
                }
            }, "WindowsCraft gpu share log");
            err.setDaemon(true);
            err.start();
        } catch (IOException e) {
            failure = e.getMessage();
        }
    }

    private void readOut(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p[0].equals("ntslots") && p.length == 6) {
                    long[] h = {Long.parseUnsignedLong(p[3]), Long.parseUnsignedLong(p[4]), Long.parseUnsignedLong(p[5])};
                    latestSlot = -1;
                    Slots old = slots;
                    announced.clear(); // new pictures: the helper starts them all free
                    slots = new Slots(Integer.parseInt(p[1]), Integer.parseInt(p[2]), h, ++versions, new java.util.concurrent.atomic.AtomicBoolean());
                    if (old != null) old.closeIfUntaken();
                } else if (p[0].equals("frame") && p.length >= 2) {
                    int k = Integer.parseInt(p[1]);
                    long cap = p.length >= 3 ? dev.windowscraft.measure.Measure.helperNanos(p[2]) : 0;
                    if (k >= 0 && k < 3) capturedAt[k] = cap;
                    if (cap != 0) ANNOUNCE_AGE.since(cap);
                    FRAMES.inc();
                    latestSlot = k;
                    announced.add(latestSlot);
                    lastFrameNanos = System.nanoTime();
                    frameSeq++;
                } else if (p[0].equals("gone")) {
                    gone = true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        if (failure == null && !gone) failure = "helper stopped";
    }

    /** Render thread: the pictures announced since last time, oldest first (each must be {@link #free}d once done). */
    public java.util.List<Integer> takeAnnounced() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Integer k; (k = announced.poll()) != null; ) out.add(k);
        return out;
    }

    /**
     * The game is done with that picture of the pictures with that version: the helper may write
     * the next one into it (one of pictures it has made anew since is ignored).
     */
    public void free(int slot, int version) { send("free " + slot + " " + version); }

    private synchronized void send(String line) {
        Process p = process;
        if (p == null || !p.isAlive()) return;
        try {
            p.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    /** Changes how many pictures a second the helper takes, without restarting it. */
    public synchronized void setFps(int fps) {
        Process p = process;
        if (p == null || !p.isAlive()) return;
        try {
            p.getOutputStream().write(("fps " + Math.max(1, fps) + "\n").getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    public Slots slots() { return slots; }
    public int latestSlot() { return latestSlot; }
    public long frameSeq() { return frameSeq; }
    public String failure() { return failure; }
    public void fail(String why) {
        if (failure == null) {
            failure = why;
            WindowsCraftClient.LOGGER.warn("Direct GPU sharing off for '{}': {}", source.title(), why);
        }
        stop();
    }

    /** Pictures are arriving (one in the last 2 s, or the window just isn't changing but nothing failed). */
    public boolean delivering() {
        // (new pictures count as soon as they're announced: the helper only writes into them once
        // the game has checked them and given them back — waiting for a frame first would wait for ever)
        return failure == null && (frameSeq > 0 || slots != null) && process != null && process.isAlive();
    }

    public long millisSinceFrame() { return (System.nanoTime() - lastFrameNanos) / 1_000_000; }

    public synchronized void stop() {
        Slots s = slots;
        if (s != null) s.closeIfUntaken();
        if (process == null) return;
        Process p = process;
        try { p.getOutputStream().close(); } catch (IOException ignored) {}
        Thread t = new Thread(() -> {
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            } catch (InterruptedException e) {
                p.destroy();
            }
        }, "WindowsCraft gpu share stop");
        t.setDaemon(true);
        t.start();
    }

    static synchronized Path extract() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("bin");
        Path exe = dir.resolve("wc-capture.exe");
        try (InputStream in = GpuShare.class.getResourceAsStream("/windowscraft/natives/wc-capture.exe")) {
            if (in == null) throw new IOException("wc-capture.exe missing from the mod jar");
            byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(exe) || Files.size(exe) != bytes.length || !java.util.Arrays.equals(Files.readAllBytes(exe), bytes)) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve("wc-capture.exe.tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return exe;
    }
}
