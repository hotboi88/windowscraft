package dev.windowscraft.capture;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycles frame buffers between the capture thread and the render thread so steady-state
 * streaming allocates nothing. Buffers of the wrong size are simply dropped.
 */
public final class FramePool {
    private static final int MAX_POOLED = 3;
    private final ConcurrentLinkedQueue<Frame> free = new ConcurrentLinkedQueue<>();

    public Frame acquire(int width, int height) {
        Frame f;
        while ((f = free.poll()) != null) {
            if (f.width == width && f.height == height) {
                f.data.clear();
                return f;
            }
        }
        return Frame.allocate(width, height);
    }

    public void release(Frame frame) {
        if (frame != null && free.size() < MAX_POOLED) free.offer(frame);
    }

    public void clear() {
        free.clear();
    }
}
