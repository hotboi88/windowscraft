package dev.windowscraft.capture.win;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinUser.HMONITOR;

/** Native handles FFmpeg's GPU capture needs. */
public final class WinHandles {
    private static final int MONITOR_DEFAULTTONEAREST = 2;

    /** The HMONITOR of the monitor containing a point (physical desktop coordinates). */
    public static long monitorAt(int x, int y) {
        HMONITOR m = User32.INSTANCE.MonitorFromPoint(new POINT.ByValue(x, y), MONITOR_DEFAULTTONEAREST);
        return m == null ? 0 : Pointer.nativeValue(m.getPointer());
    }

    /**
     * The HMONITOR to capture for a screen source. An in-game monitor is only ever itself: if it's
     * gone (or Windows renamed it), 0 — capture fails rather than show one of your real screens.
     */
    public static long monitorFor(dev.windowscraft.capture.SourceInfo s) {
        if (!dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(s)) return monitorAt(s.left() + 1, s.top() + 1);
        var m = dev.windowscraft.monitor.InGameMonitors.monitorOf(s);
        if (m == null) return 0;
        HMONITOR h = User32.INSTANCE.MonitorFromPoint(new POINT.ByValue(m.left() + m.width() / 2, m.top() + m.height() / 2), 0 /* MONITOR_DEFAULTTONULL */);
        if (h == null) return 0;
        com.sun.jna.platform.win32.WinUser.MONITORINFOEX info = new com.sun.jna.platform.win32.WinUser.MONITORINFOEX();
        if (!Win32.User32.I.GetMonitorInfo(h, info)) return 0;
        String device = com.sun.jna.Native.toString(info.szDevice);
        return device.equalsIgnoreCase(m.device()) ? Pointer.nativeValue(h.getPointer()) : 0;
    }

    private WinHandles() {}

    /** Gives a handle back to Windows (one the capture helper copied into this process). */
    public static void close(long handle) {
        Win32.Kernel32.I.CloseHandle(new com.sun.jna.platform.win32.WinNT.HANDLE(new com.sun.jna.Pointer(handle)));
    }
}
