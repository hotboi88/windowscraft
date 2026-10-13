package dev.windowscraft.capture.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HBRUSH;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Minimal, explicitly-declared Win32 bindings. Declaring exactly what we call (instead of relying
 * on jna-platform's large interfaces) keeps the signatures auditable. JNA itself ships with
 * Minecraft, so nothing extra is bundled.
 */
final class Win32 {
    private Win32() {}

    // window styles / commands
    static final int GWL_STYLE = -16, GWL_EXSTYLE = -20;
    static final int WS_EX_TOOLWINDOW = 0x00000080, WS_EX_NOACTIVATE = 0x08000000;
    static final int WS_CHILD = 0x40000000;
    static final int GW_OWNER = 4;
    static final int GCLP_HICON = -14, GCLP_HICONSM = -34;
    static final int WM_GETICON = 0x007F, ICON_SMALL = 0, ICON_BIG = 1, ICON_SMALL2 = 2;
    static final int SMTO_ABORTIFHUNG = 0x0002, SMTO_BLOCK = 0x0001;
    // capture
    static final int PW_CLIENTONLY = 0x1, PW_RENDERFULLCONTENT = 0x2;
    static final int SRCCOPY = 0x00CC0020;
    static final int HALFTONE = 4, COLORONCOLOR = 3;
    static final int DIB_RGB_COLORS = 0, BI_RGB = 0;
    static final int DI_NORMAL = 0x3, DI_DEFAULTSIZE = 0x8;
    static final int CURSOR_SHOWING = 0x1;
    static final int MONITORINFOF_PRIMARY = 0x1;
    // dwm
    static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9, DWMWA_CLOAKED = 14;
    // process
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    static final Pointer DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4L);

    interface User32 extends StdCallLibrary {
        User32 I = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean EnumWindows(WinUser.WNDENUMPROC cb, Pointer data);
        boolean IsWindow(HWND hwnd);
        boolean IsWindowVisible(HWND hwnd);
        boolean IsIconic(HWND hwnd);
        int GetWindowTextLength(HWND hwnd);
        int GetWindowText(HWND hwnd, char[] buf, int max);
        int GetClassName(HWND hwnd, char[] buf, int max);
        HWND GetWindow(HWND hwnd, int cmd);
        int GetWindowLong(HWND hwnd, int index);
        boolean GetClientRect(HWND hwnd, RECT rect);
        boolean GetWindowRect(HWND hwnd, RECT rect);
        boolean ClientToScreen(HWND hwnd, POINT point);
        int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);
        HDC GetDC(HWND hwnd);
        int ReleaseDC(HWND hwnd, HDC hdc);
        boolean PrintWindow(HWND hwnd, HDC hdc, int flags);
        boolean EnumDisplayMonitors(HDC hdc, RECT clip, WinUser.MONITORENUMPROC cb, LPARAM data);
        boolean GetMonitorInfo(WinUser.HMONITOR monitor, WinUser.MONITORINFOEX info);
        boolean GetCursorInfo(CURSORINFO info);
        HICON LoadCursorW(com.sun.jna.platform.win32.WinDef.HINSTANCE instance, Pointer name);
        boolean GetIconInfo(HICON icon, WinGDI.ICONINFO info);
        boolean DrawIconEx(HDC hdc, int x, int y, HICON icon, int cx, int cy, int step, HBRUSH flicker, int flags);
        LRESULT SendMessageTimeout(HWND hwnd, int msg, WPARAM w, LPARAM l, int flags, int timeoutMs, PointerByReference result);
        Pointer GetClassLongPtr(HWND hwnd, int index);
        Pointer SetThreadDpiAwarenessContext(Pointer ctx); // Windows 10 1607+
        Pointer GetWindowDpiAwarenessContext(HWND hwnd);  // Windows 10 1607+
        boolean IsHungAppWindow(HWND hwnd);
        boolean GetCursorPos(POINT point);
        boolean AllowSetForegroundWindow(int pid);
        boolean ClipCursor(RECT rect);
    }

    interface Gdi32 extends StdCallLibrary {
        Gdi32 I = Native.load("gdi32", Gdi32.class, W32APIOptions.DEFAULT_OPTIONS);

        HDC CreateCompatibleDC(HDC hdc);
        HBITMAP CreateDIBSection(HDC hdc, WinGDI.BITMAPINFO bmi, int usage, PointerByReference bits, Pointer section, int offset);
        HANDLE SelectObject(HDC hdc, HANDLE obj);
        boolean DeleteObject(HANDLE obj);
        boolean DeleteDC(HDC hdc);
        boolean BitBlt(HDC dst, int x, int y, int w, int h, HDC src, int sx, int sy, int rop);
        boolean StretchBlt(HDC dst, int x, int y, int w, int h, HDC src, int sx, int sy, int sw, int sh, int rop);
        int SetStretchBltMode(HDC hdc, int mode);
        boolean SetBrushOrgEx(HDC hdc, int x, int y, Pointer prev);
        boolean GdiFlush();
        int GetObject(HANDLE obj, int size, Pointer out);
    }

    interface Dwm extends StdCallLibrary {
        Dwm I = Native.load("dwmapi", Dwm.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
    }

    interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetCurrentProcessId();
        HANDLE OpenProcess(int access, boolean inherit, int pid);
        boolean QueryFullProcessImageName(HANDLE process, int flags, char[] name, IntByReference size);
        boolean CloseHandle(HANDLE h);
    }

    @Structure.FieldOrder({"cbSize", "flags", "hCursor", "ptScreenPos"})
    public static class CURSORINFO extends Structure {
        public int cbSize;
        public int flags;
        public HICON hCursor;
        public POINT ptScreenPos;

        public CURSORINFO() {
            cbSize = size();
        }
    }

    /** Opt the calling thread into per-monitor DPI awareness so coordinates are physical pixels. */
    static void makeThreadDpiAware() {
        try {
            User32.I.SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        } catch (UnsatisfiedLinkError ignored) {
            // Older Windows: GLFW already made the process DPI aware where it could.
        }
    }
}
