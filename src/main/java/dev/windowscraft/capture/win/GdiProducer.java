package dev.windowscraft.capture.win;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FramePool;
import dev.windowscraft.capture.FrameProducer;
import dev.windowscraft.capture.SourceGoneException;
import dev.windowscraft.capture.SourceInfo;

/** GDI frame producers. Instances are confined to the thread that created them. */
abstract class GdiProducer implements FrameProducer {
    protected final SourceInfo source;
    protected final HDC screenDc;
    private DibSurface full, scaled;
    protected String idle;

    GdiProducer(SourceInfo source) {
        Win32.makeThreadDpiAware();
        this.source = source;
        this.screenDc = Win32.User32.I.GetDC(null);
        if (screenDc == null) throw new IllegalStateException("GetDC(screen) failed");
    }

    protected DibSurface full(int w, int h) {
        if (full == null || full.width != w || full.height != h) {
            DibSurface old = full;
            full = null; // never leave a closed surface behind if the new one fails to allocate
            if (old != null) old.close();
            full = new DibSurface(screenDc, w, h);
        }
        return full;
    }

    protected DibSurface scaled(int w, int h) {
        if (scaled == null || scaled.width != w || scaled.height != h) {
            DibSurface old = scaled;
            scaled = null;
            if (old != null) old.close();
            DibSurface s = new DibSurface(screenDc, w, h);
            Win32.Gdi32.I.SetStretchBltMode(s.dc, Win32.HALFTONE);
            Win32.Gdi32.I.SetBrushOrgEx(s.dc, 0, 0, null);
            scaled = s;
        }
        return scaled;
    }

    /** Copies (and if needed downsamples) a region of {@code src} into a pooled frame. */
    protected Frame emit(FramePool pool, HDC src, int sx, int sy, int sw, int sh, int ow, int oh) {
        DibSurface out;
        if (ow == sw && oh == sh && src != screenDc && full != null && full.dc.equals(src) && sx == 0 && sy == 0
                && full.width == sw && full.height == sh) {
            out = full; // already exactly the right pixels
        } else if (ow == sw && oh == sh) {
            out = scaled(ow, oh);
            Win32.Gdi32.I.BitBlt(out.dc, 0, 0, ow, oh, src, sx, sy, Win32.SRCCOPY);
        } else {
            out = scaled(ow, oh);
            Win32.Gdi32.I.StretchBlt(out.dc, 0, 0, ow, oh, src, sx, sy, sw, sh, Win32.SRCCOPY);
        }
        afterDraw(out, sx, sy, ow / (double) sw);
        Frame f = pool != null ? pool.acquire(ow, oh) : Frame.allocate(ow, oh);
        out.copyTo(f);
        return f;
    }

    protected void afterDraw(DibSurface out, int sx, int sy, double scale) {}

    abstract Frame grabScaledOnce(int maxW, int maxH) throws SourceGoneException;

    @Override
    public String idleReason() { return idle; }

    @Override
    public void close() {
        if (full != null) full.close();
        if (scaled != null) scaled.close();
        full = scaled = null;
        Win32.User32.I.ReleaseDC(null, screenDc);
    }

