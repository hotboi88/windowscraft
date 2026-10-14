package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one source on its own daemon thread at the requested frame rate and hands the newest
 * frame to the render thread through a single atomic slot. If the renderer falls behind, older
 * frames are dropped rather than queued, so latency never builds up.
 */
public final class CaptureSession implements FrameSource {
    public enum State { STARTING, LIVE, IDLE, PAUSED, ENDED }

    private final SourceInfo source;
    private final FramePool pool = new FramePool();
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final Thread thread;
    private volatile CaptureSettings settings;
    /** Optional second consumer of every frame (the network publisher), called on the capture thread. */
    private volatile java.util.function.Consumer<Frame> tap;
    private volatile boolean running = true;
    private volatile boolean paused;
    /** Someone else (FFmpeg's GPU capture) is producing the pictures right now: rest. */
    private volatile boolean external, gpuRest;
    /** Upper frame rate while nothing is streamed (just your own wall): 0 = none. */
    private volatile int fpsCap;
    private volatile State state = State.STARTING;
    private volatile String message = "Starting…";

    // stats (written by capture thread, read by UI)
    private volatile double measuredFps;
    private volatile double avgCaptureMs;
    private volatile int outW, outH;

    // measurements (off by default)
    private static final dev.windowscraft.measure.Measure.Metric GRAB = dev.windowscraft.measure.Measure.ms("capture.grab",
            "taking one picture on the CPU path (capture thread; GDI, or reading the helper's shared memory)");
    private static final dev.windowscraft.measure.Measure.Metric INTERVAL = dev.windowscraft.measure.Measure.ms("capture.interval",
            "one captured picture to the next (CPU path)");
    private static final dev.windowscraft.measure.Measure.Counter NOT_SHOWN = dev.windowscraft.measure.Measure.counter("capture.frames_not_shown",
            "captured pictures replaced by a newer one before the wall took them");
    private long lastGrabbed;

