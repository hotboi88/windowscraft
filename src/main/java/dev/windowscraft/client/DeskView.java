package dev.windowscraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.display.Display;
import dev.windowscraft.monitor.InGameMonitors;
import dev.windowscraft.render.DisplayRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * The screen you sit at or watch in theater mode, drawn flat on your view right where it is in
 * the room — no 3D wall, shaders or blurrier copies in between, and when it fits pixel for pixel,
 * exactly that. Your own in-game monitor, a friend's you use, a movie you watch: all the same.
 * The room stays around it (no black bars). Scrolled out (Alt + wheel) it's still flat and sharp,
 * just smaller; look around with Alt and the room's own copy of it comes back.
 */
public final class DeskView {
    private static long since;
    private static boolean covering;

    /** Your own screen shown flat right now (null: none). */
    public static Display showing() { return TheaterMode.deskViewDisplay(); }

    /** Someone else's screen shown flat right now (null: none). */
    public static RemoteDisplays.Remote showingRemote() { return TheaterMode.deskViewRemote(); }

    /** Is the flat picture fully over this wall now (so the room's own copy needn't be drawn)? */
    public static boolean covers(Display d) { return covering && d != null && d == showing(); }

    public static boolean covers(RemoteDisplays.Remote r) { return covering && r != null && r == showingRemote(); }

    public static void render(DrawContext g, RenderTickCounter counter) {
        Display d = showing();
        RemoteDisplays.Remote r = d == null ? showingRemote() : null;
        if (d == null && r == null) {
            since = 0;
            covering = false;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.nanoTime();
        if (since == 0) since = now;
        float alpha = (float) Math.min(1, (now - since) / 120e6); // (a quick fade in over the room's copy)
        long frame = DisplayRenderer.frameNumber();
        Identifier tex = d != null ? d.pollTexture(frame, true) : r.pollTexture(frame, true);
        int tw = d != null ? d.textureWidth() : r.textureWidth(), th = d != null ? d.textureHeight() : r.textureHeight();
        if (tex == null || tw <= 0 || th <= 0) { covering = false; return; }
        // where the room shows the picture, in real pixels
        Vec3d[] c = d != null ? ScreenControl.pictureCorners(d) : ScreenControl.pictureCorners(r);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Vec3d p : c) {
            double[] s = FrameView.project(p);
            if (s == null) { covering = false; return; }
            x0 = Math.min(x0, s[0]); x1 = Math.max(x1, s[0]);
            y0 = Math.min(y0, s[1]); y1 = Math.max(y1, s[1]);
        }
        double scale = mc.getWindow().getScaleFactor();
        double k = (x1 - x0) / tw;
        // a hair bigger than 1:1 is shown exactly 1:1 (on whole pixels: no half-pixel blur) — only
        // ever made smaller like that, never bigger: it must never go past what you can see
        boolean exact = k >= 1 && k < 1.04;
        if (exact) k = 1;
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        double fx = Math.round(cx - tw * k / 2), fy = Math.round(cy - th * k / 2); // framebuffer pixels
        double pw = tw * k / scale, ph = th * k / scale, px = fx / scale, py = fy / scale;
        if (d != null) d.noteView(true, th * k);
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

        // the pointers, the same size as on the wall (so nothing changes size when the view switches)
        if (d != null) {
            var mon = InGameMonitors.monitorOf(d);
            int monH = mon != null ? mon.height() : th, monW = mon != null ? mon.width() : tw;
            double[] uv = InGameMonitors.pointerOnSeat();
            var shape = dev.windowscraft.capture.win.RealCursor.current();
            if (uv != null && shape != null && uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1)
                shape(g, DisplayRenderer.realPointerTexture(shape), shape, fx, fy, tw * k, th * k, monH, uv);
            var arrow = dev.windowscraft.capture.win.RealCursor.arrow();
            if (mon != null && arrow != null) for (double[] gp : ScreenControl.guestPointers(d))
                shape(g, DisplayRenderer.arrowTexture(arrow), arrow, fx, fy, tw * k, th * k, monH, gp);
            if (monW <= 0) return;
        } else if (ScreenControl.pointerVisible(r)) {
            double[] spot = ScreenControl.pictureSpot(r);
            var arrow = dev.windowscraft.capture.win.RealCursor.arrow();
            int monH = r.info.height() > 0 ? r.info.height() : th;
            if (arrow != null && r.info.title().startsWith("In-game monitor")) {
                shape(g, DisplayRenderer.arrowTexture(arrow), arrow, fx, fy, tw * k, th * k, monH, spot);
            } else { // the plain pointer, as on the wall
                Box box = ScreenControl.box(r);
                int[] ps = DisplayRenderer.plainPointerSize();
                double hpx = th * k * (box.y1() - box.y0()) * 0.022, wpx = hpx * ps[0] / ps[1];
                double tipX = Math.round(fx + spot[0] * tw * k), tipY = Math.round(fy + spot[1] * th * k);
                Identifier pt = DisplayRenderer.plainPointer();
                m.push();
                m.translate((float) (tipX / scale), (float) (tipY / scale), 2);
                m.scale((float) (wpx / scale / ps[0]), (float) (hpx / scale / ps[1]), 1);
                Tablet.sharp(pt, () -> g.drawTexture(pt, 0, 0, 0f, 0f, ps[0], ps[1], ps[0], ps[1]));
                m.pop();
            }
        }
    }

    /**
     * A Windows pointer at (u, v) of the picture: as big as on the wall — its own pixels on a
     * monitor {@code monH} tall, but never smaller than a normal pointer on a 1080p screen — on
     * whole pixels, pixel for pixel when that's its real size.
     */
    private static void shape(DrawContext g, Identifier ct, dev.windowscraft.capture.win.RealCursor.Shape shape,
                              double fx, double fy, double picW, double picH, int monH, double[] uv) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.getWindow().getScaleFactor();
        double onMon = Math.max(1, (monH / 34.0) / Math.max(1, shape.height())); // (the wall's rule)
        double ks = onMon * picH / Math.max(1, monH);                           // monitor pixels → yours
        if (Math.abs(ks - Math.round(ks)) < 0.04 && Math.round(ks) >= 1) ks = Math.round(ks);
        double hx = Math.floor(fx + uv[0] * picW) - Math.round(shape.hotX() * ks);
        double hy = Math.floor(fy + uv[1] * picH) - Math.round(shape.hotY() * ks);
        float s = (float) (ks / scale);
        var m = g.getMatrices();
        m.push();
        m.translate((float) (hx / scale), (float) (hy / scale), 2);
        m.scale(s, s, 1);
        Runnable draw = () -> g.drawTexture(ct, 0, 0, 0f, 0f, shape.width(), shape.height(), shape.width(), shape.height());
        if (ks == Math.rint(ks)) Tablet.sharpNearest(ct, draw); else Tablet.sharp(ct, draw);
        m.pop();
    }

    private DeskView() {}
}
