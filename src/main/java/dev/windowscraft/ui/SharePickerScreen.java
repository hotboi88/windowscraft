package dev.windowscraft.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.stream.Ffmpeg;
import dev.windowscraft.stream.StreamPublisher;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The share dialog: pick an application window or a whole screen, choose quality, Go Live.
 * Drawn entirely by hand (no vanilla widgets) for a modern look.
 */
public final class SharePickerScreen extends Screen {
    private static final int TAB_APPS = 0, TAB_SCREENS = 1, TAB_SETTINGS = 2;

    private final Display existing;
    private final WallRect newRect;
    private final String dimension;
    private final SourceLoader loader = new SourceLoader();

    private int tab = TAB_APPS;
    private String selectedId;
    private boolean preselected;
    private int heightIdx, fpsIdx;
    private boolean audio, hearInGame;
    private int fit;
    private String hoverTip;
    private final VolumeSlider ownSlider = new VolumeSlider();
    private double scroll;
    private long lastClickTime;
    private String lastClickId;

    // layout
    private int px, py, pw, ph, pad = 14;
    private int tabsY, gridX, gridY, gridW, gridH, cols, cardW, cardH, thumbH, gap = 8;
    private int footerY;
    private boolean compact;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public static SharePickerScreen forNewWall(String dimension, WallRect rect) {
        return new SharePickerScreen(null, rect, dimension);
    }

    public static SharePickerScreen forDisplay(Display d) {
        return new SharePickerScreen(d, d.rect, d.dimension);
    }

    private SharePickerScreen(Display existing, WallRect rect, String dimension) {
        super(Text.literal("Share your screen"));
        this.existing = existing;
        this.newRect = existing == null ? rect : null;
        this.dimension = dimension;
        CaptureSettings s = existing != null && existing.lastSettings != null ? existing.lastSettings : ClientSettings.get();
        heightIdx = indexOf(CaptureSettings.HEIGHTS, s.maxHeight(), 1);
        fpsIdx = indexOf(CaptureSettings.FPS, s.fps(), 1);
        audio = s.audio();
        hearInGame = s.hearInGame();
        fit = s.fit();
        if (!loader.backend.isSupported()) tab = TAB_SCREENS;
        if (existing != null && existing.isLive()) {
            selectedId = existing.session().source().id();
            tab = existing.session().source().kind() == SourceInfo.Kind.WINDOW ? TAB_APPS : TAB_SCREENS;
            preselected = true;
        } else if (existing != null && existing.lastSource != null) {
            tab = existing.lastSource.kind() == SourceInfo.Kind.WINDOW ? TAB_APPS : TAB_SCREENS;
        }
        loader.setPreferScreens(tab == TAB_SCREENS);
    }

