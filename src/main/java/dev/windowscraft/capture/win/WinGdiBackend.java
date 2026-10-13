package dev.windowscraft.capture.win;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import dev.windowscraft.capture.CaptureBackend;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FrameProducer;
import dev.windowscraft.capture.SourceInfo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Windows capture using GDI: BitBlt for monitors, PrintWindow(PW_RENDERFULLCONTENT) for
 * individual windows (works even when the window is behind Minecraft). This is the dependable
 * first backend; a Windows.Graphics.Capture helper (GPU, zero-copy) is the planned upgrade.
 */
public final class WinGdiBackend implements CaptureBackend {
    private static final Set<String> SKIP_CLASSES = Set.of(
            "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Windows.UI.Core.CoreWindow",
            "NotifyIconOverflowWindow", "XamlExplorerHostIslandWindow", "TopLevelWindowForOverflowXamlIsland");
    private final int ownPid;

    public WinGdiBackend() {
        ownPid = Win32.Kernel32.I.GetCurrentProcessId(); // also forces the bindings to load now
    }

    @Override public boolean isSupported() { return true; }
    @Override public String unsupportedReason() { return null; }

    @Override
    public synchronized List<SourceInfo> listWindows() {
        Win32.makeThreadDpiAware();
        List<SourceInfo> out = new ArrayList<>();
        char[] buf = new char[512];
        Win32.User32.I.EnumWindows((hwnd, data) -> {
            try {
                SourceInfo s = describeWindow(hwnd, buf);
                if (s != null) out.add(s);
            } catch (Throwable ignored) {
                // a single odd window must never break the list
            }
            return true;
        }, null);
        return out;
    }

    private SourceInfo describeWindow(HWND hwnd, char[] buf) {
        Win32.User32 u = Win32.User32.I;
        if (!u.IsWindowVisible(hwnd)) return null;
        if (u.GetWindow(hwnd, Win32.GW_OWNER) != null) return null;
        int ex = u.GetWindowLong(hwnd, Win32.GWL_EXSTYLE);
        if ((ex & Win32.WS_EX_TOOLWINDOW) != 0) return null;
        if ((u.GetWindowLong(hwnd, Win32.GWL_STYLE) & Win32.WS_CHILD) != 0) return null;
        if (isCloaked(hwnd)) return null;
        int len = u.GetWindowTextLength(hwnd);
        if (len <= 0) return null;
        int n = u.GetWindowText(hwnd, buf, buf.length);
        String title = new String(buf, 0, Math.max(0, n)).trim();
        if (title.isEmpty()) return null;
        n = u.GetClassName(hwnd, buf, buf.length);
        String cls = new String(buf, 0, Math.max(0, n));
        if (SKIP_CLASSES.contains(cls)) return null;
        IntByReference pid = new IntByReference();
        u.GetWindowThreadProcessId(hwnd, pid);
        if (pid.getValue() == ownPid) return null; // never offer Minecraft itself (infinite mirror)
        RECT r = new RECT();
        boolean minimized = u.IsIconic(hwnd);
        if (!minimized) {
            u.GetClientRect(hwnd, r);
            if (r.right - r.left < 32 || r.bottom - r.top < 32) return null;
        }
        String exe = exeName(pid.getValue());
        String sub = (exe.isEmpty() ? "" : exe) + (minimized ? " · minimized" : "");
        long handle = Pointer.nativeValue(hwnd.getPointer());
        return new SourceInfo(SourceInfo.Kind.WINDOW, "win:" + Long.toHexString(handle), title, sub, handle,
                0, 0, r.right - r.left, r.bottom - r.top, exe, false, pid.getValue());
    }

