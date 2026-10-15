package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;

/**
 * Windows Graphics Capture through the helper when it works on this PC; otherwise (older
 * Windows, a helper that can't start) the GDI capture, which only shows the app's content.
 */
public final class FallbackProducer implements FrameProducer {
    private final SourceInfo source;
    private final WgcProducer wgc;
    private FrameProducer gdi;
    private volatile boolean usingGdi;

    public FallbackProducer(SourceInfo source, boolean fullWindow, int fps) {
        this.source = source;
        WgcProducer w = null;
        if (WgcProducer.supported(source)) {
            try {
                w = new WgcProducer(source, fullWindow, fps);
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.info("Graphics capture helper unavailable ({}); using GDI capture", e.getMessage());
            }
        }
        wgc = w;
        if (wgc == null) usingGdi = true;
    }

    /** True while showing the whole window (title bar included); false = only its content (GDI). */
    public boolean fullWindow() { return !usingGdi && wgc.fullWindow(); }
    public boolean usingGdi() { return usingGdi; }

    public void setFps(int fps) {
        if (wgc != null && !usingGdi) wgc.setFps(fps);
    }

    /** Halve the pictures n times before they're copied (Graphics Capture only; GDI ignores it). */
    public void setShrink(int n) {
        if (wgc != null && !usingGdi) wgc.setShrink(n);
    }

    /** The picture's own size before any halving (0: not known). */
    public int fullWidth() { return wgc != null && !usingGdi ? wgc.fullWidth() : 0; }
    public int fullHeight() { return wgc != null && !usingGdi ? wgc.fullHeight() : 0; }

    @Override
    public Frame grab(FramePool pool, CaptureSettings settings) throws SourceGoneException {
        if (!usingGdi && wgc.failed()) {
            WindowsCraftClient.LOGGER.info("Graphics capture of '{}' stopped ({}); using GDI capture", source.title(), wgc.failure());
            wgc.close();
            usingGdi = true;
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
        if (wgc != null) wgc.close();
        if (gdi != null) gdi.close();
    }
}
