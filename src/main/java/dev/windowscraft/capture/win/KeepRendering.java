package dev.windowscraft.capture.win;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import dev.windowscraft.WindowsCraftClient;
import java.util.Locale;

/**
 * Keeps covered windows drawing while they're being shared.
 *
 * <p>Chrome, Edge, Discord, Spotify and other Chromium/Electron apps stop painting a window that
 * is completely covered by other windows (Minecraft in fullscreen, any maximized window), so a
 * capture of it freezes — the same thing happens when you share such a window in Discord.
 * Chromium makes one exception: while the taskbar's window-preview popup ("TaskListThumbnailWnd")
 * or Task View is showing, it treats all of its windows as visible, because those previews need
 * live content. This class shows an invisible 1×1 window with that class name while a window is
 * being shared, and re-announces it every couple of seconds (hovering the real taskbar previews
 * and moving away would otherwise clear it).
 *
 * <p>The helper is click-through, fully transparent, off-screen, never activated, and hidden from
 * the taskbar and Alt+Tab. It changes nothing in any other program.
 */
public final class KeepRendering {
    private static final String CLASS_NAME = "TaskListThumbnailWnd"; // Chromium: native_window_occlusion_tracker_win.cc
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080, WS_EX_NOACTIVATE = 0x08000000, WS_EX_LAYERED = 0x00080000,
            WS_EX_TRANSPARENT = 0x00000020;
    private static final int LWA_ALPHA = 0x2, SW_HIDE = 0, SW_SHOWNOACTIVATE = 4, PM_REMOVE = 1;
    /** Safety re-announce (e.g. a Chromium app that started tracking after us). Rare: each one is a tiny flicker of state. */
    private static final long REFRESH_MS = 60_000;
    private static final int EVENT_OBJECT_HIDE = 0x8003, WINEVENT_OUTOFCONTEXT = 0, WINEVENT_SKIPOWNPROCESS = 2;
    private static volatile boolean previewClosed;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static Thread thread;
    private static volatile boolean wanted;
    private static boolean failed;

    /** Turn on while at least one app window is being shared; off otherwise. Any thread. */
    public static synchronized void setActive(boolean on) {
        if (!WINDOWS || failed || on == wanted) return;
        wanted = on;
        if (on && (thread == null || !thread.isAlive())) {
            thread = new Thread(KeepRendering::run, "WindowsCraft keep-rendering");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void run() {
        User32 u = User32.INSTANCE;
        HINSTANCE inst = new HINSTANCE();
        inst.setPointer(Kernel32.INSTANCE.GetModuleHandle(null).getPointer());
        WinUser.WindowProc proc = (HWND h, int msg, WPARAM w, LPARAM l) -> u.DefWindowProc(h, msg, w, l);
        WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
        wc.hInstance = inst;
        wc.lpfnWndProc = proc;
        wc.lpszClassName = CLASS_NAME;
        HWND hwnd = null;
        try {
            // registering twice (e.g. after a previous run) fails harmlessly; CreateWindowEx then uses the existing class
            u.RegisterClassEx(wc);
            hwnd = u.CreateWindowEx(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_LAYERED | WS_EX_TRANSPARENT, CLASS_NAME, "",
                    WS_POPUP, -32000, -32000, 1, 1, null, null, inst, null);
            if (hwnd == null) throw new IllegalStateException("CreateWindowEx failed (" + Kernel32.INSTANCE.GetLastError() + ")");
            u.SetLayeredWindowAttributes(hwnd, 0, (byte) 0, LWA_ALPHA); // fully transparent
            WindowsCraftClient.LOGGER.info("Keeping shared windows rendering even when covered");
            // Re-announce right after the *real* taskbar previews / Task View close (that's what
            // makes Chromium forget), instead of on a timer. Out-of-context hook: delivered through
            // this thread's message loop below.
            WinUser.WinEventProc onHide = (hook, event, h, idObject, idChild, thread, time) -> {
                if (h == null || idObject.intValue() != 0) return;
                char[] cls = new char[64];
                u.GetClassName(h, cls, cls.length);
                String c = com.sun.jna.Native.toString(cls);
                if (c.equals(CLASS_NAME) || c.equals("MultitaskingViewFrame")) previewClosed = true;
            };
            com.sun.jna.platform.win32.WinNT.HANDLE hook = u.SetWinEventHook(EVENT_OBJECT_HIDE, EVENT_OBJECT_HIDE, null, onHide, 0, 0,
                    WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS);
            WinUser.MSG msg = new WinUser.MSG();
            long next = 0, previewAt = 0;
            while (wanted) {
                long now = System.currentTimeMillis();
                if (previewClosed) { // give Chromium a moment to handle the real hide first
                    previewClosed = false;
                    previewAt = now + 150;
                }
                if (now >= next || (previewAt != 0 && now >= previewAt)) {
                    previewAt = 0;
                    // hide + show in one go: Chromium sees "previews closed, previews opened" and
                    // keeps treating its windows as visible (a no-op if it already does)
                    u.ShowWindow(hwnd, SW_HIDE);
                    u.ShowWindow(hwnd, SW_SHOWNOACTIVATE);
                    next = now + REFRESH_MS;
                }
                while (u.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                    u.TranslateMessage(msg);
                    u.DispatchMessage(msg);
                }
                Thread.sleep(50);
            }
            if (hook != null) u.UnhookWinEvent(hook);
            if (onHide.hashCode() == 0) WindowsCraftClient.LOGGER.debug("keep hook reachable"); // keep the callback alive
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failed = true;
            WindowsCraftClient.LOGGER.warn("Couldn't keep covered windows rendering; cover a shared browser and it may freeze", t);
        } finally {
            if (hwnd != null) {
                u.ShowWindow(hwnd, SW_HIDE); // apps go back to normal occlusion handling
                u.DestroyWindow(hwnd);
            }
            u.UnregisterClass(CLASS_NAME, inst);
            synchronized (KeepRendering.class) {
                if (wanted && !failed) { // re-enabled while we were shutting down
                    thread = new Thread(KeepRendering::run, "WindowsCraft keep-rendering");
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }
        if (proc.hashCode() == 0) WindowsCraftClient.LOGGER.debug("keep proc reachable"); // keep the callback alive until here
    }

    private KeepRendering() {}
}