    public CaptureSession(SourceInfo source, CaptureSettings settings) {
        this.source = source;
        this.settings = settings;
        this.thread = new Thread(this::run, "WindowsCraft capture: " + source.title());
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1); // never compete with the game thread
        this.thread.start();
    }

    private void run() {
        FrameProducer producer;
        try {
            producer = CaptureBackends.open(source);
            producerName = producer.getClass().getSimpleName();
            if (producer instanceof FallbackProducer fp0) wgcActive = !fp0.usingGdi();
            producerName = producer.getClass().getSimpleName();
            if (producer instanceof FallbackProducer fp0) wgcActive = !fp0.usingGdi();
        } catch (Throwable t) {
            WindowsCraftClient.LOGGER.error("Could not open capture source {}", source.title(), t);
            end("Couldn't start capture: " + t.getMessage());
            return;
        }
        long windowStart = System.nanoTime();
        int framesInWindow = 0;
        double captureMsSum = 0;
        try {
            long next = System.nanoTime();
            while (running) {
                if (paused) {
                    state = State.PAUSED;
                    LockSupport.parkNanos(100_000_000L);
                    next = System.nanoTime();
                    continue;
                }
                CaptureSettings s = settings;
                // an in-game monitor on your own wall: every picture it makes (it's your screen —
                // whatever rate the stream to others goes at)
                // and at its full resolution, pixel for pixel
                // (only the one you're using: every in-game monitor at full size and 60 pictures a
                // second costs the PC far too much — the game and the server stutter)
                if (dev.windowscraft.monitor.InGameMonitors.inUse(source) && (s.fps() < 60 || s.maxHeight() > 0))
                    s = new CaptureSettings(0, Math.max(60, s.fps()), s.showCursor(), s.audio(), s.fit(), s.hearInGame());
                int cap = fpsCap;
                // while the GPU captures, look once a second (so a closed or resized window is still noticed)
                long interval = external || gpuRest ? 1_000_000_000L : 1_000_000_000L / Math.max(1, cap > 0 ? Math.min(cap, s.fps()) : s.fps());
                long t0 = System.nanoTime();
                if (producer instanceof FallbackProducer fp) { // the helper takes no more pictures than we use
                    int want = external || gpuRest ? 1 : cap > 0 ? Math.min(cap, s.fps()) : s.fps();
                    if (want != helperFps) { fp.setFps(want); helperFps = want; }
                    wgcActive = !fp.usingGdi();
                }
                Frame f = producer.grab(pool, s);
                long t1 = System.nanoTime();
                if (f != null) {
                    f.captureNanos = t1;
                    if (dev.windowscraft.measure.Measure.ON) {
                        GRAB.record(t1 - t0);
                        if (lastGrabbed != 0) INTERVAL.record(t1 - lastGrabbed);
                        lastGrabbed = t1;
                    } else lastGrabbed = 0;
                    java.util.function.Consumer<Frame> t = tap;
                    if (t != null) t.accept(f);
                    outW = f.width;
                    outH = f.height;
                    Frame old = latest.getAndSet(f);
                    if (old != null) {
                        pool.release(old);
                        NOT_SHOWN.inc();
                    }
                    state = State.LIVE;
                    message = null;
                    framesInWindow++;
                    captureMsSum += (t1 - t0) / 1e6;
                } else {
                    state = State.IDLE;
                    message = producer.idleReason();
                }
                if (t1 - windowStart >= 1_000_000_000L) {
                    double secs = (t1 - windowStart) / 1e9;
                    measuredFps = framesInWindow / secs;
                    avgCaptureMs = framesInWindow > 0 ? captureMsSum / framesInWindow : 0;
                    windowStart = t1;
                    framesInWindow = 0;
                    captureMsSum = 0;
                }
                next += interval;
                long now = System.nanoTime();
                if (next < now - interval) next = now; // we're behind: don't try to catch up with a burst
                long sleep = next - now;
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                    // woken early because the rest is over: carry on at the normal rate from now
                    if (System.nanoTime() < next - 2_000_000L && !gpuRest && !external) next = System.nanoTime();
                }
            }
        } catch (SourceGoneException gone) {
            end(gone.getMessage());
        } catch (Throwable t) {
            WindowsCraftClient.LOGGER.error("Capture of {} crashed", source.title(), t);
            end("Capture stopped: " + t.getClass().getSimpleName());
        } finally {
            try {
                producer.close();
            } catch (Throwable t) {
                WindowsCraftClient.LOGGER.warn("Error closing capture source", t);
            }
            pool.clear();
        }
    }

    private void end(String why) {
        message = why;
        state = State.ENDED;
        running = false;
    }

    /** Render thread: take the newest frame, if any. Caller must hand it back via {@link #recycle}. */
    @Override
    public Frame takeFrame() {
        return latest.getAndSet(null);
    }

    @Override
    public void recycle(Frame f) {
        pool.release(f);
    }

    public void stop() {
        running = false;
        LockSupport.unpark(thread);
    }

    public void setTap(java.util.function.Consumer<Frame> t) { tap = t; }
    public void setPaused(boolean p) { paused = p; }
    public boolean isPaused() { return paused; }
    public void setExternal(boolean e) { external = e; }
    /** Direct GPU sharing feeds the wall and nothing needs CPU frames: rest too. */
    public void setGpuRest(boolean r) {
        boolean was = gpuRest;
        gpuRest = r;
        if (was && !r) LockSupport.unpark(thread); // wanted again: the next picture now, not after the rest interval
    }
    public boolean isExternal() { return external; }
    public void setFpsCap(int cap) { fpsCap = cap; }
    public void updateSettings(CaptureSettings s) { settings = s; }
    public CaptureSettings settings() { return settings; }
    public SourceInfo source() { return source; }
    public State state() { return state; }
    public String message() { return message; }
    public boolean isEnded() { return state == State.ENDED; }
    public double measuredFps() { return measuredFps; }
    private volatile String producerName = "?";
    private int helperFps = -1;
    private volatile boolean wgcActive;

    /** Is the wall fed by Windows Graphics Capture (fast, no delay) right now? */
    public boolean wgcActive() { return wgcActive; }
    public String producerName() { return producerName; }
    public double avgCaptureMs() { return avgCaptureMs; }
    public int outputWidth() { return outW; }
    public int outputHeight() { return outH; }
}
