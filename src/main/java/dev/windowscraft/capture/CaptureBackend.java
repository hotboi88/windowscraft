package dev.windowscraft.capture;

import java.util.List;

/** Platform capture implementation (Windows GDI today; Windows Graphics Capture helper later). */
public interface CaptureBackend {
    /** False on platforms where real capture isn't implemented (only the test pattern works there). */
    boolean isSupported();

    String unsupportedReason();

    List<SourceInfo> listWindows();

    List<SourceInfo> listScreens();

    /** One-shot small preview, or null. Safe to call from any background thread. */
    Frame thumbnail(SourceInfo source, int maxWidth, int maxHeight);

    /** 32×32 app icon with real alpha, or null. */
    Frame icon(SourceInfo source);

    FrameProducer open(SourceInfo source);
}
