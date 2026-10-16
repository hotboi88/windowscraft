package dev.windowscraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * The little control reminder in theater mode (the rest of the HUD is hidden): all controls for a
 * few seconds after you sit down, and "recenter" whenever you're looking away from the screen.
 */
public final class TheaterHint {
    private static double offAlpha;

    public static void render(DrawContext g, RenderTickCounter counter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!TheaterMode.active() || TheaterMode.leaving() || mc.currentScreen != null) {
            offAlpha = 0;
            return;
        }
        double t = TheaterMode.secondsActive();
        String jump = mc.options.jumpKey.getBoundKeyLocalizedText().getString();
        String text;
        double alpha;
        if (TheaterMode.controlled() != null || TheaterMode.controlledRemote() != null) {
            if (t >= 9) return;
            text = "Mouse and keys: use the screen     Ctrl+Alt + mouse: look around     Ctrl+Alt + scroll: zoom     Ctrl+Alt + Space: recenter     Esc: leave";
            alpha = t < 0.5 ? t / 0.5 : t > 8 ? 9 - t : 1;
        } else if (t < 7) {
            text = "W / S or scroll: closer · further     Mouse: look around     " + jump + " or middle-click: recenter     "
                    + TheaterMode.keyName() + " or Esc: leave";
            alpha = t < 0.5 ? t / 0.5 : t > 6 ? 7 - t : 1;
        } else {
            boolean away = TheaterMode.lookOffset() > 8;
            offAlpha = Math.max(0, Math.min(1, offAlpha + (away ? 0.06 : -0.06)));
            if (offAlpha <= 0) return;
            text = jump + " or middle-click: look at the screen again";
            alpha = offAlpha;
        }
        TextRenderer tr = mc.textRenderer;
        int w = tr.getWidth(text), x = (g.getScaledWindowWidth() - w) / 2, y = g.getScaledWindowHeight() - 28;
        int a = (int) (alpha * 255) & 0xFF;
        if (a < 8) return;
        g.fill(x - 8, y - 5, x + w + 8, y + 13, ((int) (alpha * 0x90) & 0xFF) << 24);
        g.drawText(tr, text, x, y, (a << 24) | 0xF2F3F5, false);
    }

    private TheaterHint() {}
}
