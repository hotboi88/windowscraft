package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;

/**
 * Windows Graphics Capture through the helper when it works on this PC; otherwise (older
 * Windows, a helper that can't start) the GDI capture, which only shows the app's content.
 * Graphics Capture that stops (an in-game monitor moved while Windows rearranged its screens) is
 * tried again a few seconds later, then less and less often: GDI is slow (a monitor's pictures
 * at ~27 ms each kept a whole processor core busy for hours — "overtime i drop frames").
 */
public final class FallbackProducer implements FrameProducer {
    private final SourceInfo source;
    private final boolean fullWindow;
    private final boolean wgcPossible;
    private volatile WgcProducer wgc;
    private FrameProducer gdi;
    private volatile boolean usingGdi;
    private volatile int fps, shrink;
    private final Backoff retry = new Backoff(5, 300);
    /** When Graphics Capture last (re)started: one that ran a minute before stopping starts the waits over. */
    private long wgcSince;

    public FallbackProducer(SourceInfo source, boolean fullWindow, int fps) {
        this.source = source;
        this.fullWindow = fullWindow;
        this.fps = fps;
        this.wgcPossible = WgcProducer.supported(source);
        if (wgcPossible) startWgc();
        if (wgc == null) usingGdi = true;
    }

    private boolean startWgc() {
        try {
            WgcProducer w = new WgcProducer(source, fullWindow, fps);
            if (shrink > 0) w.setShrink(shrink);
            wgc = w;
            wgcSince = System.nanoTime();
            return true;
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.info("Graphics capture helper unavailable ({}); using GDI capture, trying again in {} s", e.getMessage(),
                    retry.failed(System.nanoTime()));
            return false;
        }
    }

    /** True while showing the whole window (title bar included); false = only its content (GDI). */
    public boolean fullWindow() { WgcProducer w = wgc; return !usingGdi && w != null && w.fullWindow(); }
    public boolean usingGdi() { return usingGdi; }

    public void setFps(int fps) {
        this.fps = fps;
        WgcProducer w = wgc;
        if (w != null && !usingGdi) w.setFps(fps);
    }

    /** Halve the pictures n times before they're copied (Graphics Capture only; GDI ignores it). */
    public void setShrink(int n) {
        shrink = n;
        WgcProducer w = wgc;
        if (w != null && !usingGdi) w.setShrink(n);
    }

    /** The picture's own size before any halving (0: not known). */
    public int fullWidth() { WgcProducer w = wgc; return w != null && !usingGdi ? w.fullWidth() : 0; }
    public int fullHeight() { WgcProducer w = wgc; return w != null && !usingGdi ? w.fullHeight() : 0; }

    @Override
    public Frame grab(FramePool pool, CaptureSettings settings) throws SourceGoneException {
        long now = System.nanoTime();
        WgcProducer w = wgc;
        if (!usingGdi && w != null && w.failed()) {
            if (now - wgcSince > 60_000_000_000L) retry.reset(); // (it ran a good while: a fresh hiccup)
            WindowsCraftClient.LOGGER.info("Graphics capture of '{}' stopped ({}); using GDI capture, trying again in {} s", source.title(),
                    w.failure(), retry.failed(now));
            w.close();
            wgc = null;
            usingGdi = true;
        }
        if (usingGdi && wgcPossible && retry.due(now) && startWgc()) {
            WindowsCraftClient.LOGGER.info("Graphics capture of '{}' started again", source.title());
            usingGdi = false;
            if (gdi != null) {
                gdi.close();
                gdi = null;
            }
        }
        if (usingGdi) {
            if (gdi == null) gdi = CaptureBackends.get().open(source);
            return gdi.grab(pool, settings);
        }
        return wgc.grab(pool, settings);
    }

    @Override public String idleReason() { return usingGdi && gdi != null ? gdi.idleReason() : null; }

    @Override
    public void close() {
        WgcProducer w = wgc;
        if (w != null) w.close();
        if (gdi != null) gdi.close();
    }
}