    static int[] fit(int w, int h, int maxW, int maxH) {
        double s = Math.min(1.0, Math.min(maxW / (double) w, maxH / (double) h));
        return new int[]{Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s))};
    }

    static boolean isHung(long handle) {
        try {
            return Win32.User32.I.IsHungAppWindow(new HWND(new Pointer(handle)));
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Width/height of a GDI bitmap, via GetObject(BITMAP). */
    private static int[] bitmapSize(HBITMAP bmp) {
        if (bmp == null) return null;
        try (Memory m = new Memory(32)) { // sizeof(BITMAP) on 64-bit
            if (Win32.Gdi32.I.GetObject(bmp, 32, m) == 0) return null;
            return new int[]{m.getInt(4), m.getInt(8)};
        }
    }

    /** A whole monitor, read from the desktop DC (includes the mouse cursor when enabled). */
    static final class Monitor extends GdiProducer {
        private boolean drawCursor;
        private int left, top, width, height;
        private int framesSinceCheck;

        Monitor(SourceInfo source) {
            super(source);
            left = source.left();
            top = source.top();
            width = source.width();
            height = source.height();
        }

        /** Monitors can be re-arranged or change resolution while you're sharing. */
        private void refreshGeometry() throws SourceGoneException {
            if (framesSinceCheck++ % 60 != 0) return;
            RECT r = WinGdiBackend.monitorRect(source.id());
            if (r == null) throw new SourceGoneException("The screen was disconnected");
            left = r.left;
            top = r.top;
            width = r.right - r.left;
            height = r.bottom - r.top;
        }

        @Override
        public Frame grab(FramePool pool, CaptureSettings s) throws SourceGoneException {
            refreshGeometry();
            drawCursor = s.showCursor();
            int[] o = s.outputSize(width, height);
            return emit(pool, screenDc, left, top, width, height, o[0], o[1]);
        }

        @Override
        Frame grabScaledOnce(int maxW, int maxH) {
            drawCursor = false;
            int[] o = fit(width, height, maxW, maxH);
            return emit(null, screenDc, left, top, width, height, o[0], o[1]);
        }

        @Override
        protected void afterDraw(DibSurface out, int sx, int sy, double scale) {
            if (!drawCursor) return;
            Win32.CURSORINFO ci = new Win32.CURSORINFO();
            if (!Win32.User32.I.GetCursorInfo(ci) || (ci.flags & Win32.CURSOR_SHOWING) == 0 || ci.hCursor == null) return;
            int hx = 0, hy = 0, cw = 32, ch = 32;
            WinGDI.ICONINFO ii = new WinGDI.ICONINFO();
            if (Win32.User32.I.GetIconInfo(ci.hCursor, ii)) {
                hx = ii.xHotspot;
                hy = ii.yHotspot;
                // real cursor size (DPI scaling and "larger pointer" settings change it)
                int[] sz = bitmapSize(ii.hbmColor);
                if (sz == null) {
                    sz = bitmapSize(ii.hbmMask);
                    if (sz != null && ii.hbmColor == null) sz[1] /= 2; // monochrome: AND+XOR masks stacked
                }
                if (sz != null && sz[0] > 0 && sz[1] > 0) {
                    cw = sz[0];
                    ch = sz[1];
                }
                if (ii.hbmMask != null) Win32.Gdi32.I.DeleteObject(ii.hbmMask);
                if (ii.hbmColor != null) Win32.Gdi32.I.DeleteObject(ii.hbmColor);
            }
            int w = Math.max(4, (int) Math.round(cw * scale)), h = Math.max(4, (int) Math.round(ch * scale));
            int x = (int) Math.round((ci.ptScreenPos.x - sx - hx) * scale);
            int y = (int) Math.round((ci.ptScreenPos.y - sy - hy) * scale);
            if (x < -w || y < -h || x > out.width || y > out.height) return;
            Win32.User32.I.DrawIconEx(out.dc, x, y, ci.hCursor, w, h, 0, null, Win32.DI_NORMAL);
        }
    }

    /**
     * One application window via PrintWindow(PW_RENDERFULLCONTENT): asks DWM for the window's
     * own content, so it keeps working while the window is covered by Minecraft. The client
     * area (no title bar / borders) is cropped out.
     */
    static final class Window extends GdiProducer {
        private final HWND hwnd;

        Window(SourceInfo source) {
            super(source);
            this.hwnd = new HWND(new Pointer(source.handle()));
        }

        private int[] clientRegion() throws SourceGoneException {
            Win32.User32 u = Win32.User32.I;
            if (!u.IsWindow(hwnd)) throw new SourceGoneException("The window was closed");
            if (u.IsIconic(hwnd)) {
                idle = "Window is minimized";
                return null;
            }
            if (isHung(source.handle())) { // PrintWindow would block until the app recovers
                idle = "Window is not responding";
                return null;
            }
            // Measure in the window's own DPI space: DPI-unaware apps on scaled monitors are
            // drawn by PrintWindow at their logical size, so the crop must use logical pixels too.
            Pointer previous = null;
            try {
                previous = u.SetThreadDpiAwarenessContext(u.GetWindowDpiAwarenessContext(hwnd));
            } catch (UnsatisfiedLinkError ignored) {
            }
            try {
                RECT wr = new RECT(), cr = new RECT();
                if (!u.GetWindowRect(hwnd, wr) || !u.GetClientRect(hwnd, cr)) return null;
                POINT origin = new POINT(0, 0);
                u.ClientToScreen(hwnd, origin);
                int ww = wr.right - wr.left, wh = wr.bottom - wr.top;
                int cw = cr.right - cr.left, ch = cr.bottom - cr.top;
                if (ww <= 0 || wh <= 0 || cw <= 1 || ch <= 1) {
                    idle = "Window is too small";
                    return null;
                }
                int ox = Math.max(0, origin.x - wr.left), oy = Math.max(0, origin.y - wr.top);
                cw = Math.min(cw, ww - ox);
                ch = Math.min(ch, wh - oy);
                idle = null;
                return new int[]{ww, wh, ox, oy, cw, ch};
            } finally {
                if (previous != null) u.SetThreadDpiAwarenessContext(previous);
            }
        }

        /**
         * Capture methods, best first. Most modern apps answer PW_RENDERFULLCONTENT (works while
         * covered); some only answer plain PrintWindow; a few only show what's on screen.
         */
        private static final int MODE_FULL_CONTENT = 0, MODE_PRINT = 1, MODE_SCREEN_DC = 2, MODES = 3;
        private static final int REPROBE_INTERVAL = 30, CLEAR_INTERVAL = 30, BLANK_UNLOCK = 90;
        private int mode = MODE_FULL_CONTENT;
        private boolean modeLocked;
        private int blankStreak, frameNo, nextProbe;

        private boolean renderWith(int m, DibSurface surface, int[] r) {
            Win32.User32 u = Win32.User32.I;
            if (m == MODE_FULL_CONTENT) return u.PrintWindow(hwnd, surface.dc, Win32.PW_RENDERFULLCONTENT);
            if (m == MODE_PRINT) return u.PrintWindow(hwnd, surface.dc, 0);
            HDC wdc = u.GetDC(hwnd); // client-area DC: only what's actually visible on screen
            if (wdc == null) return false;
            try {
                return Win32.Gdi32.I.BitBlt(surface.dc, r[2], r[3], r[4], r[5], wdc, 0, 0, Win32.SRCCOPY);
            } finally {
                u.ReleaseDC(hwnd, wdc);
            }
        }

        private DibSurface render(int[] r) {
            DibSurface surface = full(r[0], r[1]);
            frameNo++;
            if (modeLocked || frameNo < nextProbe) {
                // Clear now and then so an app that silently stops drawing shows up as blank
                // instead of freezing on its last frame.
                if (frameNo % CLEAR_INTERVAL == 0) surface.clear();
                boolean ok = renderWith(mode, surface, r) && !surface.looksBlank(r[2], r[3], r[4], r[5]);
                if (modeLocked) {
                    if (ok) blankStreak = 0;
                    else if (++blankStreak > BLANK_UNLOCK) { modeLocked = false; blankStreak = 0; nextProbe = frameNo; }
                }
                return surface;
            }
            for (int m = 0; m < MODES; m++) {
                surface.clear();
                if (renderWith(m, surface, r) && !surface.looksBlank(r[2], r[3], r[4], r[5])) {
                    mode = m;
                    modeLocked = true;
                    return surface;
                }
            }
            // Everything came back black: the window may genuinely be black (a paused video).
            // Stay on the best method and only re-probe occasionally, since probing costs 3×.
            mode = MODE_FULL_CONTENT;
            nextProbe = frameNo + REPROBE_INTERVAL;
            surface.clear();
            renderWith(mode, surface, r);
            return surface;
        }

        @Override
        public Frame grab(FramePool pool, CaptureSettings s) throws SourceGoneException {
            int[] r = clientRegion();
            if (r == null) return null;
            DibSurface surface = render(r);
            int[] o = s.outputSize(r[4], r[5]);
            return emit(pool, surface.dc, r[2], r[3], r[4], r[5], o[0], o[1]);
        }

        @Override
        Frame grabScaledOnce(int maxW, int maxH) throws SourceGoneException {
            int[] r = clientRegion();
            if (r == null) return null;
            DibSurface surface = render(r);
            int[] o = fit(r[4], r[5], maxW, maxH);
            return emit(null, surface.dc, r[2], r[3], r[4], r[5], o[0], o[1]);
        }
    }
}
