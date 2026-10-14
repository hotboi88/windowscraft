package dev.windowscraft.client;

import net.minecraft.client.MinecraftClient;

/**
 * The part of the game's window you can really see: all of it, minus your Windows taskbar where
 * it sits on top of the game (it does when the game isn't the window in front — as while you sit
 * at an in-game monitor and its apps have the keyboard — even in fullscreen). In framebuffer
 * pixels: {x, y, width, height}. A screen you sit at is fitted into this, never under the taskbar.
 */
public final class GameArea {
    private static int[] cached;
    private static long cachedAt;
    private static int cachedW, cachedH;

    /** Tests: pretend this much of the bottom is covered by a taskbar (framebuffer pixels). */
    public static volatile int testCoveredBottom;

    public static int[] visible() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int fw = Math.max(1, mc.getWindow().getFramebufferWidth()), fh = Math.max(1, mc.getWindow().getFramebufferHeight());
        long now = System.nanoTime();
        if (cached != null && cachedW == fw && cachedH == fh && now - cachedAt < 250_000_000L) return cached;
        int[] a = {0, 0, fw, fh};
        if (testCoveredBottom > 0) a[3] = Math.max(1, fh - testCoveredBottom);
        else if (dev.windowscraft.capture.win.WinGeom.available() && dev.windowscraft.capture.win.WinGeom.gameWindow != 0) {
            try {
                a = measure(fw, fh);
            } catch (Throwable t) {
                a = new int[]{0, 0, fw, fh};
            }
        }
        cached = a;
        cachedAt = now;
        cachedW = fw;
        cachedH = fh;
        return a;
    }

    private static int[] measure(int fw, int fh) {
        int[] v = dev.windowscraft.capture.win.WinGeom.visibleClient(dev.windowscraft.capture.win.WinGeom.gameWindow, !MinecraftClient.getInstance().getWindow().isFullscreen());
        if (v == null) return new int[]{0, 0, fw, fh};
        double sx = fw / (double) v[4], sy = fh / (double) v[5];
        int x = (int) Math.round(v[0] * sx), y = (int) Math.round(v[1] * sy);
        int w = (int) Math.round(v[2] * sx), h = (int) Math.round(v[3] * sy);
        return new int[]{x, y, Math.max(1, Math.min(fw - x, w)), Math.max(1, Math.min(fh - y, h))};
    }

    private GameArea() {}
}
