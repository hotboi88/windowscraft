package dev.windowscraft.capture.win;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinGDI;
import java.nio.ByteBuffer;

/**
 * The real Windows pointer's current shape (arrow, text cursor, hand on links…), as straight-alpha
 * ARGB pixels with its hotspot, for drawing it on an in-game monitor's wall with no delay (the
 * position comes from the mouse itself each frame; only the shape is read, and only when it
 * changes). Inverting shapes like the text cursor are drawn dark with a light edge.
 */
public final class RealCursor {
    /** {@code argb} is width×height, row by row from the top. */
    public record Shape(long handle, int[] argb, int width, int height, int hotX, int hotY) {}

    private static long lastHandle;
    private static Shape last, arrow;
    private static boolean arrowTried;

    /**
     * Your Windows arrow pointer (from your own pointer scheme — the lime one, if that's yours),
     * for drawing someone else's pointer on a wall: it looks like a real Windows pointer, not a
     * stand-in. Null off Windows.
     */
    public static Shape arrow() {
        if (arrowTried) return arrow;
        arrowTried = true;
        if (!WinGeom.available()) return null;
        try {
            HICON h = Win32.User32.I.LoadCursorW(null, Pointer.createConstant(32512)); // IDC_ARROW
            if (h != null) arrow = read(h, Pointer.nativeValue(h.getPointer()));
        } catch (Throwable t) {
            arrow = null;
        }
        return arrow;
    }

    /** The pointer's shape now, or null if it's hidden (or not on Windows). Render thread. */
    public static Shape current() {
        if (!WinGeom.available()) return null;
        try {
            Win32.CURSORINFO ci = new Win32.CURSORINFO();
            if (!Win32.User32.I.GetCursorInfo(ci) || (ci.flags & Win32.CURSOR_SHOWING) == 0 || ci.hCursor == null) return null;
            long h = Pointer.nativeValue(ci.hCursor.getPointer());
            if (h == lastHandle && last != null) return last;
            lastHandle = h;
            last = read(ci.hCursor, h);
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Shape read(HICON icon, long handle) {
        int hx = 0, hy = 0, w = 32, h = 32;
        WinGDI.ICONINFO ii = new WinGDI.ICONINFO();
        if (Win32.User32.I.GetIconInfo(icon, ii)) {
            hx = ii.xHotspot;
            hy = ii.yHotspot;
            int[] sz = size(ii.hbmColor);
            if (sz == null) {
                sz = size(ii.hbmMask);
                if (sz != null && ii.hbmColor == null) sz[1] /= 2; // monochrome: AND and XOR masks stacked
            }
            if (sz != null && sz[0] > 0 && sz[1] > 0 && sz[0] <= 256 && sz[1] <= 256) {
                w = sz[0];
                h = sz[1];
            }
            if (ii.hbmMask != null) Win32.Gdi32.I.DeleteObject(ii.hbmMask);
            if (ii.hbmColor != null) Win32.Gdi32.I.DeleteObject(ii.hbmColor);
        }
        // drawn once on white and once on black: the difference gives coverage, and pixels that
        // flip (white on black, black on white) are the inverting kind
        int[] onWhite = draw(icon, w, h, 0xFFFFFFFF), onBlack = draw(icon, w, h, 0xFF000000);
        if (onWhite == null || onBlack == null) return null;
        int[] out = new int[w * h];
        boolean[] invert = new boolean[w * h];
        for (int i = 0; i < out.length; i++) {
            int wr = (onWhite[i] >> 16) & 0xFF, wg = (onWhite[i] >> 8) & 0xFF, wb = onWhite[i] & 0xFF;
            int br = (onBlack[i] >> 16) & 0xFF, bg = (onBlack[i] >> 8) & 0xFF, bb = onBlack[i] & 0xFF;
            if (br > 200 && wr < 55 && bg > 200 && wg < 55) { // flips: an inverting pixel
                invert[i] = true;
                out[i] = 0xFF1E1E1E;
                continue;
            }
            int a = 255 - Math.max(0, Math.min(255, ((wr - br) + (wg - bg) + (wb - bb)) / 3));
            if (a <= 0) { out[i] = 0; continue; }
            int r = Math.min(255, br * 255 / a), g = Math.min(255, bg * 255 / a), b = Math.min(255, bb * 255 / a);
            out[i] = a << 24 | r << 16 | g << 8 | b;
        }
        // a light edge around inverting shapes, so the text cursor shows on dark pages too
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (out[i] != 0 || invert[i]) continue;
                boolean near = false;
                for (int dy = -1; dy <= 1 && !near; dy++)
                    for (int dx = -1; dx <= 1 && !near; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && invert[ny * w + nx]) near = true;
                    }
                if (near) out[i] = 0xFFF0F0F0;
            }
        return new Shape(handle, out, w, h, hx, hy);
    }

    private static int[] draw(HICON icon, int w, int h, int background) {
        HDC screen = Win32.User32.I.GetDC(null);
        try (DibSurface s = new DibSurface(screen, w, h)) {
            ByteBuffer px = s.pixels().order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < w * h; i++) px.putInt(i * 4, background);
            Win32.Gdi32.I.GdiFlush();
            if (!Win32.User32.I.DrawIconEx(s.dc, 0, 0, icon, w, h, 0, null, Win32.DI_NORMAL)) return null;
            Win32.Gdi32.I.GdiFlush();
            int[] out = new int[w * h];
            ByteBuffer p = s.pixels();
            for (int i = 0; i < out.length; i++) {
                // BGRA little-endian = 0xAARRGGBB as an int
                out[i] = (p.get(i * 4 + 2) & 0xFF) << 16 | (p.get(i * 4 + 1) & 0xFF) << 8 | (p.get(i * 4) & 0xFF);
            }
            return out;
        } finally {
            Win32.User32.I.ReleaseDC(null, screen);
        }
    }

    private static int[] size(HBITMAP bmp) {
        if (bmp == null) return null;
        try (Memory m = new Memory(32)) { // sizeof(BITMAP) on 64-bit
            if (Win32.Gdi32.I.GetObject(bmp, 32, m) == 0) return null;
            return new int[]{m.getInt(4), m.getInt(8)};
        }
    }

    private RealCursor() {}
}
