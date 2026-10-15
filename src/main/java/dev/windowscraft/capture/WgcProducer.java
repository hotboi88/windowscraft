package dev.windowscraft.capture;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import dev.windowscraft.WindowsCraftClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Window capture with Windows Graphics Capture through wc-capture's shared-memory mode: the
 * helper copies a picture only when the window actually changed, and we copy it out of shared
 * memory. Much lighter than asking DWM to repaint the window into a bitmap (PrintWindow) every
 * frame, and it works without direct GPU sharing.
 *
 * <p>If the helper can't run (older Windows, odd setups), {@link #failed()} turns true and the
 * caller switches to the GDI capture.
 */
public final class WgcProducer implements FrameProducer {
    private static final int HEADER = 64, SLOTS = 3, FILE_MAP_READ = 0x0004;
    private static final AtomicInteger IDS = new AtomicInteger();

    private final SourceInfo source;
    private final boolean fullWindow;
    private final Process process;
    private final OutputStream control;
    private volatile String failure;
    private volatile boolean gone;
    // current mapping (replaced when the window is resized); guarded by "this"
    private WinNT.HANDLE mapping;
    private Pointer view;
    private int w, h;
    /** The picture's own size, before the helper halves it ("shrink"). */
    private volatile int fullW, fullH;
    private volatile int shrink;
    private volatile int latestSlot = -1;
    private volatile long arrived;
    private long taken;
    private volatile int fps;
    private final long startedNanos = System.nanoTime();
    /** Measuring: when the newest picture was captured (helper's stamp, as a nanoTime), else 0. */
    private volatile long latestCaptured;
    private static final java.util.Set<WgcProducer> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final dev.windowscraft.measure.Measure.Metric HELPER_AGE = dev.windowscraft.measure.Measure.ms("capture.helper_age",
            "CPU-read capture (in-game monitors' own walls): captured by Windows → copied out by the game");
    private static final dev.windowscraft.measure.Measure.Metric COPY_OUT = dev.windowscraft.measure.Measure.ms("capture.copy_out",
            "copying one picture out of the helper's shared memory (capture thread)");
    static {
        dev.windowscraft.measure.Measure.onStart(() -> { for (WgcProducer p : LIVE) p.timing(true); });
        dev.windowscraft.measure.Measure.onStop(() -> { for (WgcProducer p : LIVE) p.timing(false); });
    }

    private void timing(boolean on) {
        try {
            control.write(("timing " + (on ? 1 : 0) + "\n").getBytes(StandardCharsets.US_ASCII));
            control.flush();
        } catch (IOException ignored) {
        }
    }

