package dev.windowscraft.ui;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.client.TheaterMode;
import dev.windowscraft.config.ClientSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * U on someone else's screen: how you want to hear it (3D from the wall, or direct for the
 * crispest sound), its volume, mute, and theater mode.
 */
public final class RemoteOptionsScreen extends Screen {
    private final RemoteDisplays.Remote remote;
    private int px, py, pw = 320, ph = 270, pad = 14;
    private final VolumeSlider slider = new VolumeSlider();
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final net.minecraft.client.gui.screen.Screen parent;

    public RemoteOptionsScreen(RemoteDisplays.Remote remote) { this(remote, null); }

    /** Opened from the hub: closing goes back to it. */
    public RemoteOptionsScreen(RemoteDisplays.Remote remote, net.minecraft.client.gui.screen.Screen parent) {
        super(Text.literal("Screen options"));
        this.remote = remote;
        this.parent = parent;
    }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }

    @Override
    protected void init() {
        pw = Math.min(320, width - 20);
        px = (width - pw) / 2;
        py = Math.max(4, (height - ph) / 2);
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        hits.clear();
        if (!RemoteDisplays.all().contains(remote)) { close(); return; }
        RemoteDisplays.Prefs prefs = remote.prefs();
        UiKit.round(g, px - 1, py + 1, pw + 2, ph + 2, 6, 0x60000000);
        UiKit.round(g, px, py, pw, ph, 6, UiKit.PANEL);

        UiKit.textScaled(g, textRenderer, remote.ownerName + "'s screen", px + pad, py + 12, 1.25f, UiKit.TEXT);
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, remote.info.title(), pw - pad * 2 - 20), px + pad, py + 28, UiKit.TEXT_MUTED);
        int cx = px + pw - pad - 12, cy = py + 10;
        boolean chov = UiKit.inside(mx, my, cx - 2, cy - 2, 16, 16);
        if (chov) UiKit.round(g, cx - 2, cy - 2, 16, 16, 3, UiKit.SURFACE_HOVER);
        UiKit.text(g, textRenderer, "✕", cx + 2, cy + 2, chov ? UiKit.TEXT : UiKit.TEXT_2);
        hits.add(new Hit(cx - 2, cy - 2, 16, 16, this::close));

        // SOUND: 3D / Direct
        int y = py + 46;
        UiKit.textScaled(g, textRenderer, "SOUND", px + pad, y, 0.75f, UiKit.TEXT_MUTED);
        String[] opts = {"3D, from the wall", "Direct"};
        int segW = Math.max(textRenderer.getWidth(opts[0]), textRenderer.getWidth(opts[1])) + 16;
        int sx0 = px + pad;
        UiKit.round(g, sx0, y + 10, segW * 2 + 4, 18, 3, UiKit.INSET);
        for (int i = 0; i < 2; i++) {
            int sx = sx0 + 2 + i * segW;
            boolean sel = (i == 1) == prefs.direct, hov = UiKit.inside(mx, my, sx, y + 12, segW, 14);
            if (sel) UiKit.round(g, sx, y + 12, segW, 14, 2, UiKit.SEG_ACTIVE);
            else if (hov) UiKit.round(g, sx, y + 12, segW, 14, 2, UiKit.SURFACE_HOVER);
            UiKit.text(g, textRenderer, opts[i], sx + segW / 2 - textRenderer.getWidth(opts[i]) / 2, y + 15, sel ? UiKit.TEXT : UiKit.TEXT_2);
            final boolean direct = i == 1;
            hits.add(new Hit(sx, y + 12, segW, 14, () -> {
                prefs.direct = direct;
                ClientSettings.setRemoteDirect(direct); // your choice becomes the default for other screens too
            }));
        }
        String explain = prefs.direct
                ? "Straight to your headphones at full quality — no distance, direction or room effects."
                : "Sounds like it's in the world: louder up close, echoes in caves, muffled through walls.";
        List<net.minecraft.text.OrderedText> lines = textRenderer.wrapLines(Text.literal(explain), pw - pad * 2);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawText(textRenderer, lines.get(i), px + pad, y + 33 + i * 10, UiKit.TEXT_MUTED, false);

        // VOLUME slider + mute
        y = py + 104;
        UiKit.textScaled(g, textRenderer, "VOLUME", px + pad, y, 0.75f, UiKit.TEXT_MUTED);
        String muteLabel = prefs.muted ? "Unmute" : "Mute";
        int mw = textRenderer.getWidth("Unmute") + 16, mxb = px + pw - pad - mw;
        int sliderW = mxb - 8 - textRenderer.getWidth("500%") - 8 - (px + pad);
        if (slider.dragging()) setVolume(slider.valueAt(mx));
        slider.render(g, textRenderer, px + pad, y + 16, sliderW, prefs.volume, prefs.muted);
        hits.add(new Hit(px + pad + VolumeSlider.ICON_W - 4, y + 10, sliderW - VolumeSlider.ICON_W + 8, 16, slider::startDrag));
        boolean mhov = UiKit.inside(mx, my, mxb, y + 9, mw, 18);
        UiKit.round(g, mxb, y + 9, mw, 18, 3, mhov ? UiKit.SURFACE_HOVER : UiKit.SURFACE);
        UiKit.text(g, textRenderer, muteLabel, mxb + mw / 2 - textRenderer.getWidth(muteLabel) / 2, y + 14, UiKit.TEXT);
        hits.add(new Hit(mxb, y + 9, mw, 18, () -> prefs.muted = !prefs.muted));

        // TRUE COLOURS: draw screens untouched by shader packs
        y = py + 140;
        UiKit.textScaled(g, textRenderer, "SHADERS ON SCREENS", px + pad, y, 0.75f, UiKit.TEXT_MUTED);
        trueColoursToggle(g, mx, my, px + pad, y + 10);

        // PICTURE AND SOUND: lip sync (Automatic / quickest / with its sound)
        y = py + 178;
        UiKit.textScaled(g, textRenderer, "PICTURE AND SOUND", px + pad, y, 0.75f, UiKit.TEXT_MUTED);
        String[] lip = {"Automatic", "Quickest", "With its sound"};
        int lx = px + pad;
        int[] lw = new int[3];
        int total = 4;
        for (int i = 0; i < 3; i++) total += lw[i] = textRenderer.getWidth(lip[i]) + 16;
        UiKit.round(g, lx, y + 10, total, 18, 3, UiKit.INSET);
        int sx2 = lx + 2;
        for (int i = 0; i < 3; i++) {
            boolean sel = prefs.lipSync == i, hov = UiKit.inside(mx, my, sx2, y + 12, lw[i], 14);
            if (sel) UiKit.round(g, sx2, y + 12, lw[i], 14, 2, UiKit.SEG_ACTIVE);
            else if (hov) UiKit.round(g, sx2, y + 12, lw[i], 14, 2, UiKit.SURFACE_HOVER);
            UiKit.text(g, textRenderer, lip[i], sx2 + lw[i] / 2 - textRenderer.getWidth(lip[i]) / 2, y + 15, sel ? UiKit.TEXT : UiKit.TEXT_2);
            final int mode = i;
            hits.add(new Hit(sx2, y + 12, lw[i], 14, () -> {
                prefs.lipSync = mode;
                var sub = remote.subscriber();
                if (sub != null) sub.setLipSync(mode);
            }));
            sx2 += lw[i];
        }
        var sub = remote.subscriber();
        double hold = sub == null ? 0 : sub.lipHoldMs();
        String now = hold > 20 ? String.format(java.util.Locale.ROOT, " Now: the picture waits %.2f s for its sound.", hold / 1000) : " Now: the quickest picture.";
        String lipExplain = switch (prefs.lipSync) {
            case 1 -> "Every picture as soon as it's in; its sound comes about a third of a second later.";
            case 2 -> "The picture always waits for its sound, even while you use the screen." + now;
            default -> "In step with its sound while you watch; the quickest picture while you use it." + now;
        };
        List<net.minecraft.text.OrderedText> ll = textRenderer.wrapLines(Text.literal(lipExplain), pw - pad * 2);
        for (int i = 0; i < Math.min(2, ll.size()); i++) g.drawText(textRenderer, ll.get(i), px + pad, y + 33 + i * 10, UiKit.TEXT_MUTED, false);

        // footer: theater + done
        int by = py + ph - 26, bh = 20;
        String done = "Done";
        int dw = Math.max(56, textRenderer.getWidth(done) + 22), dx = px + pw - pad - dw;
        boolean dhov = UiKit.inside(mx, my, dx, by, dw, bh);
        UiKit.round(g, dx, by, dw, bh, 3, dhov ? UiKit.ACCENT_HOVER : UiKit.ACCENT);
        UiKit.text(g, textRenderer, done, dx + dw / 2 - textRenderer.getWidth(done) / 2, by + 6, UiKit.TEXT);
        hits.add(new Hit(dx, by, dw, bh, this::close));
        String theater = "Theater mode (" + TheaterMode.keyName() + ")";
        int tw = textRenderer.getWidth(theater) + 18, tx = dx - 6 - tw;
        boolean thov = UiKit.inside(mx, my, tx, by, tw, bh);
        UiKit.round(g, tx, by, tw, bh, 3, thov ? UiKit.SURFACE_HOVER : UiKit.SURFACE);
        UiKit.text(g, textRenderer, theater, tx + 9, by + 6, UiKit.TEXT);
        hits.add(new Hit(tx, by, tw, bh, () -> {
            close();
            TheaterMode.watch(MinecraftClient.getInstance(), remote);
        }));
    }

    /** "Off (true colours) | On" toggle; shared with the share dialog's setting. */
    private void trueColoursToggle(DrawContext g, int mx, int my, int x, int y) {
        String[] opts = {"Off — true colours", "On"};
        boolean off = dev.windowscraft.config.ClientSettings.screensWithoutShaders();
        int segW = textRenderer.getWidth(opts[0]) + 16, w2 = textRenderer.getWidth(opts[1]) + 24;
        UiKit.round(g, x, y, segW + w2 + 4, 18, 3, UiKit.INSET);
        for (int i = 0; i < 2; i++) {
            int sx = i == 0 ? x + 2 : x + 2 + segW, sw = i == 0 ? segW : w2;
            boolean sel = (i == 0) == off, hov = UiKit.inside(mx, my, sx, y + 2, sw, 14);
            if (sel) UiKit.round(g, sx, y + 2, sw, 14, 2, UiKit.SEG_ACTIVE);
            else if (hov) UiKit.round(g, sx, y + 2, sw, 14, 2, UiKit.SURFACE_HOVER);
            UiKit.text(g, textRenderer, opts[i], sx + sw / 2 - textRenderer.getWidth(opts[i]) / 2, y + 5, sel ? UiKit.TEXT : UiKit.TEXT_2);
            final boolean wantOff = i == 0;
            hits.add(new Hit(sx, y + 2, sw, 14, () -> dev.windowscraft.config.ClientSettings.setScreensWithoutShaders(wantOff)));
        }
        String note = dev.windowscraft.render.ShaderCompat.shaderPackInUse() ? "applies to every screen" : "only matters with a shader pack";
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, note, pw - pad * 2 - segW - w2 - 14), x + segW + w2 + 12, y + 5, UiKit.TEXT_MUTED);
    }

    private void setVolume(double v) {
        RemoteDisplays.Prefs p = remote.prefs();
        p.volume = MathHelper.clamp(v, 0, VolumeSlider.MAX);
        if (p.volume > 0) p.muted = false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (UiKit.inside(mx, my, h.x, h.y, h.w, h.h)) {
                    h.action.run();
                    if (slider.dragging()) setVolume(slider.valueAt(mx));
                    else MinecraftClient.getInstance().getSoundManager()
                            .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f));
                    return true;
                }
            }
            if (!UiKit.inside(mx, my, px, py, pw, ph)) { close(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        slider.stopDrag();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        RemoteDisplays.Prefs p = remote.prefs();
        p.volume = MathHelper.clamp(p.volume + v * (p.volume >= 1 ? 0.25 : 0.05), 0, VolumeSlider.MAX);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (WindowsCraftClient.SHARE_KEY.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ENTER) {
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }
        if (WindowsCraftClient.THEATER_KEY.matchesKey(keyCode, scanCode)) {
            MinecraftClient.getInstance().setScreen(null);
            TheaterMode.watch(MinecraftClient.getInstance(), remote);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
