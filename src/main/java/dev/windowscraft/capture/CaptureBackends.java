package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.win.WinGdiBackend;
import java.util.List;
import java.util.Locale;

public final class CaptureBackends {
    private static CaptureBackend instance;

    public static synchronized CaptureBackend get() {
        if (instance == null) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            CaptureBackend chosen = null;
            if (os.contains("win")) {
                try {
                    chosen = new WinGdiBackend();
                } catch (Throwable t) {
                    WindowsCraftClient.LOGGER.error("Windows capture backend failed to initialise", t);
                }
            }
            instance = chosen != null ? chosen : new Unsupported(os.contains("win")
                    ? "Windows capture failed to start — check the log."
                    : "Screen and window capture currently works on Windows only.");
        }
        return instance;
    }

    /** Development/testing only: swap in a different backend (e.g. a fake one on Linux CI). */
    public static synchronized void overrideForTesting(CaptureBackend backend) {
        instance = backend;
    }

    /** Opens any source, including the cross-platform test pattern. */
    public static FrameProducer open(SourceInfo source) {
        if (source.kind() == SourceInfo.Kind.TEST) return new TestPatternProducer();
        // Windows: Graphics Capture through the helper (only the app's content, a picture only
        // when it changed); the GDI capture if that can't run here
        if (instance == null || instance instanceof dev.windowscraft.capture.win.WinGdiBackend) {
            if (WgcProducer.supported(source)) return new FallbackProducer(source, false, 60);
        }
        return get().open(source);
    }

    public static Frame thumbnail(SourceInfo source, int w, int h) {
        if (source.kind() == SourceInfo.Kind.TEST) return TestPatternProducer.still(w, h);
        return get().thumbnail(source, w, h);
    }

    private record Unsupported(String reason) implements CaptureBackend {
        public boolean isSupported() { return false; }
        public String unsupportedReason() { return reason; }
        public List<SourceInfo> listWindows() { return List.of(); }
        public List<SourceInfo> listScreens() { return List.of(); }
        public Frame thumbnail(SourceInfo s, int w, int h) { return null; }
        public Frame icon(SourceInfo s) { return null; }
        public FrameProducer open(SourceInfo s) { throw new UnsupportedOperationException(reason); }
    }

    private CaptureBackends() {}
}
