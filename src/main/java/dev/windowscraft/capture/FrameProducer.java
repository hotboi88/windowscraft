package dev.windowscraft.capture;

/**
 * Produces frames for one source. All methods are called on the capture thread only, so
 * implementations may keep thread-affine native resources (GDI DCs, bitmaps).
 */
public interface FrameProducer extends AutoCloseable {
    /**
     * Captures one frame into a buffer from {@code pool}. Returns null if nothing could be captured
     * this time (e.g. the window is minimized); throws {@link SourceGoneException} if the source
     * no longer exists.
     */
    Frame grab(FramePool pool, CaptureSettings settings) throws SourceGoneException;

    /** Optional human-readable reason the last grab returned null (e.g. "Window is minimized"). */
    default String idleReason() { return null; }

    @Override
    void close();
}