    private static int indexOf(int[] arr, int v, int fallback) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return fallback;
    }

    private boolean visibleToOthers() {
        if (existing != null) {
            ClientNet.Ack ack = ClientNet.ack(existing.id);
            if (ack != null) return ack.relayOpen();
        }
        return ClientNet.streamingAvailable();
    }

    private WallRect rect() { return existing != null ? existing.rect : newRect; }
    private boolean isLive() { return existing != null && existing.isLive(); }

    @Override
    protected void init() { layout(); }

    /** Sizes and places everything; Settings has no quality controls at the bottom, so it gets that room too. */
    private void layout() {
        pw = Math.min(600, width - 20);
        ph = Math.min(400, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        pad = pw < 420 ? 10 : 14;
        compact = ph < 280;
        tabsY = py + (isLive() ? 58 : 46);
        int footerH = compact ? 46 : 66;
        footerY = py + ph - footerH;
        gridX = px + pad;
        gridY = tabsY + 22;
        gridW = pw - pad * 2;
        gridH = footerY - 6 - gridY;
        cols = gridW >= 400 ? 3 : 2;
        cardW = (gridW - 6 - gap * (cols - 1)) / cols;
        thumbH = (cardW - 8) * 9 / 16;
        cardH = thumbH + 8 + 16;
    }

    @Override
    public boolean shouldPause() { return false; }

    // ---- rendering -------------------------------------------------------------------------

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta); // background blur/dim (no vanilla widgets added)
        layout();
        loader.syncTextures(false);
        maybePreselect();
        hits.clear();

        // drop shadow + panel
        UiKit.round(g, px - 1, py + 1, pw + 2, ph + 2, 6, 0x60000000);
        UiKit.round(g, px, py, pw, ph, 6, UiKit.PANEL);

        renderHeader(g, mouseX, mouseY);
        renderTabs(g, mouseX, mouseY);
        renderGrid(g, mouseX, mouseY);
        renderFooter(g, mouseX, mouseY);
    }

    private void renderHeader(DrawContext g, int mx, int my) {
        UiKit.textScaled(g, textRenderer, "Share your screen", px + pad, py + 12, 1.35f, UiKit.TEXT);
        WallRect r = rect();
        String sub = (r.isMonitor() ? "Gaming monitor" : "Display · " + r.widthBlocks() + " × " + r.heightBlocks() + " blocks") + "  ·  "
                + (visibleToOthers() ? "everyone here with WindowsCraft can watch"
                : ClientNet.serverSupported() ? "only you can see it (singleplayer)" : "only you can see it (server doesn't have WindowsCraft)");
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, sub, pw - pad * 2 - 150), px + pad, py + 30, UiKit.TEXT_MUTED);

        if (isLive()) {
            CaptureSession s = existing.session();
            boolean paused = s.state() == CaptureSession.State.PAUSED;
            int bx = px + pad, by = py + 42;
            String badge = paused ? "PAUSED" : "LIVE";
            int bw = textRenderer.getWidth(badge) + 10;
            UiKit.round(g, bx, by, bw, 11, 2, paused ? UiKit.DISABLED : UiKit.LIVE);
            UiKit.text(g, textRenderer, badge, bx + 5, by + 2, UiKit.TEXT);
            String stats;
            if (paused) {
                stats = "You're too far from this display — capture is paused to save resources";
            } else if (existing.share() != null && existing.share().gpuFeeding()) {
                stats = s.source().title() + "  —  direct GPU sharing (no CPU copies)";
            } else if (existing.publisher() != null && existing.publisher().gpuActive()) {
                stats = s.source().title() + "  —  captured on the graphics card (light on your CPU)";
            } else if (s.outputWidth() > 0) {
                stats = String.format(Locale.ROOT, "%s  —  %d×%d · %.0f fps · %.1f ms/frame",
                        s.source().title(), s.outputWidth(), s.outputHeight(), s.measuredFps(), s.avgCaptureMs());
            } else {
                stats = s.source().title() + (s.message() != null ? "  —  " + s.message() : "");
            }
            int statsW = pw - pad * 2 - bw - 8;
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, stats, statsW), bx + bw + 6, by + 2, UiKit.TEXT_2);
        }

        // close ×
        int cx = px + pw - pad - 12, cy = py + 10;
        boolean hov = UiKit.inside(mx, my, cx - 2, cy - 2, 16, 16);
        if (hov) UiKit.round(g, cx - 2, cy - 2, 16, 16, 3, UiKit.SURFACE_HOVER);
        UiKit.text(g, textRenderer, "✕", cx + 2, cy + 2, hov ? UiKit.TEXT : UiKit.TEXT_2);
        hits.add(new Hit(cx - 2, cy - 2, 16, 16, this::close));

        if (existing != null) {
            int x = cx - 8;
            if (isLive()) x = headerButton(g, mx, my, x, cy - 1, "Stop sharing", UiKit.DANGER, UiKit.DANGER_HOVER, UiKit.TEXT, this::stopSharing);
            headerButton(g, mx, my, x, cy - 1, "Remove display", 0, UiKit.SURFACE_HOVER, UiKit.TEXT_2, this::removeDisplay);
        }
    }

    /** Draws a small button ending at {@code right}; returns the x where the next one should end. */
    private int headerButton(DrawContext g, int mx, int my, int right, int y, String label, int bg, int hoverBg, int fg, Runnable action) {
        int w = textRenderer.getWidth(label) + 14, h = 16, x = right - w;
        boolean hov = UiKit.inside(mx, my, x, y, w, h);
        int color = hov ? hoverBg : bg;
        if ((color >>> 24) != 0) UiKit.round(g, x, y, w, h, 3, color);
        UiKit.text(g, textRenderer, label, x + 7, y + 4, hov && bg == 0 ? UiKit.TEXT : fg);
        hits.add(new Hit(x, y, w, h, action));
        return x - 6;
    }

    private void renderTabs(DrawContext g, int mx, int my) {
        String[] names = {"Applications", "Screens", "Settings ↗"};
        int[] counts = {loader.windows.size(), loader.screens.size(), -1};
        int x = px + pad;
        for (int i = 0; i < 3; i++) {
            String label = names[i] + (loader.loadedOnce && counts[i] >= 0 ? "  " + counts[i] : "");
            int w = textRenderer.getWidth(label);
            boolean active = tab == i, hov = UiKit.inside(mx, my, x - 4, tabsY - 4, w + 8, 18);
            UiKit.text(g, textRenderer, label, x, tabsY, active ? UiKit.TEXT : hov ? UiKit.TEXT_2 : UiKit.TEXT_MUTED);
            if (active) g.fill(x, tabsY + 12, x + w, tabsY + 14, UiKit.ACCENT);
            final int t = i;
            hits.add(new Hit(x - 4, tabsY - 4, w + 8, 18, () -> switchTab(t)));
            x += w + 20;
        }
        g.fill(px + pad, tabsY + 14, px + pw - pad, tabsY + 15, UiKit.DIVIDER);
    }

    private List<SourceLoader.Entry> currentEntries() {
        return tab == TAB_APPS ? loader.windows : tab == TAB_SCREENS ? loader.screens : List.of();
    }

    private void renderGrid(DrawContext g, int mx, int my) {
        List<SourceLoader.Entry> entries = currentEntries();
        if (entries.isEmpty()) {
            String head, detail;
            if (!loader.loadedOnce) {
                head = "Looking for things to share…";
                detail = "";
            } else if (tab == TAB_APPS && !loader.backend.isSupported()) {
                head = "Window capture needs Windows";
                detail = loader.backend.unsupportedReason() + "  Try the test pattern under Screens.";
            } else {
                head = tab == TAB_APPS ? "No windows to share" : "No screens found";
                detail = "Open an app and it will appear here.";
            }
            int cy = gridY + gridH / 2 - 10;
            g.drawCenteredTextWithShadow(textRenderer, Text.literal(head), px + pw / 2, cy, UiKit.TEXT_2);
            if (!detail.isEmpty()) {
                String d = UiKit.ellipsize(textRenderer, detail, gridW);
                UiKit.text(g, textRenderer, d, px + pw / 2 - textRenderer.getWidth(d) / 2, cy + 14, UiKit.TEXT_MUTED);
            }
            return;
        }

        int rows = (entries.size() + cols - 1) / cols;
        int contentH = rows * (cardH + gap) - gap;
        double maxScroll = Math.max(0, contentH - gridH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        List<String> live = DisplayManager.get().liveSourceIds();

        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        boolean mouseInGrid = UiKit.inside(mx, my, gridX, gridY, gridW, gridH);
        for (int i = 0; i < entries.size(); i++) {
            SourceLoader.Entry e = entries.get(i);
            int col = i % cols, row = i / cols;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap) - (int) scroll;
            if (y + cardH < gridY || y > gridY + gridH) continue;
            boolean hov = mouseInGrid && UiKit.inside(mx, my, x, y, cardW, cardH);
            boolean sel = e.info.id().equals(selectedId);
            drawCard(g, e, x, y, hov, sel, live.contains(e.info.id()));
            if (y < gridY + gridH && y + cardH > gridY) {
                int top = Math.max(y, gridY), bottom = Math.min(y + cardH, gridY + gridH);
                hits.add(new Hit(x, top, cardW, bottom - top, () -> clickCard(e)));
            }
        }
        g.disableScissor();

        if (maxScroll > 0) { // slim scrollbar
            int trackX = gridX + gridW - 3;
            int thumbLen = Math.max(20, (int) (gridH * (gridH / (double) contentH)));
            int thumbY = gridY + (int) ((gridH - thumbLen) * (scroll / maxScroll));
            UiKit.round(g, trackX, gridY, 3, gridH, 1, UiKit.INSET);
            UiKit.round(g, trackX, thumbY, 3, thumbLen, 1, UiKit.TEXT_MUTED);
        }
    }

    private void drawCard(DrawContext g, SourceLoader.Entry e, int x, int y, boolean hov, boolean sel, boolean live) {
        UiKit.round(g, x, y, cardW, cardH, 4, sel || hov ? UiKit.SURFACE_HOVER : UiKit.SURFACE);
        int tx = x + 4, ty = y + 4, tw = cardW - 8;
        g.fill(tx, ty, tx + tw, ty + thumbH, UiKit.THUMB_BG);
        Identifier thumb = e.thumbId();
        if (thumb != null) {
            int iw = e.thumbW(), ih = e.thumbH();
            double s = Math.min(tw / (double) iw, thumbH / (double) ih);
            int dw = Math.max(1, (int) Math.round(iw * s)), dh = Math.max(1, (int) Math.round(ih * s));
            int dx = tx + (tw - dw) / 2, dy = ty + (thumbH - dh) / 2;
            RenderSystem.disableBlend();
            g.drawTexture(thumb, dx, dy, dw, dh, 0, 0, iw, ih, iw, ih);
        } else {
            String ph = e.info.subtitle().contains("minimized") ? "Minimized" : "…";
            UiKit.text(g, textRenderer, ph, tx + tw / 2 - textRenderer.getWidth(ph) / 2, ty + thumbH / 2 - 4, UiKit.TEXT_MUTED);
        }
        if (live) {
            int bw = textRenderer.getWidth("LIVE") + 8;
            UiKit.round(g, tx + 4, ty + 4, bw, 11, 2, UiKit.LIVE);
            UiKit.text(g, textRenderer, "LIVE", tx + 8, ty + 6, UiKit.TEXT);
        }
        if (sel) UiKit.roundOutline(g, x, y, cardW, cardH, 4, 2, UiKit.ACCENT);

        int ly = ty + thumbH + 5, lx = x + 6, maxW = cardW - 12;
        Identifier icon = e.iconId();
        if (icon != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.drawTexture(icon, lx, ly - 1, 10, 10, 0, 0, 32, 32, 32, 32);
            RenderSystem.disableBlend();
            lx += 13;
            maxW -= 13;
        }
        SourceInfo info = e.info;
        if (info.kind() == SourceInfo.Kind.WINDOW) {
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, info.title(), maxW), lx, ly, UiKit.TEXT);
        } else {
            String right = info.kind() == SourceInfo.Kind.TEST ? "demo" : info.subtitle();
            int rw = textRenderer.getWidth(right);
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, info.title(), maxW - rw - 6), lx, ly, UiKit.TEXT);
            UiKit.text(g, textRenderer, right, x + cardW - 6 - rw, ly, UiKit.TEXT_MUTED);
        }
    }

    private void renderFooter(DrawContext g, int mx, int my) {
        g.fill(px, footerY, px + pw, footerY + 1, UiKit.PANEL_EDGE);
        UiKit.round(g, px, footerY + 1, pw, py + ph - footerY - 1, 6, UiKit.SURFACE);
        g.fill(px, footerY + 1, px + pw, footerY + 7, UiKit.SURFACE); // square off the top corners
        hoverTip = null;

        int x = px + pad, labelY = footerY + 9, segY = footerY + 19;
        String[] res = labels(CaptureSettings.HEIGHTS, true), fps = labels(CaptureSettings.FPS, false), onOff = {"On", "Off"},
                shape = {"Auto", "Fill", "Fit"};
        boolean appSound = selectedKindCanPlayInGame();
        String[] sound = appSound ? new String[]{"Game", "App", "Off"} : onOff;
        int soundIdx = appSound ? (!audio ? 2 : hearInGame ? 0 : 1) : (audio ? 0 : 1);
        String primary = primaryLabel(), cancel = "Cancel";
        int gw = Math.max(64, textRenderer.getWidth(primary) + 22), cw = textRenderer.getWidth(cancel) + 18;
        int buttonsW = gw + 6 + cw + 12;
        // roomy layout: controls get the whole first row, buttons sit beside the status line;
        // compact (small windows): one row, buttons on the right
        int room = pw - pad * 2 - (compact ? buttonsW : 0) - segWidth(res) - segWidth(fps) - 12;
        // optional controls in priority order, shown while they fit
        int need = 0;
        boolean showAudio = room >= (need += segWidth(sound) + 12);
        boolean showFill = room >= (need += segWidth(shape) + 12);
        x = segmented(g, mx, my, x, labelY, segY, "RESOLUTION", res, heightIdx, i -> heightIdx = i);
        x = segmented(g, mx, my, x + 12, labelY, segY, "FRAME RATE", fps, fpsIdx, i -> fpsIdx = i);
        if (showAudio) {
            int ax = x + 12;
            x = segmented(g, mx, my, ax, labelY, segY, "AUDIO", sound, soundIdx, i -> {
                audio = appSound ? i != 2 : i == 0;
                if (appSound && i != 2) hearInGame = i == 0;
            });
            if (UiKit.inside(mx, my, ax, labelY - 2, x - ax, segY + 18 - labelY + 2)) {
                hoverTip = appSound
                        ? "Game: you hear it from the wall, like everyone else (the app goes quiet).  App: you hear it from the app as usual."
                        : "Shares the PC's sound, without Minecraft's own.";
            }
        }
        if (showFill) segmented(g, mx, my, x + 12, labelY, segY, "PICTURE", shape, fit, i -> fit = i);

        // buttons (right)
        int by = compact ? footerY + 17 : footerY + 41, bh = 20;
        boolean enabled = primaryEnabled();
        int gx = px + pw - pad - gw;
        boolean ghov = enabled && UiKit.inside(mx, my, gx, by, gw, bh);
        UiKit.round(g, gx, by, gw, bh, 3, !enabled ? UiKit.DISABLED : ghov ? UiKit.ACCENT_HOVER : UiKit.ACCENT);
        UiKit.text(g, textRenderer, primary, gx + gw / 2 - textRenderer.getWidth(primary) / 2, by + 6, enabled ? UiKit.TEXT : UiKit.TEXT_MUTED);
        if (enabled) hits.add(new Hit(gx, by, gw, bh, this::goLive));

        int cx = gx - 6 - cw;
        boolean chov = UiKit.inside(mx, my, cx, by, cw, bh);
        if (chov) UiKit.round(g, cx, by, cw, bh, 3, UiKit.SURFACE_HOVER);
        UiKit.text(g, textRenderer, cancel, cx + 9, by + 6, chov ? UiKit.TEXT : UiKit.TEXT_2);
        hits.add(new Hit(cx, by, cw, bh, this::close));

        if (!compact) {
            int textX = px + pad;
            dev.windowscraft.display.Share share = isLive() ? existing.share() : null;
            if (share != null && share.audio().playsInGame()) {
                // how loud you hear it from the wall yourself
                int trackW = 90;
                if (ownSlider.dragging()) setOwnVolume(share, ownSlider.valueAt(mx));
                ownSlider.renderPill(g, textRenderer, px + pad, by + 1, trackW, share.localVolume, false);
                hits.add(new Hit(px + pad + 6 + VolumeSlider.ICON_W - 4, by + 1, trackW + 8, 18, () -> { ownSlider.startDrag(); }));
                textX += VolumeSlider.pillWidth(textRenderer, trackW) + 10;
            }
            String hint = hoverTip != null ? hoverTip : tipLine();
            int tw = cx - 10 - textX;
            List<net.minecraft.text.OrderedText> lines = textRenderer.wrapLines(Text.literal(hint), tw);
            if (lines.size() <= 2) {
                for (int i = 0; i < lines.size(); i++)
                    g.drawText(textRenderer, lines.get(i), textX, by + (lines.size() == 1 ? 6 : 1 + i * 10), UiKit.TEXT_MUTED, false);
            } else {
                UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, hint, tw), textX, by + 6, UiKit.TEXT_MUTED);
            }
        }
    }

    /** Can the selected (or live) source's sound be moved into the game? Only a single app's can. */
    private boolean selectedKindCanPlayInGame() {
        SourceLoader.Entry sel = selectedEntry();
        SourceInfo.Kind k = sel != null ? sel.info.kind() : selectionIsLiveSource() ? existing.session().source().kind() : null;
        if (k == null) k = tab == TAB_APPS ? SourceInfo.Kind.WINDOW : SourceInfo.Kind.SCREEN;
        return k == SourceInfo.Kind.WINDOW || k == SourceInfo.Kind.TEST;
    }

    private String tipLine() {
        if (isLive()) {
            dev.windowscraft.audio.AudioHub hub = existing.share().audio();
            String sound = !hub.enabled() ? "" : hub.hearingSound() ? " · sound coming through" : " · no sound right now";
            String where = hub.playsInGame() ? " · you hear it from the wall" : "";
            StreamPublisher p = existing.publisher();
            if (p == null) return "Audio: " + hub.status() + sound + where;
            String st = p.status();
            if (st.startsWith("Preparing FFmpeg")) st = Ffmpeg.status();
            if (st.startsWith("Not streamed: ")) {
                String why = st.substring(14);
                return "Not streamed — " + (why.isEmpty() ? why : Character.toLowerCase(why.charAt(0)) + why.substring(1));
            }
            return "Stream: " + st + " · " + hub.status() + sound + where;
        }
        SourceLoader.Entry sel = selectedEntry();
        if (sel != null && sel.info.kind() == SourceInfo.Kind.SCREEN)
            return "Tip: sharing the screen Minecraft is on shows Minecraft itself — pick a window or a second monitor.";
        if (sel != null && sel.info.kind() == SourceInfo.Kind.WINDOW)
            return "Windows keep updating even when they're behind Minecraft or covered. Minimized windows pause.";
        return "Pick a window or screen. Double-click to go live instantly.";
    }

    private static void setOwnVolume(dev.windowscraft.display.Share share, double v) {
        share.localVolume = v;
        ClientSettings.setOwnVolume(v);
    }

    private interface IntSetter { void set(int i); }

    private int segOptionWidth(String[] opts) {
        int w = 0;
        for (String o : opts) w = Math.max(w, textRenderer.getWidth(o));
        return w + 12;
    }

    private int segWidth(String[] opts) {
        return segOptionWidth(opts) * opts.length + 4;
    }

    private int segmented(DrawContext g, int mx, int my, int x, int labelY, int y, String label, String[] opts, int sel, IntSetter set) {
        int segW = segOptionWidth(opts);
        UiKit.textScaled(g, textRenderer, label, x, labelY, 0.75f, UiKit.TEXT_MUTED);
        int w = segW * opts.length + 4, h = 18;
        UiKit.round(g, x, y, w, h, 3, UiKit.INSET);
        for (int i = 0; i < opts.length; i++) {
            int sx = x + 2 + i * segW;
            boolean hov = UiKit.inside(mx, my, sx, y + 2, segW, h - 4);
            if (i == sel) UiKit.round(g, sx, y + 2, segW, h - 4, 2, UiKit.SEG_ACTIVE);
            else if (hov) UiKit.round(g, sx, y + 2, segW, h - 4, 2, UiKit.SURFACE_HOVER);
            String o = opts[i];
            UiKit.text(g, textRenderer, o, sx + segW / 2 - textRenderer.getWidth(o) / 2, y + 5, i == sel ? UiKit.TEXT : UiKit.TEXT_2);
            final int idx = i;
            hits.add(new Hit(sx, y + 2, segW, h - 4, () -> set.set(idx)));
        }
        return x + w;
    }

    private static String[] labels(int[] values, boolean heights) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = heights ? CaptureSettings.heightLabel(values[i]) : String.valueOf(values[i]);
        return out;
    }

    // ---- state helpers ---------------------------------------------------------------------

    private CaptureSettings chosenSettings() {
        // Windows' own cursor isn't shared: the screen draws its pointer only while someone uses it
        return new CaptureSettings(CaptureSettings.HEIGHTS[heightIdx], CaptureSettings.FPS[fpsIdx], false, audio, fit, hearInGame);
    }

    private SourceLoader.Entry selectedEntry() {
        if (selectedId == null) return null;
        for (SourceLoader.Entry e : loader.windows) if (e.info.id().equals(selectedId)) return e;
        for (SourceLoader.Entry e : loader.screens) if (e.info.id().equals(selectedId)) return e;
        return null;
    }

    private boolean selectionIsLiveSource() {
        return isLive() && existing.session().source().id().equals(selectedId);
    }

    private String primaryLabel() {
        if (selectionIsLiveSource()) return chosenSettings().equals(existing.session().settings()) ? "Live" : "Apply";
        if (selectedId != null && DisplayManager.get().liveSourceIds().contains(selectedId)) return "Mirror"; // live on another wall
        return isLive() ? "Switch" : "Go Live";
    }

    private boolean primaryEnabled() {
        if (selectionIsLiveSource()) return !chosenSettings().equals(existing.session().settings());
        return selectedEntry() != null;
    }

    private void maybePreselect() {
        if (preselected || !loader.loadedOnce || existing == null || existing.lastSource == null) return;
        preselected = true;
        List<SourceInfo> all = new ArrayList<>();
        for (SourceLoader.Entry e : loader.windows) all.add(e.info);
        for (SourceLoader.Entry e : loader.screens) all.add(e.info);
        SourceInfo match = existing.lastSource.findMatch(all);
        if (match != null && selectedId == null) {
            selectedId = match.id();
            switchTab(match.kind() == SourceInfo.Kind.WINDOW ? TAB_APPS : TAB_SCREENS);
        }
    }

    // ---- actions ---------------------------------------------------------------------------

    private void click() {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f));
    }

    private void switchTab(int t) {
        if (t == TAB_SETTINGS) { // who can use it, and everything else: the hub
            MinecraftClient.getInstance().setScreen(existing != null ? new HubScreen(existing, HubScreen.TAB_PEOPLE) : HubScreen.settings());
            return;
        }
        if (tab != t) {
            tab = t;
            scroll = 0;
            // selection is per tab, like Discord: never Go Live with something you can't see
            if (t != TAB_SETTINGS) {
                boolean visible = false;
                for (SourceLoader.Entry e : currentEntries()) visible |= e.info.id().equals(selectedId);
                if (!visible) selectedId = null;
            }
            if (t != TAB_SETTINGS) loader.setPreferScreens(t == TAB_SCREENS);
        }
    }

    private void clickCard(SourceLoader.Entry e) {
        long now = System.currentTimeMillis();
        boolean dbl = e.info.id().equals(lastClickId) && now - lastClickTime < 350;
        lastClickId = e.info.id();
        lastClickTime = now;
        selectedId = e.info.id();
        if (dbl && primaryEnabled()) goLive();
    }

    private void goLive() {
        SourceLoader.Entry e = selectedEntry();
        if (e == null && !selectionIsLiveSource()) return;
        CaptureSettings s = chosenSettings();
        ClientSettings.set(s);
        DisplayManager dm = DisplayManager.get();
        if (selectionIsLiveSource()) {
            dm.updateSettings(existing, s);
        } else {
            Display d = existing != null ? existing : dm.create(dimension, newRect);
            dm.start(d, e.info, s);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Now sharing ").formatted(Formatting.GRAY)
                        .append(Text.literal(e.info.title()).formatted(Formatting.WHITE)), true);
            }
            WindowsCraftClient.LOGGER.info("Sharing '{}' ({}) at {} / {} fps", e.info.title(), e.info.kind(),
                    CaptureSettings.heightLabel(s.maxHeight()), s.fps());
        }
        close();
    }

    private void stopSharing() {
        if (existing != null) DisplayManager.get().stop(existing);
        close();
    }

    private void removeDisplay() {
        if (existing != null) DisplayManager.get().remove(existing);
        close();
    }

    // ---- input -----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (UiKit.inside(mx, my, h.x, h.y, h.w, h.h)) {
                    h.action.run();
                    if (ownSlider.dragging() && existing != null && existing.share() != null) setOwnVolume(existing.share(), ownSlider.valueAt(mx));
                    else click();
                    return true;
                }
            }
            if (!UiKit.inside(mx, my, px, py, pw, ph)) {
                close(); // click outside the dialog dismisses it
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        ownSlider.stopDrag();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= vertical * 24;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            switchTab((tab + 1) % 2);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && primaryEnabled()) {
            goLive();
            return true;
        }
        if (WindowsCraftClient.SHARE_KEY.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        loader.close();
        loader.syncTextures(true);
    }
}
