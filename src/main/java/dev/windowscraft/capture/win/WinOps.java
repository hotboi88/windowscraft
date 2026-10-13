package dev.windowscraft.capture.win;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import dev.windowscraft.WindowsCraftClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What a screen does to the real app windows on your PC, all on one background thread so a
 * busy app can never stall the game.
 *
 * <p>While an app is on a screen, its real window is "parked": moved just outside your monitors
 * (where nothing covers it) and sized to exactly what it is on the screen, so the picture is
 * pixel-for-pixel and every spot on the screen is the same spot in the app. Being uncovered
 * matters: Chrome, Edge, Discord and friends ignore scrolling over a spot where Windows says
 * another program's window is on top. When the app leaves the screen — or the game closes — the
 * window goes back exactly where it was. A small file remembers parked windows so even a crash
 * can't leave one lost off-screen (they're put back on the next start).
 */
public final class WinOps {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WindowsCraft windows");
        t.setDaemon(true);
        return t;
    });

    // ---- hit-test results (WM_NCHITTEST) ----
    static final int HTERROR = -2, HTTRANSPARENT = -1, HTNOWHERE = 0, HTCLIENT = 1, HTCAPTION = 2, HTSYSMENU = 3,
            HTMINBUTTON = 8, HTMAXBUTTON = 9, HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14,
            HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17, HTCLOSE = 20;

    /** Where a window is on the real desktop: the visible frame and its client area, physical pixels. */
    /**
     * Where a window is and what state it's in. {@code atHome}: still where we parked it (an app can
     * move itself: maximize, fullscreen, restore); {@code fullscreen}: it covers a whole monitor.
     */
    public record Geometry(int frameX, int frameY, int frameW, int frameH, int clientX, int clientY, int clientW, int clientH,
                    boolean minimized, boolean maximized, boolean fullscreen, boolean atHome, boolean hidden) {
        /** A point inside the visible frame (0..1) → client coordinates (0..1, may be outside). */
        double[] frameToClient(double fx, double fy) {
            double sx = frameX + fx * frameW, sy = frameY + fy * frameH;
            return new double[]{(sx - clientX) / Math.max(1, clientW), (sy - clientY) / Math.max(1, clientH)};
        }
    }

    interface User32 extends StdCallLibrary {
        User32 I = WINDOWS ? Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS) : null;

        boolean IsWindow(HWND h);
        boolean IsWindowVisible(HWND h);
        boolean GetWindowRect(HWND h, RECT r);
        boolean GetClientRect(HWND h, RECT r);
        boolean ClientToScreen(HWND h, POINT p);
        boolean SetWindowPos(HWND h, HWND after, int x, int y, int w, int hgt, int flags);
        boolean ShowWindow(HWND h, int cmd);
        int GetWindowLong(HWND h, int index);
        boolean GetWindowPlacement(HWND h, WinUser.WINDOWPLACEMENT p);
        boolean SetWindowPlacement(HWND h, WinUser.WINDOWPLACEMENT p);
        boolean PostMessage(HWND h, int msg, WPARAM w, LPARAM l);
        com.sun.jna.platform.win32.WinDef.LRESULT SendMessageTimeout(HWND h, int msg, WPARAM w, LPARAM l, int flags, int timeout, PointerByReference result);
        int GetSystemMetrics(int index);
        boolean SetForegroundWindow(HWND h);
        boolean LockSetForegroundWindow(int code);
        Pointer SetThreadDpiAwarenessContext(Pointer ctx);
    }

    interface Dwm extends StdCallLibrary {
        Dwm I = WINDOWS ? Native.load("dwmapi", Dwm.class, W32APIOptions.DEFAULT_OPTIONS) : null;

        int DwmGetWindowAttribute(HWND h, int attr, Pointer value, int size);
    }

    private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10, SWP_NOOWNERZORDER = 0x200,
            SWP_ASYNCWINDOWPOS = 0x4000;
    private static final int WS_MINIMIZE = 0x20000000, WS_MAXIMIZE = 0x01000000;
    private static final int SW_SHOWNOACTIVATE = 4, SW_RESTORE = 9;
    private static final int WM_NCHITTEST = 0x0084, WM_SYSCOMMAND = 0x0112, SC_CLOSE = 0xF060, WM_NCACTIVATE = 0x0086;
    private static final int SMTO_ABORTIFHUNG = 0x2;
    private static final int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77;

    /** Parked windows: where each was before (to put it back). */
    private static final Map<Long, WinUser.WINDOWPLACEMENT> PARKED = new ConcurrentHashMap<>();
    /** Windows started from a screen: they close with it (like apps inside WaylandCraft) instead of coming back. */
    private static final java.util.Set<Long> LAUNCHED = ConcurrentHashMap.newKeySet();

    static void markLaunched(long handle) { LAUNCHED.add(handle); }

    /** Where each parked window was put (its window rect's top left). */
    private static final Map<Long, int[]> HOME = new ConcurrentHashMap<>();
    /** Which parking cell each parked window has. */
    private static final Map<Long, Integer> CELLS = new ConcurrentHashMap<>();
    private static boolean hooked;
    /** Minecraft's own window (gets the focus back after a maximized app is put back). */
    public static volatile long gameWindow;

    public static boolean available() { return WINDOWS && User32.I != null; }

    interface Advapi extends StdCallLibrary {
        Advapi I = WINDOWS ? Native.load("advapi32", Advapi.class, W32APIOptions.DEFAULT_OPTIONS) : null;

        boolean OpenProcessToken(com.sun.jna.platform.win32.WinNT.HANDLE process, int access, com.sun.jna.platform.win32.WinNT.HANDLEByReference token);
        boolean GetTokenInformation(com.sun.jna.platform.win32.WinNT.HANDLE token, int infoClass, Pointer info, int length, com.sun.jna.ptr.IntByReference returned);
    }

    /** Windows' own shell and tools: they can't be worked from a screen (or shouldn't be taken off your desktop). */
    private static final java.util.Set<String> NOT_ON_SCREENS = java.util.Set.of("explorer.exe", "applicationframehost.exe", "systemsettings.exe",
            "taskmgr.exe", "mmc.exe", "regedit.exe", "control.exe", "shellexperiencehost.exe", "startmenuexperiencehost.exe",
            "searchhost.exe", "searchapp.exe", "textinputhost.exe", "lockapp.exe", "windowsterminal.exe", "openconsole.exe", "conhost.exe");
    private static final Map<Long, Boolean> ELEVATED = new ConcurrentHashMap<>();

    /**
     * Can this window be used on a screen? Not Windows' own tools or Store-style apps (they only
     * listen to the real mouse and keyboard), and not apps running as administrator (Windows
     * doesn't let other programs send them input).
     */
    public static boolean usable(dev.windowscraft.capture.SourceInfo w) {
        if (!available() || w.kind() != dev.windowscraft.capture.SourceInfo.Kind.WINDOW) return true;
        if (NOT_ON_SCREENS.contains(w.exeName().toLowerCase(Locale.ROOT))) return false;
        return !elevated(w.pid());
    }

    /** Runs as administrator? (If we're not allowed to look at its rights, it does.) */
    static boolean elevated(long pid) {
        if (pid <= 0 || Advapi.I == null) return false;
        return ELEVATED.computeIfAbsent(pid, p -> {
            var k = com.sun.jna.platform.win32.Kernel32.INSTANCE;
            var proc = k.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, (int) (long) p);
            if (proc == null) return true;
            try {
                var token = new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
                if (!Advapi.I.OpenProcessToken(proc, 0x8 /* TOKEN_QUERY */, token)) return true;
                try (Memory m = new Memory(4)) {
                    var ret = new com.sun.jna.ptr.IntByReference();
                    boolean ok = Advapi.I.GetTokenInformation(token.getValue(), 20 /* TokenElevation */, m, 4, ret);
                    return ok && m.getInt(0) != 0 && !selfElevated();
                } finally {
                    k.CloseHandle(token.getValue());
                }
            } finally {
                k.CloseHandle(proc);
            }
        });
    }

    private static Boolean self;

    /** Is Minecraft itself running as administrator? (Then it can talk to elevated apps too.) */
    private static boolean selfElevated() {
        if (self == null) {
            long pid = ProcessHandle.current().pid();
            self = false;
            boolean e = elevatedRaw(pid);
            self = e;
        }
        return self;
    }

    private static boolean elevatedRaw(long pid) {
        var k = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var proc = k.OpenProcess(0x1000, false, (int) pid);
        if (proc == null) return false;
        try {
            var token = new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
            if (!Advapi.I.OpenProcessToken(proc, 0x8, token)) return false;
            try (Memory m = new Memory(4)) {
                var ret = new com.sun.jna.ptr.IntByReference();
                return Advapi.I.GetTokenInformation(token.getValue(), 20, m, 4, ret) && m.getInt(0) != 0;
            } finally {
                k.CloseHandle(token.getValue());
            }
        } finally {
            k.CloseHandle(proc);
        }
    }

    /** A window under a point of a monitor (not the game): its handle and its client area on the desktop. */
    public record At(long handle, int clientX, int clientY, int clientW, int clientH) {}

    /**
     * The top window at that point of your desktop (physical pixels), skipping the game itself,
     * hidden and tool windows — what a click there would land on if Minecraft weren't in front.
     */
    public static CompletableFuture<At> windowAt(int sx, int sy) {
        if (!available()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> windowAtNow(sx, sy), WORKER);
    }

    private static At windowAtNow(int sx, int sy) {
        dpiAware();
        At[] found = {null};
        var u = com.sun.jna.platform.win32.User32.INSTANCE;
        u.EnumWindows((hwnd, data) -> {
            long h = Pointer.nativeValue(hwnd.getPointer());
            if (h == gameWindow || !User32.I.IsWindowVisible(hwnd)) return true;
            if ((User32.I.GetWindowLong(hwnd, -20) & 0x80 /* WS_EX_TOOLWINDOW */) != 0) return true;
            if (WinOps.cloakedWin(hwnd)) return true;
            RECT r = new RECT();
            if (!User32.I.GetWindowRect(hwnd, r) || sx < r.left || sx >= r.right || sy < r.top || sy >= r.bottom) return true;
            RECT cr = new RECT();
            POINT o = new POINT(0, 0);
            if (!User32.I.GetClientRect(hwnd, cr) || !User32.I.ClientToScreen(hwnd, o)) return true;
            found[0] = new At(h, o.x, o.y, Math.max(1, cr.right), Math.max(1, cr.bottom));
            return false; // the top-most one
        }, null);
        return found[0];
    }

    /** The window's style bits (WS_*), or 0. */
    static int style(long handle) {
        if (!available()) return 0;
        try {
            return User32.I.GetWindowLong(hwnd(handle), -16);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static HWND hwnd(long h) { return new HWND(new Pointer(h)); }

    private static void dpiAware() {
        try {
            User32.I.SetThreadDpiAwarenessContext(Pointer.createConstant(-4L)); // per-monitor v2: physical pixels
        } catch (Throwable ignored) {
        }
    }

    private static void run(String what, Runnable r) {
        if (!available()) return;
        WORKER.execute(() -> {
            try {
                dpiAware();
                r.run();
            } catch (Throwable t) {
                WindowsCraftClient.LOGGER.debug("window op {} failed", what, t);
            }
        });
    }

    // ---- parking ------------------------------------------------------------------------------

    /**
     * Moves the window to its own spot outside your monitors and gives its visible frame exactly
     * {@code w × h} physical pixels. First time: remembers where it was.
     */
    public static void park(long handle, int w, int h) {
        run("park", () -> {
            installHook();
            HWND win = hwnd(handle);
            if (!User32.I.IsWindow(win)) return;
            if (!PARKED.containsKey(handle)) {
                WinUser.WINDOWPLACEMENT p = new WinUser.WINDOWPLACEMENT();
                if (User32.I.GetWindowPlacement(win, p)) {
                    PARKED.put(handle, p);
                    saveParked();
                }
            }
            int style = User32.I.GetWindowLong(win, -16);
            if ((style & (WS_MINIMIZE | WS_MAXIMIZE)) != 0) User32.I.ShowWindow(win, SW_SHOWNOACTIVATE); // can't size a maximized one
            int cell = CELLS.computeIfAbsent(handle, k -> freeCell());
            int[] insets = insets(win);
            int outerW = w + insets[0] + insets[2], outerH = h + insets[1] + insets[3];
            int[] at = cellOrigin(cell, outerW);
            // (it stays on your taskbar: it's only away for the moment you're using it)
            HOME.put(handle, at);
            User32.I.SetWindowPos(win, null, at[0], at[1], outerW, outerH, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_ASYNCWINDOWPOS);
        });
    }

    /** While an app starts: nothing may take the foreground from the game (or give it back after). */
    public static void lockForeground(boolean lock) {
        if (!available()) return;
        try {
            User32.I.LockSetForegroundWindow(lock ? 1 /* LSFW_LOCK */ : 2 /* LSFW_UNLOCK */);
        } catch (Throwable ignored) {
        }
    }

    /** Right now (any thread): a just-shown window goes off your monitors, and off the taskbar. */
    static void hideNow(long handle) {
        if (!available()) return;
        int x = User32.I.GetSystemMetrics(SM_XVIRTUALSCREEN) - 12000, y = User32.I.GetSystemMetrics(SM_YVIRTUALSCREEN);
        User32.I.SetWindowPos(hwnd(handle), null, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_ASYNCWINDOWPOS);
        dev.windowscraft.apps.InputBridge.send("tab hide " + handle);
    }

    /** Where a window really belongs (it was moved away before it could be parked normally). */
    static void rememberHome(long handle, WinUser.WINDOWPLACEMENT p) {
        if (!available()) return;
        run("home", () -> {
            PARKED.putIfAbsent(handle, p);
            saveParked();
        });
    }

    /** A window hidden while an app was starting that isn't going onto the screen: back where it was. */
    static void putBack(long handle, WinUser.WINDOWPLACEMENT p) {
        if (!available()) return;
        run("put back", () -> {
            HWND win = hwnd(handle);
            if (!User32.I.IsWindow(win)) return;
            if (p.showCmd != 3 && p.showCmd != 2 && p.showCmd != 6 && p.showCmd != 7) p.showCmd = SW_SHOWNOACTIVATE;
            if (!User32.I.IsWindowVisible(win)) p.showCmd = 0;
            User32.I.SetWindowPlacement(win, p);
            dev.windowscraft.apps.InputBridge.send("tab show " + handle);
        });
    }

    /** Puts a parked window back where it was before it went on a screen. */
    public static void unpark(long handle) {
        LAUNCHED.remove(handle);
        run("unpark", () -> restore(handle));
        CELLS.remove(handle);
    }

    /**
     * The screen turned off: windows started from it close (they're put back on your monitor
     * first, so a "save changes?" question shows up where you can see it); others go back home.
     */
    static void leaveScreen(long handle) {
        if (!LAUNCHED.remove(handle)) { unpark(handle); return; }
        run("close", () -> {
            restore(handle);
            User32.I.PostMessage(hwnd(handle), WM_SYSCOMMAND, new WPARAM(SC_CLOSE), new LPARAM(0));
        });
        CELLS.remove(handle);
    }

    private static void restore(long handle) {
        WinUser.WINDOWPLACEMENT p = PARKED.remove(handle);
        CELLS.remove(handle);
        HOME.remove(handle);
        if (p == null) return;
        HWND win = hwnd(handle);
        dev.windowscraft.apps.InputBridge.send("tab show " + handle);
        if (User32.I.IsWindow(win)) {
            if (p.showCmd != 3 /* SW_SHOWMAXIMIZED */ && p.showCmd != 2 && p.showCmd != 6 && p.showCmd != 7) p.showCmd = SW_SHOWNOACTIVATE;
            if (!User32.I.IsWindowVisible(win)) p.showCmd = 0; // it went to the tray: put it back where it was, still hidden
            User32.I.SetWindowPlacement(win, p);
            // a window that was maximized or minimized went through SetWindowPlacement's show state;
            // keep it behind the game so Minecraft doesn't lose its place in front
            User32.I.SetWindowPos(win, hwnd(1) /* HWND_BOTTOM */, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            if (p.showCmd == 3 && gameWindow != 0) User32.I.SetForegroundWindow(hwnd(gameWindow));
        }
        saveParked();
    }

    /** Game closing: every window goes home (synchronously, before the JVM exits). */
    public static void unparkAll() {
        if (!available()) return;
        try {
            WORKER.submit(() -> {
                dpiAware();
                for (Long h : new ArrayList<>(PARKED.keySet())) {
                    boolean close = LAUNCHED.remove(h);
                    restore(h);
                    if (close) User32.I.PostMessage(hwnd(h), WM_SYSCOMMAND, new WPARAM(SC_CLOSE), new LPARAM(0));
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void installHook() {
        if (hooked) return;
        hooked = true;
        Runtime.getRuntime().addShutdownHook(new Thread(WinOps::unparkAll, "WindowsCraft put windows back"));
    }

    private static int freeCell() {
        for (int i = 0; ; i++) if (!CELLS.containsValue(i)) return i;
    }

    /**
     * Parking cells, 4200×2600 px each: just below the monitor the game is on (so apps keep that
     * monitor's display scaling, the size you're used to), unless another monitor is there; then
     * to the left of all your monitors.
     */
    private static int[] cellOrigin(int cell, int outerW) {
        try {
            var u = com.sun.jna.platform.win32.User32.INSTANCE;
            WinUser.HMONITOR mon = gameWindow > 0 ? u.MonitorFromWindow(hwnd(gameWindow), 2 /* NEAREST */) : null;
            WinUser.MONITORINFO mi = new WinUser.MONITORINFO();
            if (mon != null && u.GetMonitorInfo(mon, mi).booleanValue()) {
                int left = mi.rcMonitor.left, top = mi.rcMonitor.bottom + 300;
                int cols = Math.max(1, Math.min(6, (32000 - left) / 4200));
                int col = cell % cols, row = cell / cols;
                int x = left + col * 4200, y = top + row * 2600;
                RECT probe = new RECT();
                probe.left = x - 100;
                probe.top = y - 100;
                probe.right = x + 4200;
                probe.bottom = y + 2600;
                if (y + 2600 < 32000 && u.MonitorFromRect(probe, 0 /* NULL */) == null) return new int[]{x, y};
            }
        } catch (Throwable ignored) {
        }
        return leftOfMonitors(cell, outerW);
    }

    private static int[] leftOfMonitors(int cell, int outerW) {
        int vx = User32.I.GetSystemMetrics(SM_XVIRTUALSCREEN), vy = User32.I.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int right = vx - 400; // a gap so even shadows stay off the monitors
        int cols = Math.max(1, (right + 32000) / 4200);
        int col = cell % cols, row = cell / cols;
        return new int[]{right - col * 4200 - outerW, vy + row * 2600};
    }

    /** {left, top, right, bottom}: invisible resize borders etc. between the window rect and its visible frame. */
    private static int[] insets(HWND win) {
        RECT wr = new RECT();
        if (!User32.I.GetWindowRect(win, wr)) return new int[4];
        RECT fr = frameBounds(win);
        if (fr == null) return new int[4];
        return new int[]{Math.max(0, fr.left - wr.left), Math.max(0, fr.top - wr.top), Math.max(0, wr.right - fr.right), Math.max(0, wr.bottom - fr.bottom)};
    }

    private static RECT frameBounds(HWND win) {
        try {
            Memory m = new Memory(16);
            if (Dwm.I.DwmGetWindowAttribute(win, 9 /* DWMWA_EXTENDED_FRAME_BOUNDS */, m, 16) != 0) return null;
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

    // ---- geometry and hit tests -----------------------------------------------------------------

    /** The window's visible frame and client area right now. */
    public static CompletableFuture<Geometry> geometry(long handle) {
        if (!available()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            dpiAware();
            HWND win = hwnd(handle);
            RECT fr = frameBounds(win);
            RECT wr = new RECT();
            if (!User32.I.GetWindowRect(win, wr)) return null;
            if (fr == null) fr = wr;
            RECT cr = new RECT();
            POINT o = new POINT(0, 0);
            if (!User32.I.GetClientRect(win, cr) || !User32.I.ClientToScreen(win, o)) return null;
            int style = User32.I.GetWindowLong(win, -16);
            boolean min = (style & WS_MINIMIZE) != 0, max = (style & WS_MAXIMIZE) != 0, full = false;
            try {
                var u = com.sun.jna.platform.win32.User32.INSTANCE;
                WinUser.MONITORINFO mi = new WinUser.MONITORINFO();
                if (!max && !min && u.GetMonitorInfo(u.MonitorFromWindow(win, 2), mi).booleanValue()) {
                    full = Math.abs(wr.left - mi.rcMonitor.left) <= 1 && Math.abs(wr.top - mi.rcMonitor.top) <= 1
                            && Math.abs(wr.right - mi.rcMonitor.right) <= 1 && Math.abs(wr.bottom - mi.rcMonitor.bottom) <= 1;
                }
            } catch (Throwable ignored) {
            }
            int[] home = HOME.get(handle);
            boolean atHome = home == null || (Math.abs(wr.left - home[0]) <= 2 && Math.abs(wr.top - home[1]) <= 2);
            boolean hidden = !User32.I.IsWindowVisible(win) || WinOps.cloakedWin(win);
            return new Geometry(fr.left, fr.top, fr.right - fr.left, fr.bottom - fr.top, o.x, o.y, cr.right - cr.left, cr.bottom - cr.top,
                    min, max, full, atHome, hidden);
        }, WORKER);
    }

    /**
     * What the app says is at that spot of its window (title bar, minimize button, client area…),
     * given as 0..1 of its visible frame. HTCLIENT if the app doesn't answer within 60 ms.
     */
    static int hitTest(long handle, Geometry g, double fx, double fy) {
        if (!available() || g == null) return HTCLIENT;
        try {
            return CompletableFuture.supplyAsync(() -> {
                dpiAware();
                int sx = (int) Math.floor(g.frameX + fx * g.frameW), sy = (int) Math.floor(g.frameY + fy * g.frameH);
                long l = ((long) (sy & 0xFFFF) << 16) | (sx & 0xFFFF);
                PointerByReference res = new PointerByReference();
                var ok = User32.I.SendMessageTimeout(hwnd(handle), WM_NCHITTEST, new WPARAM(0), new LPARAM(l), SMTO_ABORTIFHUNG, 50, res);
                if (ok == null || ok.longValue() == 0) return HTCLIENT;
                return (int) (short) Pointer.nativeValue(res.getValue());
            }, WORKER).get(60, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return HTCLIENT;
        }
    }

    /** The app's own close (like clicking its ×): it may ask to save first. */
    static void close(long handle) {
        run("close", () -> User32.I.PostMessage(hwnd(handle), WM_SYSCOMMAND, new WPARAM(SC_CLOSE), new LPARAM(0)));
    }

    /** Is the window still there and showing (not closed, not hidden in the tray)? */
    static CompletableFuture<Boolean> showing(long handle) {
        if (!available()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            HWND win = hwnd(handle);
            return User32.I.IsWindow(win) && User32.I.IsWindowVisible(win) && !WinOps.cloakedWin(win);
        }, WORKER);
    }

    /** Draw the title bar as active (the window you're using) or inactive. */
    static void activeLook(long handle, boolean active) {
        run("ncactivate", () -> User32.I.PostMessage(hwnd(handle), WM_NCACTIVATE, new WPARAM(active ? 1 : 0), new LPARAM(0)));
    }

    // ---- surviving a crash ---------------------------------------------------------------------

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("parked-windows.txt");
    }

    private static void saveParked() {
        StringBuilder sb = new StringBuilder();
        for (var e : PARKED.entrySet()) {
            WinUser.WINDOWPLACEMENT p = e.getValue();
            sb.append(e.getKey()).append(' ').append(p.showCmd).append(' ').append(p.rcNormalPosition.left).append(' ')
                    .append(p.rcNormalPosition.top).append(' ').append(p.rcNormalPosition.right).append(' ')
                    .append(p.rcNormalPosition.bottom).append('\n');
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), sb.toString());
        } catch (Exception ignored) {
        }
    }

    /** Game start: windows left parked by a crash go back where they were. */
    public static void recoverAfterCrash() {
        if (!available()) return;
        run("recover", () -> {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            List<String> lines;
            try {
                lines = Files.readAllLines(f);
            } catch (Exception e) {
                return;
            }
            int n = 0;
            for (String line : lines) {
                String[] p = line.trim().split(" ");
                if (p.length != 6) continue;
                try {
                    long h = Long.parseLong(p[0]);
                    HWND win = hwnd(h);
                    if (!User32.I.IsWindow(win)) continue;
                    WinUser.WINDOWPLACEMENT wp = new WinUser.WINDOWPLACEMENT();
                    if (!User32.I.GetWindowPlacement(win, wp)) continue;
                    wp.showCmd = Integer.parseInt(p[1]) == 3 ? 3 : SW_SHOWNOACTIVATE;
                    wp.rcNormalPosition.left = Integer.parseInt(p[2]);
                    wp.rcNormalPosition.top = Integer.parseInt(p[3]);
                    wp.rcNormalPosition.right = Integer.parseInt(p[4]);
                    wp.rcNormalPosition.bottom = Integer.parseInt(p[5]);
                    User32.I.SetWindowPlacement(win, wp);
                    n++;
                } catch (RuntimeException ignored) {
                }
            }
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {
            }
            if (n > 0) WindowsCraftClient.LOGGER.info("Put {} app window(s) back where they were after last session", n);
        });
    }

    static boolean cloakedWin(HWND h) {
        try {
            com.sun.jna.ptr.IntByReference v = new com.sun.jna.ptr.IntByReference();
            return Dwm.I.DwmGetWindowAttribute(h, 14 /* DWMWA_CLOAKED */, v.getPointer(), 4) == 0 && v.getValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private WinOps() {}
}
