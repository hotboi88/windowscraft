package dev.windowscraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The camera of the frame just drawn (its view and projection, exactly as the world used them —
 * your FOV, its effects, the window's shape): so a screen can be put flat on your view right where
 * the world shows it, and the seat in front of it worked out from the real field of view.
 */
public final class FrameView {
    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f();
    private static Vec3d cam = Vec3d.ZERO;
    private static boolean have;

    /** Called with the world's own matrices, each frame. */
    public static void capture(Matrix4f view, Matrix4f projection, Vec3d camera) {
        VIEW.set(view);
        PROJ.set(projection);
        cam = camera;
        have = true;
    }

    /** tan(half the vertical field of view), from the frame drawn (the FOV setting before the first one). */
    public static double tanV() {
        if (have && PROJ.m11() > 1e-6) return 1.0 / PROJ.m11();
        MinecraftClient mc = MinecraftClient.getInstance();
        return Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2);
    }

    /** tan(half the horizontal field of view). */
    public static double tanH() {
        if (have && PROJ.m00() > 1e-6) return 1.0 / PROJ.m00();
        MinecraftClient mc = MinecraftClient.getInstance();
        double aspect = mc.getWindow().getFramebufferWidth() / (double) Math.max(1, mc.getWindow().getFramebufferHeight());
        return tanV() * aspect;
    }

    /** Where a point of the world is on the frame, in framebuffer pixels from the top left; null if behind you. */
    public static double[] project(Vec3d p) {
        if (!have) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vector4f v = new Vector4f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1);
        VIEW.transform(v);
        PROJ.transform(v);
        if (v.w <= 1e-5f) return null;
        double nx = v.x / v.w, ny = v.y / v.w;
        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        return new double[]{(nx + 1) / 2 * fw, (1 - ny) / 2 * fh};
    }

    private FrameView() {}
}