    /**
     * @param fullWindow the whole window as on the desktop (title bar included), or only its content
     */
    public WgcProducer(SourceInfo source, boolean fullWindow, int fps) throws IOException {
        this.source = source;
        this.fullWindow = fullWindow;
        this.fps = fps;
        List<String> cmd = new ArrayList<>();
        cmd.add(GpuShare.extract().toString());
        if (source.kind() == SourceInfo.Kind.WINDOW) cmd.addAll(List.of("--hwnd", Long.toUnsignedString(source.handle())));
        else cmd.addAll(List.of("--hmonitor", Long.toUnsignedString(dev.windowscraft.capture.win.WinHandles.monitorFor(source))));
        String name = "Local\\windowscraft-" + ProcessHandle.current().pid() + "-" + IDS.incrementAndGet();
        cmd.addAll(List.of("--cpu", name, "--box", fullWindow ? "full" : "client", "--fps", Integer.toString(fps),
                "--cursor", source.kind() == SourceInfo.Kind.SCREEN && !dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(source) ? "1" : "0", "--parent-pid", Long.toString(ProcessHandle.current().pid())));
        if (Boolean.getBoolean("windowscraft.exp.hires")) cmd.add("--hires"); // (Step 0 experiment, dev only)
        process = new ProcessBuilder(cmd).start();
        control = process.getOutputStream();
        Thread out = new Thread(this::readOut, "WindowsCraft wgc " + source.title());
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    WindowsCraftClient.LOGGER.info("[wc-capture cpu] {}", line);
                    if (line.startsWith("error=")) failure = line.substring(6);
                }
            } catch (IOException ignored) {
            }
        }, "WindowsCraft wgc log");
        err.setDaemon(true);
        err.start();
        LIVE.add(this);
        if (dev.windowscraft.measure.Measure.ON) timing(true);
    }

    public static boolean supported(SourceInfo s) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && (s.kind() == SourceInfo.Kind.WINDOW && s.handle() != 0 || s.kind() == SourceInfo.Kind.SCREEN);
    }

    private void readOut() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.trim().split(" ");
                if (p[0].equals("cpuslots") && p.length >= 4) {
                    remap(Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]);
                    fullW = p.length >= 6 ? Integer.parseInt(p[4]) : Integer.parseInt(p[1]);
                    fullH = p.length >= 6 ? Integer.parseInt(p[5]) : Integer.parseInt(p[2]);
                } else if (p[0].equals("frame") && p.length >= 2) {
                    latestCaptured = p.length >= 3 ? dev.windowscraft.measure.Measure.helperNanos(p[2]) : 0;
                    latestSlot = Integer.parseInt(p[1]);
                    arrived++;
                } else if (p[0].equals("gone")) {
                    gone = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (failure == null) failure = String.valueOf(e.getMessage());
        }
        if (failure == null && !gone) failure = "helper stopped";
    }

    private synchronized void remap(int nw, int nh, String name) {
        unmap();
        WinNT.HANDLE m = Kernel32.INSTANCE.OpenFileMapping(FILE_MAP_READ, false, name);
        if (m == null) {
            failure = "couldn't open the picture memory";
            return;
        }
        Pointer v = Kernel32.INSTANCE.MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
        if (v == null) {
            Kernel32.INSTANCE.CloseHandle(m);
            failure = "couldn't map the picture memory";
            return;
        }
        mapping = m;
        view = v;
        w = nw;
        h = nh;
        latestSlot = -1;
    }

    private void unmap() {
        if (view != null) Kernel32.INSTANCE.UnmapViewOfFile(view);
        if (mapping != null) Kernel32.INSTANCE.CloseHandle(mapping);
        view = null;
        mapping = null;
    }

    /** The helper didn't work here: use another capture method. */
    public boolean failed() { return failure != null && !gone; }
    public String failure() { return failure; }
    public boolean fullWindow() { return fullWindow; }

    /** Change the frame rate (e.g. lower while the window isn't the one you're using). */
    public void setFps(int n) {
        if (n == fps) return;
        fps = n;
        try {
            control.write(("fps " + n + "\n").getBytes(StandardCharsets.US_ASCII));
            control.flush();
        } catch (IOException ignored) {
        }
    }

    /** Halve the pictures n times (0-3) on the graphics card before they're copied out. */
    public void setShrink(int n) {
        if (n == shrink) return;
        shrink = n;
        try {
            control.write(("shrink " + n + "\n").getBytes(StandardCharsets.US_ASCII));
            control.flush();
        } catch (IOException ignored) {
        }
    }

    /** The picture's own height before any halving (0: no picture yet). */
    public int fullHeight() { return fullH; }
    public int fullWidth() { return fullW; }

    @Override
    public synchronized Frame grab(FramePool pool, CaptureSettings settings) throws SourceGoneException {
        if (gone) throw new SourceGoneException("The window was closed");
        long a = arrived;
        // a window always sends its first picture right away; nothing after 5 s means this
        // capture doesn't work here (the GDI capture takes over)
        if (a == 0 && failure == null && System.nanoTime() - startedNanos > 5_000_000_000L) failure = "no picture from the graphics capture";
        int slot = latestSlot;
        if (a == taken || slot < 0 || view == null) return null; // nothing new: the window hasn't changed
        long seqBefore = view.getLong(slot * 8L);
        if (seqBefore == 0) return null; // being written right now; next time
        long captured = latestCaptured, c0 = dev.windowscraft.measure.Measure.ON ? System.nanoTime() : 0;
        Frame f = pool.acquire(w, h);
        ByteBuffer src = view.getByteBuffer(HEADER + (long) slot * w * h * 4, (long) w * h * 4);
        f.data.put(src);
        f.data.flip();
        if (view.getLong(slot * 8L) != seqBefore) { // overwritten while we copied: skip this one
            pool.release(f);
            return null;
        }
        taken = a;
        f.alphaValid = false;
        if (c0 != 0) {
            COPY_OUT.since(c0);
            HELPER_AGE.since(captured);
        }
        return f;
    }

    @Override public String idleReason() { return null; }

    @Override
    public synchronized void close() {
        LIVE.remove(this);
        try {
            control.close();
        } catch (IOException ignored) {
        }
        Process p = process;
        Thread t = new Thread(() -> {
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            } catch (InterruptedException e) {
                p.destroy();
            }
        }, "WindowsCraft wgc stop");
        t.setDaemon(true);
        t.start();
        unmap();
    }
}
