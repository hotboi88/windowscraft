package dev.windowscraft.ui;

import dev.windowscraft.display.Display;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.monitor.InGameMonitors;
import dev.windowscraft.monitor.MonitorBackend.Driver;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * First time (or when the driver isn't working): what an in-game monitor needs, and a one-click
 * install. Sharing a window or a screen of yours stays one click away.
 */
public final class MonitorSetupScreen extends Screen {
    private final String dimension;
    private final WallRect rect;
    private final Display existing;
    private Driver state;
    private volatile String progress;
    private volatile String error;
    private volatile boolean busy;
    private int px, py, pw, ph;
    private static final int PAD = 14;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public MonitorSetupScreen(String dimension, WallRect rect, Display existing, Driver state) {
        super(Text.literal("In-game monitor"));
        this.dimension = dimension;
        this.rect = rect;
        this.existing = existing;
        this.state = state;
    }

    @Override
    protected void init() {
        pw = Math.min(420, width - 20);
        ph = Math.min(height - 16, 210);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override public boolean shouldPause() { return false; }

    private List<String> body() {
        if (busy) return List.of(progress == null ? "Working…" : progress);
        List<String> l = new ArrayList<>();
        if (error != null) l.add("§e" + error);
        switch (state) {
            case MISSING -> {
                l.add("An in-game monitor is a monitor that exists only inside Windows:");
                l.add("you see it on this wall, and everyone you share with sees it too.");
                l.add("Sit down at it (" + dev.windowscraft.client.TheaterMode.keyName() + ") and your real mouse and keyboard");
                l.add("work any app or game on it.");
                l.add("");
                l.add("It needs a free display driver (Parsec Virtual Display Driver,");
                l.add("signed). Windows will ask for permission once.");
            }
            case RESTART -> {
                l.add("The display driver is installed, but Windows needs a");
                l.add("restart before it can be used. Restart your PC, then press");
                l.add(dev.windowscraft.WindowsCraftClient.keyName() + " on the wall again.");
            }
            case DISABLED -> {
                l.add("The display driver is turned off in Windows.");
                l.add("Turn on \"Parsec Virtual Display Adapter\" in Device Manager");
                l.add("(Display adapters), then press " + dev.windowscraft.WindowsCraftClient.keyName() + " again.");
            }
            default -> {
                l.add("The display driver isn't working right now.");
                l.add("Installing it again usually fixes that.");
            }
        }
        return l;
    }

    @Override
    public void render(DrawContext g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        hits.clear();
        UiKit.round(g, px - 1, py + 1, pw + 2, ph + 2, 6, 0x60000000);
        UiKit.round(g, px, py, pw, ph, 6, UiKit.PANEL);
        UiKit.textScaled(g, textRenderer, "In-game monitor", px + PAD, py + 12, 1.35f, UiKit.TEXT);
        int y = py + 34;
        for (String line : body()) {
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, line, pw - PAD * 2), px + PAD, y, line.startsWith("§e") ? 0xFFF0B232 : UiKit.TEXT_2);
            y += 11;
        }
        if (busy) return;
        int by = py + ph - 28, bh = 20, x = px + pw - PAD;
        String primary = state == Driver.RESTART || state == Driver.DISABLED ? null : "Install driver";
        if (primary != null) {
            int w = textRenderer.getWidth(primary) + 22;
            x -= w;
            boolean hov = UiKit.inside(mx, my, x, by, w, bh);
            UiKit.round(g, x, by, w, bh, 3, hov ? UiKit.ACCENT_HOVER : UiKit.ACCENT);
            UiKit.text(g, textRenderer, primary, x + 11, by + 6, UiKit.TEXT);
            hits.add(new Hit(x, by, w, bh, this::install));
            x -= 6;
        }
        if (existing == null || !existing.tablet) { // (a tablet only ever is an in-game monitor)
            String share = "Share a window instead";
            int sw = textRenderer.getWidth(share) + 18;
            x -= sw;
            boolean shov = UiKit.inside(mx, my, x, by, sw, bh);
            UiKit.round(g, x, by, sw, bh, 3, shov ? UiKit.SURFACE_HOVER : UiKit.SURFACE);
            UiKit.text(g, textRenderer, share, x + 9, by + 6, UiKit.TEXT_2);
            hits.add(new Hit(x, by, sw, bh, () -> MinecraftClient.getInstance().setScreen(existing != null
                    ? SharePickerScreen.forDisplay(existing) : SharePickerScreen.forNewWall(dimension, rect))));
        }
        String cancel = "Cancel";
        int cw = textRenderer.getWidth(cancel) + 18, cx = px + PAD;
        boolean chov = UiKit.inside(mx, my, cx, by, cw, bh);
        if (chov) UiKit.round(g, cx, by, cw, bh, 3, UiKit.SURFACE_HOVER);
        UiKit.text(g, textRenderer, cancel, cx + 9, by + 6, chov ? UiKit.TEXT : UiKit.TEXT_2);
        hits.add(new Hit(cx, by, cw, bh, this::close));
    }

    private void install() {
        busy = true;
        error = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        InGameMonitors.installDriver(p -> progress = p).whenComplete((st, err) -> mc.execute(() -> {
            busy = false;
            if (err != null) {
                Throwable why = err.getCause() != null ? err.getCause() : err;
                error = "Couldn't install: " + why.getMessage();
                return;
            }
            state = st;
            if (st == Driver.OK) {
                close();
                InGameMonitors.turnOn(mc, dimension, rect, existing);
            } else if (st != Driver.RESTART) {
                error = "The driver was installed but isn't running yet. Try again, or restart your PC.";
            }
        }));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !busy) {
            for (Hit h : hits) {
                if (UiKit.inside(mx, my, h.x, h.y, h.w, h.h)) {
                    MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f));
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean shouldCloseOnEsc() { return !busy; }
}
