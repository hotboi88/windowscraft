package dev.windowscraft.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * A volume slider from 0 % to {@link #MAX} (500 %), with a tick at 100 %. The first part of the
 * track (0–100 %) is wider than the boost part, so normal volumes stay easy to set precisely.
 */
public final class VolumeSlider {
    public static final double MAX = 5.0;
    /** Fraction of the track used for 0–100 %. */
    private static final double NORMAL_SPAN = 0.45;
    private int x, y, w;
    private boolean dragging;

    /** Width of the speaker icon drawn left of the track (plus its gap). */
    public static final int ICON_W = 14;

    /**
     * Draws a speaker icon at {@code x}, then the slider; returns the x after the percentage label.
     * The icon shows sound waves by level, or a cross when muted / at 0 %.
     */
    public int render(DrawContext g, TextRenderer tr, int x, int y, int w, double value, boolean muted) {
        speaker(g, x, y - 3, muted || value <= 0 ? 0 : value < 0.5 ? 1 : value <= 1.001 ? 2 : 3);
        x += ICON_W;
        w -= ICON_W;
        this.x = x;
        this.y = y;
        this.w = w;
        UiKit.round(g, x, y, w, 4, 2, UiKit.SEG_ACTIVE); // visible on the panel and inside a pill
        int tick = x + (int) Math.round(w * NORMAL_SPAN);
        g.fill(tick, y - 2, tick + 1, y + 6, UiKit.TEXT_MUTED);
        int fill = (int) Math.round(w * toPos(value));
        UiKit.round(g, x, y, Math.max(4, fill), 4, 2, muted ? UiKit.DISABLED : value > 1.001 ? 0xFFE8A33D : UiKit.ACCENT);
        UiKit.round(g, x + fill - 4, y - 4, 8, 12, 3, UiKit.TEXT);
        String pct = muted ? "muted" : Math.round(value * 100) + "%";
        UiKit.text(g, tr, pct, x + w + 8, y - 2, value > 1.001 && !muted ? 0xFFE8A33D : UiKit.TEXT_2);
        return x + w + 8 + tr.getWidth("500%");
    }

    /** A tiny pixel speaker (9×9 at GUI scale): 0 = muted, 1–3 = how many sound waves. */
    static void speaker(DrawContext g, int x, int y, int waves) {
        int c = waves == 0 ? UiKit.TEXT_MUTED : UiKit.TEXT_2;
        g.fill(x, y + 3, x + 2, y + 7, c);           // magnet
        g.fill(x + 2, y + 2, x + 3, y + 8, c);       // cone
        g.fill(x + 3, y + 1, x + 4, y + 9, c);
        g.fill(x + 4, y, x + 5, y + 10, c);
        if (waves == 0) {                            // ×
            for (int i = 0; i < 4; i++) {
                g.fill(x + 6 + i, y + 3 + i, x + 7 + i, y + 4 + i, c);
                g.fill(x + 9 - i, y + 3 + i, x + 10 - i, y + 4 + i, c);
            }
            return;
        }
        int warm = waves == 3 ? 0xFFE8A33D : c;
        g.fill(x + 6, y + 4, x + 7, y + 6, c);       // small wave
        if (waves >= 2) { g.fill(x + 8, y + 2, x + 9, y + 3, c); g.fill(x + 9, y + 3, x + 10, y + 7, c); g.fill(x + 8, y + 7, x + 9, y + 8, c); }
        if (waves >= 3) { g.fill(x + 10, y, x + 11, y + 1, warm); g.fill(x + 11, y + 1, x + 12, y + 9, warm); g.fill(x + 10, y + 9, x + 11, y + 10, warm); }
    }

    /** Total width {@link #renderPill} uses for a track of {@code trackW}. */
    public static int pillWidth(TextRenderer tr, int trackW) {
        return 6 + ICON_W + trackW + 8 + tr.getWidth("500%") + 6;
    }

    /**
     * The slider inside a rounded inset "pill", 18 high — the same shape as the dialog's other
     * controls, so it sits in a row of them naturally.
     */
    public void renderPill(DrawContext g, TextRenderer tr, int x, int y, int trackW, double value, boolean muted) {
        int w = pillWidth(tr, trackW);
        UiKit.round(g, x, y, w, 18, 3, UiKit.INSET);
        render(g, tr, x + 6, y + 7, ICON_W + trackW, value, muted);
    }

    public boolean hit(double mx, double my) {
        return UiKit.inside(mx, my, x - 4, y - 6, w + 8, 16);
    }

    public void startDrag() { dragging = true; }
    public void stopDrag() { dragging = false; }
    public boolean dragging() { return dragging; }

    /** The value under the mouse (snaps to exactly 100 % near the tick). */
    public double valueAt(double mx) {
        double v = fromPos(MathHelper.clamp((mx - x) / w, 0, 1));
        return Math.abs(v - 1.0) < 0.04 ? 1.0 : v;
    }

    static double toPos(double v) {
        v = MathHelper.clamp(v, 0, MAX);
        return v <= 1 ? v * NORMAL_SPAN : NORMAL_SPAN + (v - 1) / (MAX - 1) * (1 - NORMAL_SPAN);
    }

    static double fromPos(double p) {
        return p <= NORMAL_SPAN ? p / NORMAL_SPAN : 1 + (p - NORMAL_SPAN) / (1 - NORMAL_SPAN) * (MAX - 1);
    }
}
