package dev.windowscraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.display.Display;
import dev.windowscraft.monitor.InGameMonitors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Sitting at your own in-game monitor: its picture is drawn flat on your screen, right where the
 * monitor is in the room — no 3D wall, shaders or blurrier copies in between, and when it fits
 * pixel for pixel, exactly that. The room stays around it (no black bars). Scrolled out (Alt +
 * wheel) it's still flat and sharp, just smaller; look around with Alt and the room's own copy
 * of it comes back.
 */
public final class DeskView {
    private static long since, frame;
    private static boolean covering;

    /** The monitor shown flat right now (null: the room as usual). */
    public static Display showing() { return TheaterMode.deskViewDisplay(); }

    /** Is the flat picture fully over this wall now (so the room's own copy needn't be drawn)? */
    public static boolean covers(Display d) { return covering && d != null && d == showing(); }

    public static void render(DrawContext g, RenderTickCounter counter) {
        Display d = showing();
        if (d == null) {
            since = 0;
            covering = false;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.nanoTime();
        if (since == 0) since = now;
        float alpha = (float) Math.min(1, (now - since) / 120e6); // (a quick fade in over the room's copy)
        Identifier tex = d.pollTexture(++frame, true);
        int tw = d.textureWidth(), th = d.textureHeight();
        if (tex == null || tw <= 0 || th <= 0) { covering = false; return; }
        // where the room shows the picture, in real pixels
        Vec3d[] c = ScreenControl.pictureCorners(d);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Vec3d p : c) {
            double[] s = FrameView.project(p);
            if (s == null) { covering = false; return; }
            x0 = Math.min(x0, s[0]); x1 = Math.max(x1, s[0]);
            y0 = Math.min(y0, s[1]); y1 = Math.max(y1, s[1]);
        }
        double scale = mc.getWindow().getScaleFactor();
        double k = (x1 - x0) / tw;
        // within a few percent of 1:1 it's exactly 1:1 (on whole pixels: no half-pixel blur)
        boolean exact = Math.abs(k - 1) < 0.04 && Math.abs((y1 - y0) / th - 1) < 0.04;
        if (exact) k = 1;
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        double fx = Math.round(cx - tw * k / 2), fy = Math.round(cy - th * k / 2); // framebuffer pixels
        double pw = tw * k / scale, ph = th * k / scale, px = fx / scale, py = fy / scale;
        d.noteView(true, th * k);
        var m = g.getMatrices();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        m.push();
        m.translate(px, py, 0);
        m.scale((float) (pw / tw), (float) (ph / th), 1);
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        Runnable draw = () -> g.drawTexture(tex, 0, 0, 0f, 0f, tw, th, tw, th);
        if (exact) Tablet.sharpNearest(tex, draw);
        else if (k < 0.75) Tablet.smallerSharp(tex, draw); // (well scrolled out: its smaller copies, no shimmer)
        else Tablet.sharp(tex, draw);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        m.pop();
        covering = alpha >= 1;
        // your real pointer, at its real size, on whole pixels
        double[] uv = InGameMonitors.pointerOnSeat();
        var shape = dev.windowscraft.capture.win.RealCursor.current();
        if (uv != null && shape != null && uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1) {
            Identifier ct = dev.windowscraft.render.DisplayRenderer.realPointerTexture(shape);
            // on the monitor the pointer is shape-size pixels; here, the same share of the picture
            // (1:1 at full size) — but never smaller than it really is
            double ks = Math.max(1, k);
            double hx = Math.floor(fx + uv[0] * tw * k) - Math.round(shape.hotX() * ks);
            double hy = Math.floor(fy + uv[1] * th * k) - Math.round(shape.hotY() * ks);
            float s = (float) (ks / scale);
            m.push();
            m.translate((float) (hx / scale), (float) (hy / scale), 1);
            m.scale(s, s, 1);
            Tablet.sharpNearest(ct, () -> g.drawTexture(ct, 0, 0, 0f, 0f, shape.width(), shape.height(), shape.width(), shape.height()));
            m.pop();
        }
    }

    private DeskView() {}
}
