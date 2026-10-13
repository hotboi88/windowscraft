package dev.windowscraft.capture;

/** Anything that hands newest-frame-wins pictures to the render thread (local capture or a network stream). */
public interface FrameSource {
    /** Newest frame or null; ownership passes to the caller until {@link #recycle}. */
    Frame takeFrame();

    void recycle(Frame f);
}
