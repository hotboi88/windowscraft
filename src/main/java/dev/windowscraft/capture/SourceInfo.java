package dev.windowscraft.capture;

/** Something that can be shared: a monitor, an application window, or the built-in test pattern. */
public record SourceInfo(
        Kind kind,
        String id,          // unique while the picker is open, e.g. "win:1a2b3c" / "mon:\\.\DISPLAY1" / "test"
        String title,       // window title or monitor name
        String subtitle,    // exe name or resolution
        long handle,        // HWND for windows, 0 otherwise
        int left, int top, int width, int height, // monitor bounds in physical pixels (monitors only)
        String exeName,     // e.g. "chrome.exe" (windows only)
        boolean primary,
        long pid) {         // owning process (windows only; used to capture just that app's audio)

    public SourceInfo(Kind kind, String id, String title, String subtitle, long handle, int left, int top, int width,
                      int height, String exeName, boolean primary) {
        this(kind, id, title, subtitle, handle, left, top, width, height, exeName, primary, 0);
    }

    public enum Kind { WINDOW, SCREEN, TEST }

    public static SourceInfo testPattern() {
        return new SourceInfo(Kind.TEST, "test", "Test pattern", "Animated 1280×720", 0, 0, 0, 1280, 720, "", false);
    }

    public SourceDescriptor descriptor() {
        return new SourceDescriptor(kind, title, exeName == null ? "" : exeName, kind == Kind.SCREEN ? subtitle + "|" + id : "");
    }
}
