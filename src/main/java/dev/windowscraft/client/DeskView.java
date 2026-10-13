package dev.windowscraft.client;

import dev.windowscraft.display.Display;
import dev.windowscraft.monitor.InGameMonitors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/**
 * Sitting at your own in-game monitor: once you're seated, its picture is shown flat on your
 * screen, pixel for pixel — like the real monitor in front of you, with no 3D wall, shaders or
 * blurrier copies in between. Scroll out (Alt + wheel) to see the room around it again; look
 * around with Alt and the room comes back too.
 */
public final class DeskView {
    private static long since, frame;

    /** The monitor shown flat right now (null: the room as usual). */
    public static Display showing() { return TheaterMode.deskViewDisplay(); }

    public static void render(DrawContext g, RenderTickCounter counter) {
        Display d = showing();
        if (d == null) {
            since = 0;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.nanoTime();
        if (since == 0) since = now;
        float alpha = (float) Math.min(1, (now - since) / 120e6); // (a quick fade from the room)
        int sw = g.getScaledWindowWidth(), sh = g.getScaledWindowHeight();
        g.fill(0, 0, sw, sh, ((int) (alpha * 255) << 24));
        Identifier tex = d.pollTexture(++frame, true);
        int tw = d.textureWidth(), th = d.textureHeight();
        if (tex == null || tw <= 0 || th <= 0) return;
        // in real pixels: as big as fits; within a few percent of 1:1 it's exactly 1:1
        double scale = mc.getWindow().getScaleFactor();
        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        double k = Math.min(fw / (double) tw, fh / (double) th);
        boolean exact = Math.abs(k - 1) < 0.04;
        if (exact) k = 1;
        double pw = tw * k / scale, ph = th * k / scale;
        // whole pixels: no half-pixel offset that would blur every column
        double px = Math.floor((fw - tw * k) / 2) / scale, py = Math.floor((fh - th * k) / 2) / scale;
        d.noteView(true, th * k);
        var m = g.getMatrices();
        m.push();
        m.translate(px, py, 0);
        m.scale((float) (pw / tw), (float) (ph / th), 1);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, alpha);
        Runnable draw = () -> g.drawTexture(tex, 0, 0, 0f, 0f, tw, th, tw, th);
        if (exact) Tablet.sharpNearest(tex, draw);
        else Tablet.sharp(tex, draw);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
        m.pop();
        // your real pointer, at its real size
        double[] uv = InGameMonitors.pointerOnSeat();
        var shape = dev.windowscraft.capture.win.RealCursor.current();
        if (uv != null && shape != null && uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1) {
            Identifier ct = dev.windowscraft.render.DisplayRenderer.realPointerTexture(shape);
            float s = (float) (1 / scale);
            m.push();
            m.translate((float) (px + uv[0] * pw) - shape.hotX() * s, (float) (py + uv[1] * ph) - shape.hotY() * s, 1);
            m.scale(s, s, 1);
            Tablet.sharpNearest(ct, () -> g.drawTexture(ct, 0, 0, 0f, 0f, shape.width(), shape.height(), shape.width(), shape.height()));
            m.pop();
        }
    }

    private DeskView() {}
}
