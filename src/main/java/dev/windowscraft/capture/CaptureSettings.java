package dev.windowscraft.capture;

/**
 * Quality options picked in the share dialog. {@code maxHeight <= 0} means native resolution.
 * {@code fit} is how the picture sits on the wall: {@link #FIT_AUTO}, {@link #FIT_FILL} or
 * {@link #FIT_BARS}. {@code hearInGame}: for an app window, the person sharing hears its sound
 * from the wall in the game (the app itself goes quiet) instead of straight from the app.
 */
public record CaptureSettings(int maxHeight, int fps, boolean showCursor, boolean audio, int fit, boolean hearInGame) {
    /** Fill the wall when the shapes nearly match (no hairline bars), letterbox when they really differ. */
    public static final int FIT_AUTO = 0;
    /** Always stretch over the whole wall. */
    public static final int FIT_FILL = 1;
    /** Always keep the picture's shape, with black bars. */
    public static final int FIT_BARS = 2;
    /** Within this much aspect difference, Auto stretches (≤10% is not noticeable on a wall). */
    public static final double AUTO_TOLERANCE = 0.10;

    public CaptureSettings(int maxHeight, int fps, boolean showCursor) {
        this(maxHeight, fps, showCursor, true, FIT_AUTO, true);
    }

    public CaptureSettings(int maxHeight, int fps, boolean showCursor, boolean audio) {
        this(maxHeight, fps, showCursor, audio, FIT_AUTO, true);
    }

    /** Should a picture of contentW×contentH cover a wall of wallW×wallH completely? */
    public static boolean shouldFill(int fit, int contentW, int contentH, double wallW, double wallH) {
        if (fit == FIT_FILL) return true;
        if (fit == FIT_BARS || contentW <= 0 || contentH <= 0) return false;
        double ratio = (contentW / (double) contentH) / (wallW / wallH);
        return Math.abs(ratio - 1) <= AUTO_TOLERANCE;
    }

    /** Settings that need the encoder rebuilt when they change (fill and cursor don't). */
    public boolean sameStream(CaptureSettings o) {
        return o != null && maxHeight == o.maxHeight && fps == o.fps && audio == o.audio;
    }

    public static final int[] HEIGHTS = {720, 1080, 0};
    public static final int[] FPS = {15, 30, 60};

    public static String heightLabel(int h) {
        return h <= 0 ? "Source" : h + "p";
    }

    /** Output size for a source of the given size: never upscales, keeps aspect ratio, even dimensions. */
    public int[] outputSize(int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0) return new int[]{0, 0};
        if (maxHeight <= 0 || srcH <= maxHeight) return new int[]{srcW, srcH};
        int h = maxHeight;
        int w = (int) Math.round(srcW * (h / (double) srcH));
        return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
    }
}
