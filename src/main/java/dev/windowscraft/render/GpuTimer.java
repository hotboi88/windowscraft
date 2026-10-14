package dev.windowscraft.render;

import dev.windowscraft.measure.Measure;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Measurements only: how long the graphics card spends on a piece of our work, from a pair of
 * GL_TIMESTAMP queries around it (these never clash with timer queries the game or a shader pack
 * may have open). Results are read a few frames later without waiting, so measuring never stalls
 * the render thread. Does nothing unless {@link Measure#ON}.
 */
public final class GpuTimer {
    private static final int RING = 8;
    private final Measure.Metric metric;
    private final int[] begin = new int[RING], end = new int[RING];
    private final boolean[] busy = new boolean[RING];
    private int next;
    private boolean open;
    private static Boolean supported;

    public GpuTimer(Measure.Metric metric) { this.metric = metric; }

    private static boolean supported() {
        if (supported == null) {
            var caps = GL.getCapabilities();
            supported = caps.OpenGL33 || caps.GL_ARB_timer_query;
        }
        return supported;
    }

    /** Render thread: before the work. */
    public void begin() {
        if (!Measure.ON || !supported()) return;
        collect();
        int i = next;
        if (busy[i]) return; // all in flight (the card is far behind): skip this one
        if (begin[i] == 0) {
            begin[i] = GL15.glGenQueries();
            end[i] = GL15.glGenQueries();
        }
        GL33.glQueryCounter(begin[i], GL33.GL_TIMESTAMP);
        open = true;
    }

    /** Render thread: after the work. */
    public void end() {
        if (!open) return;
        open = false;
        int i = next;
        GL33.glQueryCounter(end[i], GL33.GL_TIMESTAMP);
        busy[i] = true;
        next = (next + 1) % RING;
    }

    /** Reads every finished pair (never waits). */
    private void collect() {
        for (int i = 0; i < RING; i++) {
            if (!busy[i]) continue;
            if (GL15.glGetQueryObjecti(end[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long t0 = GL33.glGetQueryObjecti64(begin[i], GL15.GL_QUERY_RESULT);
            long t1 = GL33.glGetQueryObjecti64(end[i], GL15.GL_QUERY_RESULT);
            busy[i] = false;
            if (t1 >= t0) metric.record(t1 - t0);
        }
    }
}
