package dev.windowscraft.client;

import dev.windowscraft.capture.win.WinGeom;

/**
 * Where an app's client area (or a monitor) sits inside a picture of it, as fractions of the
 * picture: (0, 0, 1, 1) when the picture is exactly the client area. A picture of the whole
 * window (title bar too), fitted into a frame of another shape, has it somewhere inside.
 */
public record Box(double x0, double y0, double x1, double y1) {
    public static final Box FULL = new Box(0, 0, 1, 1);

    /** A spot of the picture → a spot of the client area (0..1, kept inside it). */
    public double[] toClient(double u, double v) {
        double w = Math.max(1e-6, x1 - x0), h = Math.max(1e-6, y1 - y0);
        return new double[]{clamp((u - x0) / w), clamp((v - y0) / h)};
    }

    /** A spot of the client area → where it is in the picture. */
    public double[] toPicture(double[] c) {
        return new double[]{x0 + c[0] * (x1 - x0), y0 + c[1] * (y1 - y0)};
    }

    /**
     * A picture W × H of the window's whole visible frame, fitted in (scaled to fit, centred) —
     * how Windows Graphics Capture through FFmpeg delivers a window at the stream's size.
     */
    public static Box frameFit(int pw, int ph, WinGeom.Geometry g) {
        if (g == null || pw <= 0 || ph <= 0 || g.frameW() <= 0 || g.frameH() <= 0 || g.clientW() <= 0 || g.clientH() <= 0) return FULL;
        double s = Math.min(pw / (double) g.frameW(), ph / (double) g.frameH());
        double ox = (pw - g.frameW() * s) / 2, oy = (ph - g.frameH() * s) / 2;
        Box b = new Box((ox + (g.clientX() - g.frameX()) * s) / pw, (oy + (g.clientY() - g.frameY()) * s) / ph,
                (ox + (g.clientX() + g.clientW() - g.frameX()) * s) / pw, (oy + (g.clientY() + g.clientH() - g.frameY()) * s) / ph);
        return b.sane() ? b : FULL;
    }

    public boolean sane() {
        return x0 >= -0.01 && y0 >= -0.01 && x1 <= 1.01 && y1 <= 1.01 && x1 - x0 > 0.05 && y1 - y0 > 0.05;
    }

    /** Practically the same (no need to tell anyone). */
    public boolean near(Box o) {
        return o != null && Math.abs(x0 - o.x0) < 0.002 && Math.abs(y0 - o.y0) < 0.002 && Math.abs(x1 - o.x1) < 0.002 && Math.abs(y1 - o.y1) < 0.002;
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
