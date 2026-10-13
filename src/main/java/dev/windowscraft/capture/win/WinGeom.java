package dev.windowscraft.capture.win;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where windows are on your desktop (read only — nothing here moves a window). Looked up on a
 * background thread so a busy app can never stall the game; physical pixels throughout.
 */
public final class WinGeom {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WindowsCraft windows");
        t.setDaemon(true);
        return t;
    });

    /** A window's visible frame (what Windows Graphics Capture shows) and its client area. */
    public record Geometry(int frameX, int frameY, int frameW, int frameH, int clientX, int clientY, int clientW, int clientH) {}

    /** A window under a point of a monitor: its handle and its client area on the desktop. */
    public record At(long handle, int clientX, int clientY, int clientW, int clientH) {}

    /** Minecraft's own window (never the answer to "what's under this point"). */
    public static volatile long gameWindow;

    public static boolean available() { return WINDOWS; }

    private static final Map<Long, Geometry> GEOMETRY = new ConcurrentHashMap<>();
    private static final Map<Long, Long> ASKED = new ConcurrentHashMap<>();

    /** The newest known geometry of that window (refreshed in the background a few times a second), or null. */
    public static Geometry geometry(long handle) {
        if (!WINDOWS || handle == 0) return null;
        long now = System.nanoTime();
        Long asked = ASKED.get(handle);
        if (asked == null || now - asked > 250_000_000L) {
            ASKED.put(handle, now);
            WORKER.execute(() -> {
                Geometry g = geometryNow(handle);
                if (g != null) GEOMETRY.put(handle, g);
            });
        }
        return GEOMETRY.get(handle);
    }

    private static Geometry geometryNow(long handle) {
        try {
            dpiAware();
            Win32.User32 u = Win32.User32.I;
            HWND win = new HWND(new Pointer(handle));
            if (!u.IsWindow(win) || u.IsIconic(win)) return null;
            RECT wr = new RECT(), cr = new RECT();
            if (!u.GetWindowRect(win, wr) || !u.GetClientRect(win, cr)) return null;
            RECT fr = frameBounds(win);
            if (fr == null) fr = wr;
            POINT o = new POINT(0, 0);
            if (!u.ClientToScreen(win, o)) return null;
            return new Geometry(fr.left, fr.top, fr.right - fr.left, fr.bottom - fr.top, o.x, o.y, cr.right - cr.left, cr.bottom - cr.top);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The top window at that point of your desktop, skipping the game itself and hidden/tool windows. */
    /** Tests (off Windows): what's under a desktop point. */
    public static volatile java.util.function.BiFunction<Integer, Integer, At> testWindowAt;

    public static CompletableFuture<At> windowAt(int sx, int sy) {
        if (!WINDOWS) return CompletableFuture.completedFuture(testWindowAt != null ? testWindowAt.apply(sx, sy) : null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                dpiAware();
                At[] found = {null};
                Win32.User32 u = Win32.User32.I;
                u.EnumWindows((hwnd, data) -> {
                    long h = Pointer.nativeValue(hwnd.getPointer());
                    if (h == gameWindow || !u.IsWindowVisible(hwnd)) return true;
                    if ((u.GetWindowLong(hwnd, Win32.GWL_EXSTYLE) & Win32.WS_EX_TOOLWINDOW) != 0) return true;
                    if (cloaked(hwnd)) return true;
                    RECT r = new RECT();
                    if (!u.GetWindowRect(hwnd, r) || sx < r.left || sx >= r.right || sy < r.top || sy >= r.bottom) return true;
                    RECT cr = new RECT();
                    POINT o = new POINT(0, 0);
                    if (!u.GetClientRect(hwnd, cr) || !u.ClientToScreen(hwnd, o)) return true;
                    found[0] = new At(h, o.x, o.y, Math.max(1, cr.right), Math.max(1, cr.bottom));
                    return false; // the top-most one
                }, null);
                return found[0];
            } catch (Throwable t) {
                return null;
            }
        }, WORKER);
    }

    private static RECT frameBounds(HWND win) {
        try {
            Memory m = new Memory(16);
            if (Win32.Dwm.I.DwmGetWindowAttribute(win, Win32.DWMWA_EXTENDED_FRAME_BOUNDS, m, 16) != 0) return null;
            RECT r = new RECT();
            r.left = m.getInt(0);
            r.top = m.getInt(4);
            r.right = m.getInt(8);
            r.bottom = m.getInt(12);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean cloaked(HWND hwnd) {
        try {
            IntByReference v = new IntByReference();
            return Win32.Dwm.I.DwmGetWindowAttribute(hwnd, Win32.DWMWA_CLOAKED, v.getPointer(), 4) == 0 && v.getValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Where the real mouse pointer is on the desktop (physical pixels), or null. Any thread. */
    public static int[] cursorPos() {
        if (!WINDOWS) return null;
        try {
            dpiAware();
            POINT p = new POINT();
            return Win32.User32.I.GetCursorPos(p) ? new int[]{p.x, p.y} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dpiAware() {
        try {
            Win32.User32.I.SetThreadDpiAwarenessContext(Win32.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        } catch (Throwable ignored) {
        }
    }

    private WinGeom() {}
}
