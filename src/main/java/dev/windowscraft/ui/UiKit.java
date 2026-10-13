package dev.windowscraft.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Small drawing helpers and the colour palette for the share dialog. */
final class UiKit {
    // palette (ARGB) — dark, Discord-like, but our own accent
    static final int OVERLAY = 0xB0000000;
    static final int PANEL = 0xFF313338;
    static final int PANEL_EDGE = 0xFF26272B;
    static final int SURFACE = 0xFF2B2D31;
    static final int SURFACE_HOVER = 0xFF35373C;
    static final int INSET = 0xFF1E1F22;
    static final int THUMB_BG = 0xFF111214;
    static final int SEG_ACTIVE = 0xFF4E5058;
    static final int TEXT = 0xFFF2F3F5;
    static final int TEXT_2 = 0xFFB5BAC1;
    static final int TEXT_MUTED = 0xFF80848E;
    static final int ACCENT = 0xFF6E74F7;
    static final int ACCENT_HOVER = 0xFF5B61E8;
    static final int DANGER = 0xFFDA373C;
    static final int DANGER_HOVER = 0xFFA12D2F;
    static final int LIVE = 0xFFED4245;
    static final int DISABLED = 0xFF3F4147;
    static final int DIVIDER = 0xFF3F4147;

    /** Filled rectangle with corners rounded by {@code r} GUI pixels (r ≤ 4 looks right at any GUI scale). */
    static void round(DrawContext g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** Rounded outline of thickness t. */
    static void roundOutline(DrawContext g, int x, int y, int w, int h, int r, int t, int color) {
        g.fill(x + r, y, x + w - r, y + t, color);
        g.fill(x + r, y + h - t, x + w - r, y + h, color);
        g.fill(x, y + r, x + t, y + h - r, color);
        g.fill(x + w - t, y + r, x + w, y + h - r, color);
        // corners: approximate with short diagonals
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
            int span = Math.max(t, (i == 0 ? r - inset : 1));
            g.fill(x + inset, y + i, x + inset + span, y + i + 1, color);
            g.fill(x + w - inset - span, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + inset + span, y + h - i, color);
            g.fill(x + w - inset - span, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    static void text(DrawContext g, TextRenderer tr, String s, int x, int y, int color) {
        g.drawText(tr, s, x, y, color, false);
    }

    static void textScaled(DrawContext g, TextRenderer tr, String s, float x, float y, float scale, int color) {
        g.getMatrices().push();
        g.getMatrices().translate(x, y, 0);
        g.getMatrices().scale(scale, scale, 1);
        g.drawText(tr, s, 0, 0, color, false);
        g.getMatrices().pop();
    }

    static String ellipsize(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        String dots = "…";
        return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth(dots))) + dots;
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private UiKit() {}
}