    static boolean isCloaked(HWND hwnd) {
        try (Memory m = new Memory(4)) {
            m.setInt(0, 0);
            int hr = Win32.Dwm.I.DwmGetWindowAttribute(hwnd, Win32.DWMWA_CLOAKED, m, 4);
            return hr == 0 && m.getInt(0) != 0;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static String exeName(int pid) {
        HANDLE h = Win32.Kernel32.I.OpenProcess(Win32.PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == null) return "";
        try {
            char[] name = new char[1024];
            IntByReference size = new IntByReference(name.length);
            if (!Win32.Kernel32.I.QueryFullProcessImageName(h, 0, name, size)) return "";
            String path = new String(name, 0, size.getValue());
            int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return path.substring(slash + 1);
        } finally {
            Win32.Kernel32.I.CloseHandle(h);
        }
    }

    @Override
    public synchronized List<SourceInfo> listScreens() {
        Win32.makeThreadDpiAware();
        List<SourceInfo> out = new ArrayList<>();
        Win32.User32.I.EnumDisplayMonitors(null, null, (hMonitor, hdc, rect, data) -> {
            WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
            if (Win32.User32.I.GetMonitorInfo(hMonitor, info)) {
                RECT m = info.rcMonitor;
                String device = cString(info.szDevice);
                if (dev.windowscraft.monitor.InGameMonitors.ownsDevice(device)) return 1; // in-game monitors aren't "your screens"
                boolean primary = (info.dwFlags & Win32.MONITORINFOF_PRIMARY) != 0;
                out.add(new SourceInfo(SourceInfo.Kind.SCREEN, "mon:" + device, "", (m.right - m.left) + "×" + (m.bottom - m.top),
                        0, m.left, m.top, m.right - m.left, m.bottom - m.top, "", primary));
            }
            return 1;
        }, new LPARAM(0));
        // Name them like Windows does: primary first, "Screen 1", "Screen 2", …
        out.sort((a, b) -> a.primary() == b.primary() ? Integer.compare(a.left(), b.left()) : (a.primary() ? -1 : 1));
        List<SourceInfo> named = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            SourceInfo s = out.get(i);
            String title = "Screen " + (i + 1) + (s.primary() ? " (main)" : "");
            named.add(new SourceInfo(s.kind(), s.id(), title, s.subtitle(), 0, s.left(), s.top(), s.width(), s.height(), "", s.primary()));
        }
        return named;
    }

    /** Current bounds of a monitor by its device id ("mon:\\.\DISPLAY1"), or null if it's gone. */
    static RECT monitorRect(String id) {
        RECT[] found = new RECT[1];
        // an in-game monitor ("vmon:<index>:<device>") is found by its device name only while it's ours
        String want = id.startsWith("vmon:") ? id.substring(id.indexOf(':', 5) + 1) : id.startsWith("mon:") ? id.substring(4) : null;
        if (want == null || id.startsWith("vmon:") && !dev.windowscraft.monitor.InGameMonitors.ownsDevice(want)) return null;
        Win32.User32.I.EnumDisplayMonitors(null, null, (hMonitor, hdc, rect, data) -> {
            WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
            if (Win32.User32.I.GetMonitorInfo(hMonitor, info) && cString(info.szDevice).equalsIgnoreCase(want)) {
                found[0] = info.rcMonitor;
                return 0;
            }
            return 1;
        }, new LPARAM(0));
        return found[0];
    }

    private static String cString(char[] chars) {
        int n = 0;
        while (n < chars.length && chars[n] != 0) n++;
        return new String(chars, 0, n);
    }

    @Override
    public Frame thumbnail(SourceInfo source, int maxW, int maxH) {
        Win32.makeThreadDpiAware();
        if (source.kind() == SourceInfo.Kind.WINDOW && GdiProducer.isHung(source.handle())) return null;
        try (FrameProducer p = open(source)) {
            if (p instanceof GdiProducer g) return g.grabScaledOnce(maxW, maxH);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public Frame icon(SourceInfo source) {
        if (source.kind() != SourceInfo.Kind.WINDOW) return null;
        HWND hwnd = new HWND(new Pointer(source.handle()));
        HICON icon = findIcon(hwnd);
        if (icon == null) return null;
        HDC screen = Win32.User32.I.GetDC(null);
        try (DibSurface s = new DibSurface(screen, 32, 32)) {
            s.clear();
            if (!Win32.User32.I.DrawIconEx(s.dc, 0, 0, icon, 32, 32, 0, null, Win32.DI_NORMAL)) return null;
            Win32.Gdi32.I.GdiFlush();
            Frame f = Frame.allocate(32, 32);
            ByteBuffer px = s.pixels();
            f.data.put(px).flip();
            fixIconAlpha(f.data);
            f.alphaValid = true;
            return f;
        } finally {
            Win32.User32.I.ReleaseDC(null, screen);
        }
    }

    /** Old-style icons have no alpha channel; treat any drawn pixel as opaque. */
    private static void fixIconAlpha(ByteBuffer bgra) {
        boolean anyAlpha = false;
        for (int i = 3; i < bgra.limit(); i += 4) if (bgra.get(i) != 0) { anyAlpha = true; break; }
        if (anyAlpha) return;
        for (int i = 0; i < bgra.limit(); i += 4) {
            if (bgra.get(i) != 0 || bgra.get(i + 1) != 0 || bgra.get(i + 2) != 0) bgra.put(i + 3, (byte) 0xFF);
        }
    }

    private static HICON findIcon(HWND hwnd) {
        int[] kinds = {Win32.ICON_BIG, Win32.ICON_SMALL2, Win32.ICON_SMALL};
        for (int k : kinds) {
            PointerByReference result = new PointerByReference();
            Win32.User32.I.SendMessageTimeout(hwnd, Win32.WM_GETICON, new WPARAM(k), new LPARAM(0),
                    Win32.SMTO_ABORTIFHUNG | Win32.SMTO_BLOCK, 40, result);
            if (result.getValue() != null) return new HICON(result.getValue());
        }
        Pointer p = Win32.User32.I.GetClassLongPtr(hwnd, Win32.GCLP_HICON);
        if (p == null) p = Win32.User32.I.GetClassLongPtr(hwnd, Win32.GCLP_HICONSM);
        return p == null ? null : new HICON(p);
    }

    @Override
    public FrameProducer open(SourceInfo source) {
        return switch (source.kind()) {
            case SCREEN -> new GdiProducer.Monitor(source);
            case WINDOW -> new GdiProducer.Window(source);
            case TEST -> throw new IllegalArgumentException("test pattern is handled by CaptureBackends");
        };
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
