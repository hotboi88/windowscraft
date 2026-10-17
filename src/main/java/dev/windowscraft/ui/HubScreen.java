package dev.windowscraft.ui;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.monitor.InGameMonitors;
import dev.windowscraft.monitor.MonitorBackend;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * U from anywhere: every screen of yours in this world on the left (and other people's), and for
 * the one you pick — what's open on it, who can use it, its size and picture — plus the settings
 * for all of WindowsCraft. Sneak + U at an in-game monitor opens it on that monitor.
 */
public final class HubScreen extends Screen {
    public static final int TAB_WINDOWS = 0, TAB_PEOPLE = 1, TAB_PICTURE = 2;
    private static final int PAD = 12, ROW = 20, SIDE_ROW = 26;

    private Display selected;
    private boolean settingsPage;
    private int tab;

    private int px, py, pw, ph, side;
    /** The menu's own size (Settings → Menu size) on top of Minecraft's GUI scale; everything is laid out in its units. */
    private double k = 1;
    // Renaming a screen: which one, and the name as typed so far
    private Display renaming;
    private String renameText = "";
    // Menu size: dragging its slider (it's applied when you let go: the menu doesn't change size under your mouse)
    private boolean scaleDrag;
    private int scaleX, scaleW;
    private double scalePending = -1;
    private final List<Hit> hits = new ArrayList<>();
    private int clipTop = Integer.MIN_VALUE, clipBottom = Integer.MAX_VALUE;
    private double sideScroll, scroll;

    // Windows
    private volatile List<MonitorBackend.Win> onIt = List.of(), desk = List.of();
    private volatile Map<Display, List<MonitorBackend.Win>> elsewhere = Map.of();
    private long expanded, refreshed;
    // Picture
    private volatile List<int[]> modes;
    private volatile Display modesFor, windowsFor;
    private volatile String modeNote, windowsNote;
    private volatile boolean modeBusy;
    // Settings
    private boolean speedDrag;
    private int speedX, speedW;
    private volatile String driverText;
    private volatile boolean driverBusy;

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    /** The hub on the screen you're most likely after (an in-game monitor that's on, else one that's live, else any). */
    public HubScreen() { this(null, -1); }

    /** The hub on that screen, on that tab (-1: whichever fits). */
    public HubScreen(Display select, int tab) {
        super(Text.literal("WindowsCraft"));
        selected = select != null ? select : pickDefault();
        this.tab = tab >= 0 ? tab : selected != null && InGameMonitors.isOn(selected) ? TAB_WINDOWS : TAB_PEOPLE;
    }

    /** The settings for all of WindowsCraft. */
    public static HubScreen settings() {
        HubScreen h = new HubScreen(null, -1);
        h.settingsPage = true;
        return h;
    }

    private static Display pickDefault() {
        List<Display> all = DisplayManager.get().all();
        for (Display d : all) if (InGameMonitors.isOn(d)) return d;
        for (Display d : all) if (d.isLive()) return d;
        return all.isEmpty() ? null : all.get(0);
    }

    /** Opens a window's "Move" choices (as clicking its Move button does). */
    public void showMove(long hwnd) { expanded = hwnd; }

    @Override public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        layout();
        refresh();
    }

    private void layout() {
        k = ClientSettings.hubScale();
        int vw = (int) (width / k), vh = (int) (height / k);
        pw = Math.min(640, vw - 16);
        ph = Math.min(400, vh - 12);
        px = (vw - pw) / 2;
        py = (vh - ph) / 2;
        side = pw < 480 ? 124 : 160;
    }

    /** A clipped area, in the menu's units (Minecraft's own scissor doesn't follow the menu's size). */
    private void scissor(DrawContext g, int x1, int y1, int x2, int y2) {
        g.enableScissor((int) Math.floor(x1 * k), (int) Math.floor(y1 * k), (int) Math.ceil(x2 * k), (int) Math.ceil(y2 * k));
    }

    // ---- state -----------------------------------------------------------------------------

    private void select(Display d) {
        if (selected != d || settingsPage) scroll = 0;
        selected = d;
        settingsPage = false;
        expanded = 0;
        if (tab == TAB_WINDOWS && !InGameMonitors.isOn(d)) tab = TAB_PEOPLE;
        refresh();
    }

    private void refresh() {
        refreshed = System.nanoTime();
        Display d = selected;
        if (d == null || !InGameMonitors.isOn(d)) return;
        if (windowsFor != d) { // another screen: don't show the last one's windows meanwhile
            windowsFor = d;
            onIt = List.of();
            expanded = 0;
        }
        InGameMonitors.windowsOn(d).thenAccept(l -> { if (selected == d) onIt = l; });
        InGameMonitors.copiesOn(d).thenAccept(l -> { if (selected == d) copies = l; });
        dev.windowscraft.monitor.Sandbox.refresh();
        InGameMonitors.deskWindows().thenAccept(l -> desk = l);
        Map<Display, List<MonitorBackend.Win>> fresh = new ConcurrentHashMap<>();
        List<Display> others = otherMonitors();
        if (others.isEmpty()) elsewhere = Map.of();
        for (Display o : others)
            InGameMonitors.windowsOn(o).thenAccept(l -> {
                fresh.put(o, l);
                if (fresh.size() == others.size()) elsewhere = new LinkedHashMap<>(fresh);
            });
        if (modesFor != d || (modes != null && modes.isEmpty() && !modeBusy)) { // (an empty answer: ask again)
            if (modesFor != d) {
                modes = null;
                modeNote = null;
                modeBusy = false;
            }
            modesFor = d;
            InGameMonitors.modes(d).thenAccept(l -> { if (modesFor == d) modes = l; });
        }
    }

    private List<Display> otherMonitors() {
        List<Display> out = new ArrayList<>();
        for (Display o : DisplayManager.get().all()) if (o != selected && InGameMonitors.isOn(o)) out.add(o);
        return out;
    }

    @Override
    public void tick() {
        if (selected != null && !DisplayManager.get().all().contains(selected)) {
            if (settingsPage) selected = pickDefault(); // (stay on the settings)
            else select(pickDefault());
        }
        if (selected != null && tab == TAB_WINDOWS && !InGameMonitors.isOn(selected) && !InGameMonitors.starting(selected)) tab = TAB_PEOPLE;
        if (selected != null && InGameMonitors.isOn(selected) && modesFor != selected) refresh();
        if (System.nanoTime() - refreshed > 1_000_000_000L) refresh();
    }

    /** What a screen is called in the list. */
    static String nameOf(Display d) {
        if (d.name != null && !d.name.isBlank()) return d.name;
        if (d.tablet) return "Your tablet";
        if (d.share() != null) return InGameMonitors.isInGameMonitor(d.share().source()) ? InGameMonitors.name(d.share().source()) : d.share().source().title();
        int n = 0;
        for (Display o : DisplayManager.get().all()) {
            if (o.tablet) continue;
            n++;
            if (o == d) break;
        }
        return (d.rect.isMonitor() ? "Gaming monitor " : "Wall ") + n;
    }

    private static String whereOf(Display d) {
        if (d.tablet) return "in your hand";
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.world != null && mc.world.getRegistryKey().getValue().toString().equals(d.dimension)) {
            double dist = mc.player.getPos().distanceTo(d.rect.center());
            return dist < 12 ? "right here" : Math.round(dist) + " m away";
        }
        String dim = d.dimension.substring(d.dimension.indexOf(':') + 1).replace('_', ' ');
        return dim.isEmpty() ? "elsewhere" : "in the " + dim;
    }

    private static int stateColor(Display d) {
        if (InGameMonitors.isOn(d)) return 0xFF3BA55D;
        if (InGameMonitors.starting(d)) return 0xFFFAA61A;
        if (d.isLive()) return UiKit.LIVE;
        return UiKit.TEXT_MUTED;
    }

    private static String stateText(Display d) {
        if (d.tablet) return InGameMonitors.isOn(d) ? "On" : InGameMonitors.starting(d) ? "Turning on…" : "Off";
        if (InGameMonitors.isOn(d)) return "In-game monitor, on";
        if (InGameMonitors.starting(d)) return "Turning on…";
        if (d.isLive()) return d.share().source().kind() == SourceInfo.Kind.WINDOW ? "Sharing an app" : "Sharing a screen";
        return "Off";
    }

    // ---- drawing helpers -------------------------------------------------------------------

    private void hit(int x, int y, int w, int h, Runnable action) {
        if (y + h <= clipTop || y >= clipBottom) return;
        hits.add(new Hit(x, Math.max(y, clipTop), w, Math.min(y + h, clipBottom) - Math.max(y, clipTop), action));
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return my >= clipTop && my < clipBottom && UiKit.inside(mx, my, x, y, w, h);
    }

    /** A button whose right edge is at {@code right}; returns its width. */
    private int button(DrawContext g, int mx, int my, String label, int right, int y, int h, int color, int hoverColor, Runnable action) {
        int bw = textRenderer.getWidth(label) + 16, bx = right - bw;
        boolean hov = hover(mx, my, bx, y, bw, h);
        UiKit.round(g, bx, y, bw, h, 3, hov ? hoverColor : color);
        UiKit.text(g, textRenderer, label, bx + 8, y + (h - 8) / 2, UiKit.TEXT);
        hit(bx, y, bw, h, action);
        return bw;
    }

    private int wrap(DrawContext g, String s, int x, int y, int w, int color) {
        for (OrderedText t : textRenderer.wrapLines(Text.literal(s), Math.max(40, w))) {
            g.drawText(textRenderer, t, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private int segOptionWidth(String[] opts) {
        int w = 0;
        for (String o : opts) w = Math.max(w, textRenderer.getWidth(o));
        return w + 12;
    }

    /** A row of choices; returns its width. */
    private int segmented(DrawContext g, int mx, int my, int x, int y, String[] opts, int sel, IntConsumer set) {
        int segW = segOptionWidth(opts), total = segW * opts.length + 4;
        UiKit.round(g, x, y, total, 16, 3, UiKit.INSET);
        for (int i = 0; i < opts.length; i++) {
            int sx = x + 2 + i * segW;
            boolean s = i == sel, hov = hover(mx, my, sx, y + 2, segW, 12);
            if (s) UiKit.round(g, sx, y + 2, segW, 12, 2, UiKit.ACCENT);
            else if (hov) UiKit.round(g, sx, y + 2, segW, 12, 2, UiKit.SURFACE_HOVER);
            UiKit.text(g, textRenderer, opts[i], sx + segW / 2 - textRenderer.getWidth(opts[i]) / 2, y + 4, s ? UiKit.TEXT : UiKit.TEXT_2);
            final int idx = i;
            hit(sx, y + 2, segW, 12, () -> set.accept(idx));
        }
        return total;
    }

    /** A setting: its name, its choices on the right, and what it does underneath. Returns the next y. */
    private int choiceRow(DrawContext g, int mx, int my, int x, int y, int w, String label, String explain, String[] opts, int sel, IntConsumer set) {
        int segTotal = segOptionWidth(opts) * opts.length + 4;
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, label, w - segTotal - 12), x + 4, y + 4, UiKit.TEXT);
        segmented(g, mx, my, x + w - segTotal, y, opts, sel, set);
        y += 18;
        if (explain != null && !explain.isEmpty()) y = wrap(g, explain, x + 4, y, w - segTotal - 12, UiKit.TEXT_MUTED);
        return y + 6;
    }

    private int toggleRow(DrawContext g, int mx, int my, int x, int y, int w, String label, String explain, boolean on, Consumer<Boolean> set) {
        return choiceRow(g, mx, my, x, y, w, label, explain, new String[]{"On", "Off"}, on ? 0 : 1, i -> set.accept(i == 0));
    }

    /**
     * A setting with its choices on the right (or under its name, when both don't fit) and what
     * it does underneath, the whole width. Returns the next y.
     */
    private int pickRow(DrawContext g, int mx, int my, int x, int y, int w, String label, String explain, String[] opts, int sel, IntConsumer set) {
        int segTotal = segOptionWidth(opts) * opts.length + 4;
        boolean below = textRenderer.getWidth(label) + segTotal + 16 > w;
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, label, w - 8), x + 4, y + 4, UiKit.TEXT);
        if (below) y += 14;
        if (opts.length > 0) segmented(g, mx, my, below ? x + 4 : x + w - segTotal, y, opts, sel, set);
        y += 18;
        if (explain != null && !explain.isEmpty()) y = wrap(g, explain, x + 4, y, w - 8, UiKit.TEXT_MUTED);
        return y + 6;
    }

    /** A performance setting: in Automatic, what it's doing (nothing to click); in Advanced, your pick. */
    private int perfRow(DrawContext g, int mx, int my, int x, int y, int w, ClientSettings.Auto a, String label, String explain, String now) {
        String tail = now != null ? " " + now : "";
        if (ClientSettings.perfAdvanced()) {
            Boolean p = ClientSettings.pick(a);
            String auto = p == null ? " Automatic: " + (ClientSettings.automatic(a) ? "on." : "off.") : "";
            return pickRow(g, mx, my, x, y, w, label, explain + auto + tail, new String[]{"Automatic", "On", "Off"}, p == null ? 0 : p ? 1 : 2,
                    i -> ClientSettings.setPick(a, i == 0 ? null : i == 1));
        }
        // (plain words, not a box: nothing to click here)
        String state = ClientSettings.on(a) ? "On" : "Off";
        int sw = textRenderer.getWidth(state);
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, label, w - sw - 16), x + 4, y + 4, UiKit.TEXT);
        UiKit.text(g, textRenderer, state, x + w - sw - 4, y + 4, UiKit.TEXT_2);
        return wrap(g, explain + tail, x + 4, y + 18, w - 8, UiKit.TEXT_MUTED) + 6;
    }

    /** One of this screen's own picks: "Like all screens" or its own. */
    private int overrideRow(DrawContext g, int mx, int my, int x, int y, int w, Display d, ClientSettings.Auto a, String label, String own,
                            String explain) {
        boolean mine = d.overrides.containsKey(a.key);
        return pickRow(g, mx, my, x, y, w, label, explain, new String[]{"Like all screens", own}, mine ? 1 : 0, i -> {
            if (i == 1) d.overrides.put(a.key, "false");
            else d.overrides.remove(a.key);
            DisplayManager.get().save();
        });
    }

    private static String plural(long n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** Off-screen pause, now: which of your screens rest because you can't see them. */
    private static String pauseNow() {
        var live = DisplayManager.get().liveShares();
        if (live.isEmpty()) return null;
        long resting = live.stream().filter(s -> !s.seenRecently()).count();
        return resting == 0 ? "Now: every screen you share is in view." : "Now: " + plural(resting, "screen is", "screens are") + " out of view, resting.";
    }

    /** Distance frame-rate limit, now: which of your screens look small enough to be slowed down. */
    private static String farNow() {
        var live = DisplayManager.get().liveShares();
        if (live.isEmpty()) return null;
        long far = live.stream().filter(s -> s.seenRecently() && s.viewPx() < dev.windowscraft.render.DisplayRenderer.FAR_PX).count();
        return far == 0 ? "Now: none of your screens is that far away." : "Now: " + plural(far, "screen is", "screens are") + " far enough away for it.";
    }

    /** As sharp as it looks, now: the screens your walls get a smaller copy of. */
    private static String sharpNow() {
        var live = DisplayManager.get().liveShares();
        if (live.isEmpty()) return null;
        List<String> small = new ArrayList<>();
        for (var s : live) {
            int[] c = s.smallerCopy();
            if (c != null) small.add(s.source().title() + " at " + c[0] + " × " + c[1] + " (of " + c[2] + " × " + c[3] + ")");
        }
        if (small.isEmpty()) return "Now: every screen at full size.";
        return "Now: " + String.join("; ", small.subList(0, Math.min(2, small.size()))) + (small.size() > 2 ? " and " + (small.size() - 2) + " more." : ".");
    }

    private int heading(DrawContext g, String s, int x, int y) {
        UiKit.textScaled(g, textRenderer, s, x, y, 0.75f, UiKit.TEXT_MUTED);
        return y + 13;
    }

    // ---- rendering -------------------------------------------------------------------------

    @Override
    public void render(DrawContext g, int rmx, int rmy, float delta) {
        super.render(g, rmx, rmy, delta);
        layout();
        int mx = (int) Math.floor(rmx / k), my = (int) Math.floor(rmy / k);
        g.getMatrices().push();
        g.getMatrices().scale((float) k, (float) k, 1);
        try {
            renderMenu(g, mx, my);
        } finally {
            g.getMatrices().pop();
        }
    }

    private void renderMenu(DrawContext g, int mx, int my) {
        hits.clear();
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        UiKit.round(g, px - 1, py + 1, pw + 2, ph + 2, 6, 0x60000000);
        UiKit.round(g, px, py, pw, ph, 6, UiKit.PANEL);
        UiKit.round(g, px, py, side, ph, 6, UiKit.SURFACE);
        g.fill(px + side - 6, py, px + side, py + ph, UiKit.SURFACE);
        renderSide(g, mx, my);

        // close
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        int cx = px + pw - PAD - 12, cy = py + 10;
        boolean chov = UiKit.inside(mx, my, cx - 2, cy - 2, 16, 16);
        if (chov) UiKit.round(g, cx - 2, cy - 2, 16, 16, 3, UiKit.SURFACE_HOVER);
        UiKit.text(g, textRenderer, "✕", cx + 2, cy + 2, chov ? UiKit.TEXT : UiKit.TEXT_2);
        hit(cx - 2, cy - 2, 16, 16, this::close);

        int x = px + side + PAD, w = pw - side - PAD * 2;
        if (settingsPage) renderSettings(g, mx, my, x, w);
        else if (selected == null) renderWelcome(g, x, w);
        else renderScreen(g, mx, my, x, w);
    }

    private void renderSide(DrawContext g, int mx, int my) {
        int x = px + 6, w = side - 12;
        UiKit.textScaled(g, textRenderer, "WindowsCraft", px + PAD, py + 12, 1.2f, UiKit.TEXT);
        int top = py + 30, bottom = py + ph - 30;
        clipTop = top;
        clipBottom = bottom;
        scissor(g, px, top, px + side, bottom);
        int y = top - (int) sideScroll;
        y = heading(g, "MY SCREENS", x + 4, y + 2);
        List<Display> mine = DisplayManager.get().all();
        if (mine.isEmpty()) {
            y = wrap(g, "None yet.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 4;
        }
        for (Display d : mine) {
            boolean sel = d == selected && !settingsPage, hov = hover(mx, my, x, y, w, SIDE_ROW - 2);
            if (sel || hov) UiKit.round(g, x, y, w, SIDE_ROW - 2, 4, sel ? UiKit.SEG_ACTIVE : UiKit.SURFACE_HOVER);
            UiKit.round(g, x + 6, y + 6, 6, 6, 3, stateColor(d));
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, nameOf(d), w - 22), x + 17, y + 4, UiKit.TEXT);
            UiKit.textScaled(g, textRenderer, UiKit.ellipsize(textRenderer, whereOf(d), (int) ((w - 22) / 0.8f)), x + 17, y + 15, 0.8f, UiKit.TEXT_MUTED);
            hit(x, y, w, SIDE_ROW - 2, () -> select(d));
            y += SIDE_ROW;
        }
        List<RemoteDisplays.Remote> theirs = new ArrayList<>(RemoteDisplays.all());
        if (!theirs.isEmpty()) {
            y = heading(g, "OTHER PEOPLE'S", x + 4, y + 8);
            for (RemoteDisplays.Remote r : theirs) {
                boolean hov = hover(mx, my, x, y, w, SIDE_ROW - 2);
                if (hov) UiKit.round(g, x, y, w, SIDE_ROW - 2, 4, UiKit.SURFACE_HOVER);
                UiKit.round(g, x + 6, y + 6, 6, 6, 3, r.controllableByMe() ? 0xFF3BA55D : UiKit.TEXT_MUTED);
                UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, r.ownerName + "'s", w - 22), x + 17, y + 4, UiKit.TEXT);
                String sub = (r.controllableByMe() ? "you can use it · " : "") + r.info.title();
                UiKit.textScaled(g, textRenderer, UiKit.ellipsize(textRenderer, sub, (int) ((w - 22) / 0.8f)), x + 17, y + 15, 0.8f, UiKit.TEXT_MUTED);
                hit(x, y, w, SIDE_ROW - 2, () -> MinecraftClient.getInstance().setScreen(new RemoteOptionsScreen(r, this)));
                y += SIDE_ROW;
            }
        }
        g.disableScissor();
        int content = y + (int) sideScroll - top;
        sideScroll = Math.max(0, Math.min(sideScroll, Math.max(0, content - (bottom - top))));
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        int sy = py + ph - 26;
        boolean shov = UiKit.inside(mx, my, x, sy, w, 20);
        if (settingsPage || shov) UiKit.round(g, x, sy, w, 20, 4, settingsPage ? UiKit.SEG_ACTIVE : UiKit.SURFACE_HOVER);
        UiKit.gear(g, x + 8, sy + 4, UiKit.TEXT);
        UiKit.text(g, textRenderer, "Settings", x + 24, sy + 6, UiKit.TEXT);
        hit(x, sy, w, 20, () -> {
            settingsPage = true;
            scroll = 0;
        });
    }

    private void renderWelcome(DrawContext g, int x, int w) {
        UiKit.textScaled(g, textRenderer, "No screens yet", x, py + 12, 1.3f, UiKit.TEXT);
        String k = WindowsCraftClient.keyName();
        int y = py + 36;
        y = wrap(g, "Build a wall of black concrete, look at it and press " + k + ".", x, y, w, UiKit.TEXT_2) + 6;
        if (InGameMonitors.supported()) {
            y = wrap(g, "It turns into an in-game monitor: a real extra screen for your PC, with its own taskbar and Start menu. "
                    + "Sit down at it with " + dev.windowscraft.client.TheaterMode.keyName() + " and use it like any monitor.", x, y, w, UiKit.TEXT_2) + 6;
            wrap(g, "Sneak + " + k + " at a wall instead to show one of your windows or screens on it.", x, y, w, UiKit.TEXT_2);
        } else {
            wrap(g, "Pick a window or screen of yours, and it shows on the wall for everyone here.", x, y, w, UiKit.TEXT_2);
        }
    }

    private void renderScreen(DrawContext g, int mx, int my, int x, int w) {
        Display d = selected;
        boolean on = InGameMonitors.isOn(d);
        if (renaming == d) {
            // typing its new name: Enter keeps it, Esc doesn't; left empty, it's called what it was
            int bw = Math.min(260, w - 120);
            UiKit.round(g, x - 2, py + 7, bw, 18, 3, UiKit.INSET);
            String caret = (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            String typed = renameText;
            while (!typed.isEmpty() && textRenderer.getWidth(typed + "_") > bw - 10) typed = typed.substring(1);
            UiKit.text(g, textRenderer, typed + caret, x + 3, py + 12, UiKit.TEXT);
            int right = x + bw + 4;
            int ok = button(g, mx, my, "Save", right + textRenderer.getWidth("Save") + 16, py + 8, 16, UiKit.ACCENT, UiKit.ACCENT_HOVER, this::finishRename);
            button(g, mx, my, "Cancel", right + ok + 4 + textRenderer.getWidth("Cancel") + 16, py + 8, 16, UiKit.SURFACE, UiKit.SURFACE_HOVER, () -> renaming = null);
        } else {
            String title = UiKit.ellipsize(textRenderer, nameOf(d), (int) ((w - 90) / 1.3f));
            UiKit.textScaled(g, textRenderer, title, x, py + 12, 1.3f, UiKit.TEXT);
            int tx = x + (int) (textRenderer.getWidth(title) * 1.3f) + 8;
            button(g, mx, my, "Rename", tx + textRenderer.getWidth("Rename") + 16, py + 10, 14, UiKit.SURFACE, UiKit.SURFACE_HOVER, () -> {
                renaming = d;
                renameText = d.name != null ? d.name : "";
            });
        }
        BlockPos c = BlockPos.ofFloored(d.rect.center());
        MonitorBackend.Mon m = InGameMonitors.monitorOf(d);
        String sub = d.tablet ? stateText(d) + (m != null ? " · " + m.width() + " × " + m.height() : "") + " · only you see it · right-click with it to hold it up"
                : stateText(d) + (m != null ? " · " + m.width() + " × " + m.height() : "") + " · "
                + (d.rect.isMonitor() ? "gaming monitor" : d.rect.widthBlocks() + "×" + d.rect.heightBlocks() + " wall")
                + " at " + c.getX() + ", " + c.getY() + ", " + c.getZ() + " · " + whereOf(d);
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, sub, w - 4), x, py + 28, UiKit.TEXT_MUTED);

        // its volume on your speakers (0 – 500 %): the share's own, and the next one's too
        var share = d.share();
        double vol = share != null ? share.localVolume : ClientSettings.ownVolume();
        int sw = Math.min(220, w - 70);
        if (volSlider.dragging()) setScreenVolume(share, volSlider.valueAt(mx));
        volSlider.render(g, textRenderer, x, py + 45, sw, vol, false);
        hit(x + VolumeSlider.ICON_W - 4, py + 39, sw - VolumeSlider.ICON_W + 8, 16, () -> {
            volSlider.startDrag();
            setScreenVolume(share, volSlider.valueAt(mx));
        });

        // tabs on the left, what you can do with it on the right
        int ty = py + 58;
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        if (on) { names.add("Windows"); ids.add(TAB_WINDOWS); }
        if (!d.tablet) {
            names.add("Who can use it");
            ids.add(TAB_PEOPLE);
        } else if (tab == TAB_PEOPLE) tab = on ? TAB_WINDOWS : TAB_PICTURE;
        names.add("Picture");
        ids.add(TAB_PICTURE);
        if (!ids.contains(tab)) tab = ids.get(0); // (a tab this screen doesn't have)
        int tx = x;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            int tw = textRenderer.getWidth(n) + 14, id = ids.get(i);
            boolean sel = tab == id, hov = UiKit.inside(mx, my, tx, ty, tw, 16);
            if (sel || hov) UiKit.round(g, tx, ty, tw, 16, 3, sel ? UiKit.SEG_ACTIVE : UiKit.SURFACE_HOVER);
            UiKit.text(g, textRenderer, n, tx + 7, ty + 4, sel ? UiKit.TEXT : UiKit.TEXT_2);
            hit(tx, ty, tw, 16, () -> { tab = id; scroll = 0; expanded = 0; refresh(); });
            tx += tw + 2;
        }
        int right = x + w;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (on) {
            right -= button(g, mx, my, "Turn off", right, ty, 16, UiKit.DANGER, UiKit.DANGER_HOVER, () -> InGameMonitors.turnOff(d)) + 4;
        } else if (InGameMonitors.starting(d)) {
            String s = "Turning on…";
            UiKit.text(g, textRenderer, s, right - textRenderer.getWidth(s), ty + 4, UiKit.TEXT_MUTED);
        } else if (d.tablet) {
            String s = "Right-click with a tablet to turn it on";
            UiKit.text(g, textRenderer, s, right - textRenderer.getWidth(s), ty + 4, UiKit.TEXT_MUTED);
        } else if (d.isLive()) {
            right -= button(g, mx, my, "Stop", right, ty, 16, UiKit.DANGER, UiKit.DANGER_HOVER, () -> DisplayManager.get().stop(d)) + 4;
            if (right - tx > 90) button(g, mx, my, "Change…", right, ty, 16, UiKit.SURFACE_HOVER, UiKit.SEG_ACTIVE, () -> mc.setScreen(SharePickerScreen.forDisplay(d)));
        } else {
            if (InGameMonitors.supported())
                right -= button(g, mx, my, "Turn on", right, ty, 16, UiKit.ACCENT, UiKit.ACCENT_HOVER,
                        () -> InGameMonitors.turnOn(mc, d.dimension, d.rect, d)) + 4;
            if (right - tx > 90) button(g, mx, my, "Share…", right, ty, 16, UiKit.SURFACE_HOVER, UiKit.SEG_ACTIVE, () -> mc.setScreen(SharePickerScreen.forDisplay(d)));
        }
        g.fill(x, ty + 20, x + w, ty + 21, UiKit.DIVIDER);

        int top = ty + 26, bottom = py + ph - 8;
        clipTop = top;
        clipBottom = bottom;
        scissor(g, x - 2, top, x + w + 2, bottom);
        int y0 = top - (int) scroll, y;
        if (tab == TAB_WINDOWS && on) y = renderWindows(g, mx, my, x, y0, w);
        else if (tab == TAB_PICTURE) y = renderPicture(g, mx, my, x, y0, w);
        else y = renderPeople(g, mx, my, x, y0, w);
        g.disableScissor();
        scrollbar(g, x + w + 1, top, bottom, y - y0);
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
    }

    private void scrollbar(DrawContext g, int x, int top, int bottom, int content) {
        int h = bottom - top;
        double max = Math.max(0, content - h);
        scroll = Math.max(0, Math.min(scroll, max));
        if (max <= 0) return;
        int len = Math.max(20, (int) (h * (h / (double) content)));
        int ty = top + (int) ((h - len) * (scroll / max));
        UiKit.round(g, x, top, 3, h, 1, UiKit.INSET);
        UiKit.round(g, x, ty, 3, len, 1, UiKit.TEXT_MUTED);
    }

    // ---- Windows -----------------------------------------------------------------------------

    private void moved() {
        refreshed = 0;
        expanded = 0;
    }

    private int renderWindows(DrawContext g, int mx, int my, int x, int y, int w) {
        Display d = selected;
        int right = x + w;
        List<Display> others = otherMonitors();
        y = heading(g, "OPEN ON IT", x, y + 2);
        if (windowsNote != null) y = wrap(g, windowsNote, x, y + 2, w, UiKit.TEXT_2) + 2;
        if (onIt.isEmpty()) {
            UiKit.text(g, textRenderer, "Nothing yet — sit down at it (" + dev.windowscraft.client.TheaterMode.keyName()
                    + ") and start apps from its own taskbar.", x + 4, y + 5, UiKit.TEXT_MUTED);
            y += ROW;
        }
        String sharedApp = null;
        for (MonitorBackend.Win win : onIt) {
            UiKit.round(g, x, y, w, ROW - 2, 3, UiKit.SURFACE);
            int bx = right - 3;
            bx -= button(g, mx, my, "Close", bx, y + 2, 14, UiKit.SEG_ACTIVE, UiKit.DANGER_HOVER, () -> { InGameMonitors.close(win.hwnd()); moved(); }) + 4;
            // the same browser is open somewhere else too: one program, one sound (said below)
            if (sharedElsewhere(win) && InGameMonitors.appName(win.pid()) != null) sharedApp = InGameMonitors.appName(win.pid());
            boolean open = expanded == win.hwnd();
            bx -= button(g, mx, my, open ? "Move ▴" : "Move ▾", bx, y + 2, 14, open ? UiKit.SURFACE_HOVER : UiKit.SEG_ACTIVE, UiKit.SURFACE_HOVER,
                    () -> expanded = expanded == win.hwnd() ? 0 : win.hwnd()) + 4;
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, win.title(), bx - 4 - (x + 6)), x + 6, y + 5, UiKit.TEXT);
            y += ROW;
            if (open) {
                UiKit.text(g, textRenderer, "to", x + 10, y + 5, UiKit.TEXT_MUTED);
                int ax = x + 10 + textRenderer.getWidth("to") + 6;
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                labels.add("Your desk");
                acts.add(() -> { InGameMonitors.sendHome(win.hwnd()); moved(); });
                for (Display o : others) {
                    labels.add(nameOf(o));
                    acts.add(() -> { InGameMonitors.bring(win.hwnd(), o); moved(); });
                }
                for (int i = 0; i < labels.size(); i++) {
                    int bw = textRenderer.getWidth(labels.get(i)) + 16;
                    if (ax + bw > right - 4) break;
                    button(g, mx, my, labels.get(i), ax + bw, y + 2, 14, UiKit.ACCENT, UiKit.ACCENT_HOVER, acts.get(i));
                    ax += bw + 4;
                }
                y += ROW;
            }
        }
        if (sharedApp != null) {
            y = wrap(g, sharedApp + " is also open somewhere else, and Windows gives one program one sound, so it plays where you last used it.",
                    x, y + 2, w, UiKit.TEXT_MUTED) + 2;
        }
        y = renderOwnCopies(g, mx, my, x, y, w);
        y = heading(g, "BRING A WINDOW HERE", x, y + 8);
        List<Object[]> candidates = new ArrayList<>(); // {Win, where}
        for (MonitorBackend.Win win : desk) candidates.add(new Object[]{win, "your desk"});
        for (Map.Entry<Display, List<MonitorBackend.Win>> e : elsewhere.entrySet())
            if (InGameMonitors.isOn(e.getKey())) for (MonitorBackend.Win win : e.getValue()) candidates.add(new Object[]{win, nameOf(e.getKey())});
        if (candidates.isEmpty()) {
            UiKit.text(g, textRenderer, "No other windows open.", x + 4, y + 5, UiKit.TEXT_MUTED);
            y += ROW;
        }
        for (Object[] c : candidates) {
            MonitorBackend.Win win = (MonitorBackend.Win) c[0];
            UiKit.round(g, x, y, w, ROW - 2, 3, UiKit.SURFACE);
            int bx = right - 3;
            bx -= button(g, mx, my, "Bring here", bx, y + 2, 14, UiKit.ACCENT, UiKit.ACCENT_HOVER, () -> { InGameMonitors.bring(win.hwnd(), d); moved(); }) + 4;
            String where = (String) c[1];
            int ww = textRenderer.getWidth(where);
            UiKit.text(g, textRenderer, where, bx - 4 - ww, y + 5, UiKit.TEXT_MUTED);
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, win.title(), bx - 14 - ww - (x + 6)), x + 6, y + 5, UiKit.TEXT);
            y += ROW;
        }
        y += 6;
        y = wrap(g, "When it turns off, the apps on it close (windows you brought from your desk go back there).", x, y, w, UiKit.TEXT_MUTED);
        return y + 6;
    }

    private List<MonitorBackend.Win> copies = List.of();
    private String copiesNote;
    private long resetArmedAt;

    /**
     * "Own copies of apps": the apps you pick to have their own copy on every monitor (none until
     * you do), set up once; one more copy of anything here by hand; this monitor's copies reset.
     */
    private int renderOwnCopies(DrawContext g, int mx, int my, int x, int y, int w) {
        Display d = selected;
        int right = x + w;
        y = heading(g, "OWN COPIES OF APPS", x, y + 8);
        String st = dev.windowscraft.monitor.Sandbox.status(), prog = dev.windowscraft.monitor.Sandbox.progress();
        if (!dev.windowscraft.monitor.Sandbox.ready()) {
            if (prog != null) {
                UiKit.text(g, textRenderer, prog, x + 4, y + 4, UiKit.TEXT_2);
                return y + ROW;
            }
            UiKit.round(g, x, y, w, ROW - 2, 3, UiKit.SURFACE);
            int bx = right - 3;
            bx -= button(g, mx, my, "Set up", bx, y + 2, 14, UiKit.ACCENT, UiKit.ACCENT_HOVER, () -> {
                copiesNote = null;
                dev.windowscraft.monitor.Sandbox.install().thenAccept(s -> copiesNote = s.equals("ok") ? "Ready: pick the apps that get their own copy on every monitor."
                        : s.equals("cancelled") ? "Not set up (Windows' permission was declined)." : "Couldn't set it up: " + s);
            }) + 4;
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, "Give apps their own copy on every monitor", bx - 4 - (x + 6)), x + 6, y + 5, UiKit.TEXT);
            y += ROW;
            if (copiesNote != null) y = wrap(g, copiesNote, x, y + 2, w, UiKit.TEXT_2) + 2;
            return wrap(g, "For apps you have open on several monitors at once (a browser, Discord): each monitor runs its own copy, so its sound "
                    + "is never mixed with another monitor's or your desk's, and it keeps its own sign-ins. Only the apps you pick: games and "
                    + "everything else run normally. One-time setup of the free Sandboxie-Plus "
                    + "(Windows asks for permission once; it never shows itself)." + (st.equals("stopped") ? " It's installed but not running: restart your PC." : ""),
                    x, y + 2, w, UiKit.TEXT_MUTED) + 2;
        }
        // the apps with their own copy on every monitor (the same list on all of them)
        List<String> picked = ClientSettings.ownCopyApps();
        UiKit.text(g, textRenderer, "Their own copy on every monitor" + (picked.isEmpty() ? ": none yet" : ":"), x + 4, y + 4, UiKit.TEXT_2);
        y += 14;
        int ax = x + 4;
        for (String p : picked) {
            String label = AppNames.of(p) + "  ×";
            int bw = textRenderer.getWidth(label) + 16;
            if (ax + bw > right - 4) { ax = x + 4; y += ROW - 4; }
            button(g, mx, my, label, ax + bw, y, 14, UiKit.SEG_ACTIVE, UiKit.DANGER_HOVER, () -> {
                List<String> l = new ArrayList<>(ClientSettings.ownCopyApps());
                l.remove(p);
                dev.windowscraft.monitor.Sandbox.setOwnCopyApps(l);
            });
            ax += bw + 4;
        }
        if (!picked.isEmpty()) y += ROW - 2;
        // what's open now — on your desk, here, on other monitors — to add
        java.util.LinkedHashMap<String, String> apps = new java.util.LinkedHashMap<>(); // exe → name
        for (MonitorBackend.Win win : desk) addApp(apps, win.pid());
        for (List<MonitorBackend.Win> l : elsewhere.values()) for (MonitorBackend.Win win : l) addApp(apps, win.pid());
        for (MonitorBackend.Win win : onIt) addApp(apps, win.pid());
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String p : picked) have.add(fileOf(p));
        // (never a game, a launcher or the game you play in: those always run normally)
        List<Map.Entry<String, String>> addable = apps.entrySet().stream()
                .filter(e -> !have.contains(fileOf(e.getKey())) && !dev.windowscraft.monitor.Sandbox.mustRunNormally(e.getKey())).toList();
        if (!addable.isEmpty()) {
            UiKit.text(g, textRenderer, "Add one that's open:", x + 4, y + 4, UiKit.TEXT_2);
            y += 14;
            ax = x + 4;
            for (var e : addable) {
                String label = "+ " + e.getValue(), exe = e.getKey();
                int bw = textRenderer.getWidth(label) + 16;
                if (ax + bw > right - 4) { ax = x + 4; y += ROW - 4; }
                button(g, mx, my, label, ax + bw, y, 14, UiKit.SURFACE, UiKit.SURFACE_HOVER, () -> {
                    List<String> l = new ArrayList<>(ClientSettings.ownCopyApps());
                    if (l.stream().noneMatch(q -> fileOf(q).equals(fileOf(exe)))) l.add(exe);
                    dev.windowscraft.monitor.Sandbox.setOwnCopyApps(l);
                });
                ax += bw + 4;
            }
            y += ROW - 2;
        }
        y = wrap(g, "Each monitor runs its own copy of these: its own sound, never mixed with another monitor's or your desk's, and its own "
                + "sign-ins, kept (the first time, it starts signed in like your desk). It closes with its monitor. Everything else runs normally: "
                + "games, Steam, Store apps.", x + 4, y + 2, w - 8, UiKit.TEXT_MUTED) + 4;
        if (!copies.isEmpty()) {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (MonitorBackend.Win c : copies) names.add(appName(c.pid()));
            y = wrap(g, "Running as this monitor's own: " + String.join(", ", names), x + 4, y, w - 8, UiKit.TEXT_2) + 4;
        }
        // one more copy of something that's open, here, by hand (a second game, a second Discord…)
        if (!apps.isEmpty()) {
            UiKit.text(g, textRenderer, "Open another copy here:", x + 4, y + 4, UiKit.TEXT_2);
            y += 14;
            ax = x + 4;
            for (var e : apps.entrySet()) {
                int bw = textRenderer.getWidth(e.getValue()) + 16;
                if (ax + bw > right - 4) { ax = x + 4; y += ROW - 4; }
                String exe = e.getKey(), name = e.getValue();
                button(g, mx, my, name, ax + bw, y, 14, UiKit.SURFACE_HOVER, UiKit.ACCENT_HOVER,
                        () -> InGameMonitors.openCopyHere(d, exe).thenAccept(ok -> { copiesNote = ok ? "Opening another " + name + " here…" : "Couldn't open another " + name + " here"; moved(); }));
                ax += bw + 4;
            }
            y += ROW - 2;
        }
        // wipe this monitor's copies (its logins and data)
        boolean armed = System.nanoTime() - resetArmedAt < 4_000_000_000L;
        int bw = button(g, mx, my, armed ? "Click again: wipe its sign-ins and data" : "Reset this monitor's copies", right - 3, y, 14,
                armed ? UiKit.DANGER : UiKit.SEG_ACTIVE, UiKit.DANGER_HOVER, () -> {
                    if (System.nanoTime() - resetArmedAt < 4_000_000_000L) {
                        resetArmedAt = 0;
                        InGameMonitors.resetCopies(d).thenAccept(v -> { copiesNote = "This monitor's copies were reset."; moved(); });
                    } else resetArmedAt = System.nanoTime();
                });
        if (copiesNote != null) UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, copiesNote, w - bw - 12), x + 4, y + 3, UiKit.TEXT_2);
        return y + ROW;
    }

    private static void addApp(java.util.Map<String, String> apps, long pid) {
        String exe = InGameMonitors.exeOf(pid);
        if (exe.isEmpty() || apps.containsKey(exe)) return;
        String f = fileOf(exe);
        if (f.equals("explorer.exe") || f.equals("javaw.exe") || f.equals("java.exe") || f.equals("applicationframehost.exe")) return;
        if (AppNames.storeApp(exe)) return; // (a Store app can't be a monitor's own copy)
        apps.put(exe, appName(pid));
    }

    private static String fileOf(String exe) { return exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).toLowerCase(java.util.Locale.ROOT); }

    /** "WhatsApp", "Windows Terminal": what the program calls itself (see AppNames). */
    private static String appName(long pid) { return AppNames.of(InGameMonitors.exeOf(pid)); }

    /** Is that window's program (the same process) also showing windows on your desk or another monitor? */
    private boolean sharedElsewhere(MonitorBackend.Win win) {
        if (win.pid() <= 0) return false;
        for (MonitorBackend.Win o : desk) if (o.pid() == win.pid()) return true;
        for (List<MonitorBackend.Win> l : elsewhere.values()) for (MonitorBackend.Win o : l) if (o.pid() == win.pid()) return true;
        return false;
    }

    // ---- Who can use it ----------------------------------------------------------------------

    private boolean othersCanJoin() {
        ClientNet.Ack ack = selected == null ? null : ClientNet.ack(selected.id);
        if (ack != null) return ack.relayOpen();
        return ClientNet.streamingAvailable();
    }

    private int renderPeople(DrawContext g, int mx, int my, int x, int y, int w) {
        Display d = selected;
        DisplayManager dm = DisplayManager.get();
        y = wrap(g, "Everyone here can watch it. Choose who else can use its mouse and keyboard — "
                + "each person gets their own pointer, and nobody's mouse moves anyone else's.", x, y + 2, w, UiKit.TEXT_2) + 6;
        segmented(g, mx, my, x, y, new String[]{"Only me", "People I pick", "Everyone except…"}, d.access, a -> dm.setAccess(d, a));
        y += 24;

        MinecraftClient mc = MinecraftClient.getInstance();
        UUID me = mc.player == null ? null : mc.player.getUuid();
        Map<UUID, String> people = new LinkedHashMap<>();
        if (mc.getNetworkHandler() != null)
            for (var e : mc.getNetworkHandler().getPlayerList())
                if (!e.getProfile().getId().equals(me)) people.put(e.getProfile().getId(), e.getProfile().getName());
        List<String> using = dev.windowscraft.client.ScreenControl.guests(d);

        if (d.access == Display.ACCESS_ME) {
            y = wrap(g, "Only you can use it. Everyone else can still watch.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 6;
        } else {
            boolean picking = d.access == Display.ACCESS_PICKED;
            Map<UUID, String> listed = picking ? d.picked : d.blocked;
            for (var e : listed.entrySet()) people.putIfAbsent(e.getKey(), e.getValue()); // (chosen, but not here right now)
            if (people.isEmpty()) {
                y = wrap(g, othersCanJoin() ? "Nobody else is here right now. When friends join, they show up here."
                        : "Nobody else can join this world, so nobody else can see or use it.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 6;
            }
            String[] opts = picking ? new String[]{"Watch only", "Can use it"} : new String[]{"Can use it", "Blocked"};
            int segTotal = segOptionWidth(opts) * 2 + 4;
            for (var e : people.entrySet()) {
                UUID id = e.getKey();
                String name = e.getValue();
                boolean here = mc.getNetworkHandler() != null && mc.getNetworkHandler().getPlayerListEntry(id) != null;
                if (hover(mx, my, x, y - 2, w, ROW)) UiKit.round(g, x - 2, y - 2, w + 4, ROW, 3, UiKit.SURFACE);
                String label = name + (!here ? "  (not here)" : using.contains(name) ? "  · using it now" : "");
                UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, label, w - segTotal - 12), x + 4, y + 4, here ? UiKit.TEXT : UiKit.TEXT_MUTED);
                int sel = picking ? (d.picked.containsKey(id) ? 1 : 0) : (d.blocked.containsKey(id) ? 1 : 0);
                segmented(g, mx, my, x + w - segTotal, y, opts, sel, i -> {
                    if (picking) dm.setController(d, id, name, i == 1);
                    else dm.setBlocked(d, id, name, i == 1);
                });
                y += ROW + 2;
            }
            if (d.controllers.size() >= dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS)
                y = wrap(g, "Up to " + dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS + " people can use one screen.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 4;
        }
        List<Display> all = dm.all();
        if (all.size() > 1) {
            y += 6;
            int bw = button(g, mx, my, "Same for all my screens", x + textRenderer.getWidth("Same for all my screens") + 16, y, 16,
                    UiKit.SURFACE_HOVER, UiKit.SEG_ACTIVE, () -> {
                        for (Display o : all) {
                            if (o == d || o.tablet) continue;
                            o.access = d.access;
                            o.picked.clear();
                            o.picked.putAll(d.picked);
                            o.blocked.clear();
                            o.blocked.putAll(d.blocked);
                            dm.applyAccess(o);
                        }
                        dm.save();
                    });
            UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, "(new walls start with these too)", w - bw - 10), x + bw + 8, y + 4, UiKit.TEXT_MUTED);
            y += 22;
        }
        return y;
    }

    // ---- Picture -----------------------------------------------------------------------------

    /** Of the sizes it can show, the one shaped most like the wall (nearest 1080p among those). */
    static int[] matchWall(List<int[]> modes, double wallW, double wallH) {
        double want = wallW / wallH;
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int[] m : modes) {
            double shape = Math.abs(Math.log((m[0] / (double) m[1]) / want));
            double score = shape * 100 + Math.abs(Math.log(m[1] / 1080.0));
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    private void setMode(int[] m) {
        Display d = selected;
        modeBusy = true;
        modeNote = "Changing to " + m[0] + " × " + m[1] + "…";
        InGameMonitors.setMode(d, m[0], m[1], m[2]).thenAccept(why -> {
            if (modesFor != d) return; // (you went on to another screen meanwhile)
            modeBusy = false;
            modeNote = why == null ? null : "Couldn't change it: " + why;
        });
    }

    private int renderPicture(DrawContext g, int mx, int my, int x, int y, int w) {
        Display d = selected;
        y += 2;
        if (InGameMonitors.supported()) {
            y = heading(g, "IN-GAME MONITOR SIZE", x, y);
            MonitorBackend.Mon mon = InGameMonitors.monitorOf(d);
            List<int[]> ms = modes;
            if (mon == null) {
                int cw = d.monitorW > 0 ? d.monitorW : InGameMonitors.WIDTH, chh = d.monitorW > 0 ? d.monitorH : InGameMonitors.HEIGHT;
                y = wrap(g, "It starts at " + cw + " × " + chh + ". Turn it on to pick another size.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 8;
            } else if (ms == null) {
                y = wrap(g, "Asking Windows which sizes it can show…", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 8;
            } else if (ms.isEmpty()) {
                y = wrap(g, "It's " + mon.width() + " × " + mon.height() + " (Windows offers no other sizes for it).", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 8;
            } else {
                int[] fit = d.tablet ? null : matchWall(ms, d.rect.width(), d.rect.height()); // (a tablet takes the shape of its screen)
                List<String> labels = new ArrayList<>();
                List<int[]> picks = new ArrayList<>();
                if (fit != null) {
                    labels.add((d.tablet ? "Tablet-shaped (" : "Match the wall (") + fit[0] + " × " + fit[1] + ")");
                    picks.add(fit);
                }
                for (int[] m : ms) {
                    labels.add(m[0] + " × " + m[1]);
                    picks.add(m);
                }
                int cx = x, rowY = y;
                for (int i = 0; i < labels.size(); i++) {
                    String l = labels.get(i);
                    int[] m = picks.get(i);
                    int bw = textRenderer.getWidth(l) + 14;
                    if (cx + bw > x + w && cx > x) {
                        cx = x;
                        rowY += 18;
                    }
                    boolean current = (i > 0 || fit == null) && m[0] == mon.width() && m[1] == mon.height();
                    boolean hov = !modeBusy && hover(mx, my, cx, rowY, bw, 15);
                    UiKit.round(g, cx, rowY, bw, 15, 3, current ? UiKit.ACCENT : hov ? UiKit.SURFACE_HOVER : UiKit.SURFACE);
                    UiKit.text(g, textRenderer, l, cx + 7, rowY + 4, current || hov ? UiKit.TEXT : UiKit.TEXT_2);
                    if (!modeBusy && !current) hit(cx, rowY, bw, 15, () -> setMode(m));
                    cx += bw + 4;
                }
                y = rowY + 20;
                String note = modeNote;
                y = wrap(g, note != null ? note : "Its apps and windows stay open. A size shaped like the wall fills it without black bars.",
                        x + 4, y, w - 8, note != null && note.startsWith("Couldn't") ? 0xFFF0B232 : UiKit.TEXT_MUTED) + 8;
            }
        }

        y = heading(g, "PICTURE FOR EVERYONE WATCHING", x, y);
        CaptureSettings s = d.lastSettings != null ? d.lastSettings : ClientSettings.get();
        DisplayManager dm = DisplayManager.get();
        String[] hs = new String[CaptureSettings.HEIGHTS.length];
        int hSel = 0;
        for (int i = 0; i < hs.length; i++) {
            hs[i] = CaptureSettings.heightLabel(CaptureSettings.HEIGHTS[i]);
            if (CaptureSettings.HEIGHTS[i] == s.maxHeight()) hSel = i;
        }
        String[] fs = new String[CaptureSettings.FPS.length];
        int fSel = 0;
        for (int i = 0; i < fs.length; i++) {
            fs[i] = String.valueOf(CaptureSettings.FPS[i]);
            if (CaptureSettings.FPS[i] == s.fps()) fSel = i;
        }
        y = choiceRow(g, mx, my, x, y, w, "Sharpness", "How sharp it looks to other people. It turns itself down for a while if someone's connection can't keep up.",
                hs, hSel, i -> dm.updateSettings(d, new CaptureSettings(CaptureSettings.HEIGHTS[i], s.fps(), false, s.audio(), s.fit(), s.hearInGame())));
        y = choiceRow(g, mx, my, x, y, w, "Pictures a second", "60 is smoothest for videos and games.",
                fs, fSel, i -> dm.updateSettings(d, new CaptureSettings(s.maxHeight(), CaptureSettings.FPS[i], false, s.audio(), s.fit(), s.hearInGame())));
        y = choiceRow(g, mx, my, x, y, w, "Shape on the wall", "Auto fills the wall when the shapes nearly match; Fill always stretches; Fit keeps the shape with black bars.",
                new String[]{"Auto", "Fill", "Fit"}, Math.max(0, Math.min(2, s.fit())),
                i -> dm.updateSettings(d, new CaptureSettings(s.maxHeight(), s.fps(), false, s.audio(), i, s.hearInGame())));
        y = toggleRow(g, mx, my, x, y, w, "Sound", "Its sound plays from the wall for everyone nearby.", s.audio(),
                v -> dm.updateSettings(d, new CaptureSettings(s.maxHeight(), s.fps(), false, v, s.fit(), s.hearInGame())));
        if (s.audio()) {
            y = toggleRow(g, mx, my, x, y, w, "You hear it from the wall too",
                    "On: you hear it in the game, from the wall, like everyone else. Off: you hear it straight from your PC.", s.hearInGame(),
                    v -> dm.updateSettings(d, new CaptureSettings(s.maxHeight(), s.fps(), false, s.audio(), s.fit(), v)));
        }
        if (!d.tablet) {
            y = heading(g, "ON YOUR OWN WALL", x, y + 2);
            boolean sharp = ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN);
            int[] c = d.share() == null ? null : d.share().smallerCopy();
            y = overrideRow(g, mx, my, x, y, w, d, ClientSettings.Auto.SHARP_AS_SEEN, "Copy as sharp as it looks", "Always full size",
                    (sharp ? "Like all screens: a smaller copy for your wall while it looks small from where you are."
                            : "Like all screens: full size (Settings → Performance).")
                            + (c != null ? " Now: " + c[0] + " × " + c[1] + " of its " + c[2] + " × " + c[3] + "." : ""));
            boolean limit = ClientSettings.distanceFpsLimit();
            y = overrideRow(g, mx, my, x, y, w, d, ClientSettings.Auto.DISTANCE_LIMIT, "Distance frame-rate limit", "Never here",
                    limit ? "Like all screens: at most 15 pictures a second while it's far away. \"Never here\": every picture, from anywhere (a screen you watch from afar)."
                            : "Like all screens: off (Settings → Performance).");
        }
        return y;
    }

    // ---- Settings ----------------------------------------------------------------------------

    private void renderSettings(DrawContext g, int mx, int my, int x, int w) {
        UiKit.textScaled(g, textRenderer, "Settings", x, py + 12, 1.3f, UiKit.TEXT);
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, "For all of WindowsCraft, on this PC.", w - 24), x, py + 28, UiKit.TEXT_MUTED);
        int top = py + 44, bottom = py + ph - 8;
        clipTop = top;
        clipBottom = bottom;
        scissor(g, x - 2, top, x + w + 2, bottom);
        int y0 = top - (int) scroll, y = y0;

        // ---- the menu's own size ----
        y = heading(g, "MENU SIZE", x, y);
        double hs = scalePending > 0 ? scalePending : ClientSettings.hubScale(), hmin = 0.6, hmax = 2;
        if (scaleDrag) {
            double hf = Math.max(0, Math.min(1, (mx - scaleX) / (double) scaleW));
            double nv = Math.round((hmin + hf * (hmax - hmin)) * 20) / 20.0;
            if (Math.abs(nv - 1) < 0.04) nv = 1; // (snaps to Minecraft's own size)
            scalePending = hs = nv;
        }
        scaleX = x + 4;
        scaleW = Math.min(200, w - 170);
        int sty = y + 6;
        double shf = (hs - hmin) / (hmax - hmin);
        UiKit.round(g, scaleX, sty, scaleW, 4, 2, UiKit.INSET);
        UiKit.round(g, scaleX, sty, (int) Math.max(4, scaleW * shf), 4, 2, UiKit.ACCENT);
        boolean shov = scaleDrag || hover(mx, my, scaleX - 4, y, scaleW + 8, 16);
        UiKit.round(g, scaleX + (int) (scaleW * shf) - 4, sty - 4, 8, 12, 2, shov ? UiKit.ACCENT_HOVER : UiKit.ACCENT);
        hit(scaleX - 4, y, scaleW + 8, 16, () -> scaleDrag = true);
        String sval = Math.round(hs * 100) + "%" + (hs == 1.0 ? " — Minecraft's GUI scale" : scaleDrag ? " — let go to apply" : "");
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, sval, w - scaleW - 16), scaleX + scaleW + 10, sty - 2, UiKit.TEXT_2);
        y += 20;
        y = wrap(g, "How big this menu is, on top of Minecraft's own GUI scale.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 10;

        // ---- pointer speed ----
        y = heading(g, "POINTER SPEED (SITTING AT A SCREEN)", x, y);
        double v = ClientSettings.pointerSpeed(), min = 0.25, max = 3;
        if (speedDrag) {
            double f = Math.max(0, Math.min(1, (mx - speedX) / (double) speedW));
            double nv = min + f * (max - min);
            nv = Math.abs(nv - 1) < 0.06 ? 1 : Math.round(nv * 20) / 20.0; // snaps to "like your PC"
            if (nv != v) ClientSettings.setPointerSpeed(v = nv);
        }
        speedX = x + 4;
        speedW = Math.min(200, w - 170);
        int ty = y + 6;
        double f = (v - min) / (max - min);
        UiKit.round(g, speedX, ty, speedW, 4, 2, UiKit.INSET);
        UiKit.round(g, speedX, ty, (int) Math.max(4, speedW * f), 4, 2, UiKit.ACCENT);
        int kx = speedX + (int) (speedW * f) - 4;
        boolean hov = speedDrag || hover(mx, my, speedX - 4, y, speedW + 8, 16);
        UiKit.round(g, kx, ty - 4, 8, 12, 2, hov ? UiKit.ACCENT_HOVER : UiKit.ACCENT);
        hit(speedX - 4, y, speedW + 8, 16, () -> speedDrag = true);
        String val = String.format(Locale.ROOT, "%.2f×", v) + (v == 1.0 ? " — like your PC's cursor" : "");
        UiKit.text(g, textRenderer, UiKit.ellipsize(textRenderer, val, w - speedW - 16), speedX + speedW + 10, ty - 2, UiKit.TEXT_2);
        y += 20;
        y = wrap(g, "For walls showing a window. At an in-game monitor your mouse is your real mouse, at your PC's own speed; "
                + "looking around with Alt uses your Minecraft mouse sensitivity.", x + 4, y, w - 8, UiKit.TEXT_MUTED) + 10;

        // ---- performance: Automatic (decided for you, and it says what) or Advanced (your picks) ----
        String[] modes = {"Automatic", "Advanced"};
        boolean adv = ClientSettings.perfAdvanced();
        heading(g, "PERFORMANCE", x, y + 4);
        segmented(g, mx, my, x + w - (segOptionWidth(modes) * modes.length + 4), y, modes, adv ? 1 : 0, i -> ClientSettings.setPerfAdvanced(i == 1));
        y = wrap(g, adv ? "Your own pick for each of these; one left on Automatic is still decided for you. A screen can have its own picks too (its Picture page)."
                        : "WindowsCraft picks these for you and says here what it's doing. Advanced: pick them yourself.",
                x + 4, y + 20, w - 8, UiKit.TEXT_MUTED) + 8;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            boolean on = ClientSettings.gpuSharing();
            Boolean works = dev.windowscraft.render.GpuInterop.state();
            String state = !on ? "Now: off — your wall reads the window with Graphics Capture instead."
                    : works == Boolean.FALSE ? "Not available on this PC: " + dev.windowscraft.render.GpuInterop.reason()
                    : works == null ? "Checking (safely, outside the game) that your graphics driver can do this…"
                    : "Now: running — your wall reads the picture straight from graphics memory.";
            y = perfRow(g, mx, my, x, y, w, ClientSettings.Auto.GPU_SHARING, "Direct GPU sharing",
                    "Your own walls take the picture straight from graphics memory, with no copies through the CPU.", state);
        }
        y = perfRow(g, mx, my, x, y, w, ClientSettings.Auto.OFFSCREEN_PAUSE, "Off-screen pause",
                "Skip the per-frame work whenever a wall isn't actually on your screen.", ClientSettings.offscreenPause() ? pauseNow() : null);
        y = perfRow(g, mx, my, x, y, w, ClientSettings.Auto.DISTANCE_LIMIT, "Distance frame-rate limit",
                "Slow the picture down when you're far away (at most 15 pictures a second).", ClientSettings.distanceFpsLimit() ? farNow() : null);
        y = perfRow(g, mx, my, x, y, w, ClientSettings.Auto.SHARP_AS_SEEN, "Copies as sharp as they look",
                "While one of your screens looks small from where you are, your wall gets a smaller copy of it: a 4K screen across the room "
                        + "doesn't need 4K copies. Full size as you come closer or sit down; what other people see never changes. "
                        + "(Not needed with direct GPU sharing running.)",
                ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN) ? sharpNow() : null);
        y = toggleRow(g, mx, my, x, y, w, "Shader pack on screens",
                (ClientSettings.screensShadedForEveryone() ? "For everyone on this server: flipping it changes every player's view. " : "")
                        + "Off: screens keep their true colours and stay sharp, untouched by shader packs' lighting, fog and blur. "
                        + (dev.windowscraft.render.ShaderCompat.shaderPackInUse() ? "" : "(No shader pack is on right now.)"),
                !ClientSettings.screensWithoutShaders(), shaded -> ClientSettings.setScreensWithoutShaders(!shaded));
        y += 4;

        // ---- in-game monitors ----
        if (InGameMonitors.supported()) y = monitorSection(g, mx, my, x, y, w) + 4;

        // ---- updates ----
        y = heading(g, "UPDATES", x, y);
        Map<String, String> servers = dev.windowscraft.client.Updater.servers();
        if (servers.isEmpty()) {
            y = wrap(g, "When a server you play on has a newer WindowsCraft, it offers it to you in chat; your answer shows up here.",
                    x + 4, y, w - 8, UiKit.TEXT_MUTED) + 8;
        } else {
            for (Map.Entry<String, String> e : servers.entrySet()) {
                String key = e.getKey();
                y = toggleRow(g, mx, my, x, y, w, "From " + key, null, e.getValue().equals("yes"),
                        yes -> dev.windowscraft.client.Updater.setTrusted(key, yes));
            }
            y = wrap(g, "On: when that server has a newer WindowsCraft, it downloads by itself and is put in place when you close the game.",
                    x + 4, y - 4, w - 8, UiKit.TEXT_MUTED) + 8;
        }

        // ---- controls ----
        y = heading(g, "CONTROLS", x, y);
        String k = WindowsCraftClient.keyName(), t = dev.windowscraft.client.TheaterMode.keyName();
        String[] lines = {
                k + " anywhere: this menu. " + k + " at a black concrete wall: turns an in-game monitor on or off. Sneak + " + k
                        + " at a wall: share one of your windows or screens on it instead (or, at an in-game monitor, this menu for it).",
                t + " at a screen: sit down — the mouse and keyboard work it. Esc gets up. Sitting, hold Ctrl+Alt to look around and Ctrl+Alt + scroll to zoom (plain Alt is the app's).",
                "Hold Alt and look at a screen (without sitting): the pointer follows your view; click and scroll to use it.",
                "At an in-game monitor, the Windows key opens its own Start menu.",
        };
        for (String l : lines) y = wrap(g, l, x + 4, y, w - 8, UiKit.TEXT_2) + 4;
        g.disableScissor();
        scrollbar(g, x + w + 1, top, bottom, y + 4 - y0);
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
    }

    /** In-game monitors: whether the driver is there, and a way to remove it or turn every monitor off. */
    private int monitorSection(DrawContext g, int mx, int my, int x, int y, int w) {
        y = heading(g, "IN-GAME MONITORS", x, y);
        y = toggleRow(g, mx, my, x, y, w, "Sit-down view: pixel for pixel",
                "Sitting at your own monitor shows it flat on your screen, exactly like a real monitor: sharp, with nothing in between. "
                        + "Ctrl+Alt + scroll out (or look around with Ctrl+Alt) to see the room.",
                ClientSettings.deskView(), ClientSettings::setDeskView);
        var known = InGameMonitors.knownDriver();
        if (known == null && !driverBusy) {
            driverBusy = true;
            InGameMonitors.driver().whenComplete((d, e) -> driverBusy = false);
        }
        int live = InGameMonitors.all().size();
        String state = driverText != null ? driverText : known == null ? "Checking the display driver…" : switch (known) {
            case OK -> "Display driver installed. " + (live == 0 ? "No in-game monitors are on." : live == 1 ? "1 in-game monitor is on." : live + " in-game monitors are on.");
            case MISSING -> "Not set up yet: press " + WindowsCraftClient.keyName() + " at a black concrete wall to install the free display driver.";
            case RESTART -> "The display driver needs a PC restart before it works.";
            case DISABLED -> "The display driver is turned off in Device Manager.";
            default -> "The display driver isn't working right now.";
        };
        List<String> buttons = new ArrayList<>();
        if (live > 0) buttons.add("Turn all off");
        if (known != null && known != MonitorBackend.Driver.MISSING && !driverBusy) buttons.add("Remove driver");
        int bw = 0;
        for (String b : buttons) bw += textRenderer.getWidth(b) + 16 + 4;
        int ty = wrap(g, state, x + 4, y + 4, w - bw - 12, UiKit.TEXT_2);
        int bx = x + w;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            String b = buttons.get(i);
            boolean danger = b.equals("Remove driver");
            bx -= button(g, mx, my, b, bx, y, 16, danger ? UiKit.DANGER : UiKit.SURFACE, danger ? UiKit.DANGER_HOVER : UiKit.SURFACE_HOVER,
                    danger ? this::removeDriver : InGameMonitors::turnOffAll) + 4;
        }
        return Math.max(ty, y + 16) + 8;
    }

    private void removeDriver() {
        driverBusy = true;
        driverText = "Removing the display driver — Windows will ask for permission…";
        MinecraftClient mc = MinecraftClient.getInstance();
        InGameMonitors.removeDriver().whenComplete((r, e) -> mc.execute(() -> {
            driverBusy = false;
            driverText = e != null ? "Couldn't remove the driver: " + e.getMessage()
                    : switch (r) {
                        case "ok" -> "The display driver was removed.";
                        case "cancelled" -> "Not removed (the permission request was declined).";
                        case "missing" -> "The display driver isn't installed.";
                        default -> "Couldn't remove the driver (" + r + ").";
                    };
            InGameMonitors.driver();
        }));
    }

    // ---- input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double rmx, double rmy, int button) {
        double mx = rmx / k, my = rmy / k;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (UiKit.inside(mx, my, h.x, h.y, h.w, h.h)) {
                    MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.35f));
                    h.action.run();
                    return true;
                }
            }
            if (!UiKit.inside(mx, my, px, py, pw, ph)) {
                close();
                return true;
            }
        }
        return super.mouseClicked(rmx, rmy, button);
    }

    private final VolumeSlider volSlider = new VolumeSlider();

    private static void setScreenVolume(dev.windowscraft.display.Share share, double v) {
        v = Math.max(0, Math.min(VolumeSlider.MAX, v));
        if (share != null) share.localVolume = v;
        ClientSettings.setOwnVolume(v);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        speedDrag = false;
        if (scaleDrag) {
            scaleDrag = false;
            if (scalePending > 0) ClientSettings.setHubScale(scalePending);
            scalePending = -1;
            layout();
        }
        volSlider.stopDrag();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double rmx, double rmy, double h, double v) {
        double mx = rmx / k;
        if (mx < px + side) sideScroll = Math.max(0, sideScroll - v * 16);
        else scroll = Math.max(0, scroll - v * 16);
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (renaming != null) {
            typeName(String.valueOf(chr));
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    private void typeName(String t) {
        for (char c : t.toCharArray()) if (c >= 32 && c != 127 && renameText.length() < 32) renameText += c;
    }

    /** The name typed becomes its name (remembered with its settings); empty: its usual name again. */
    private void finishRename() {
        Display d = renaming;
        renaming = null;
        if (d == null) return;
        String n = renameText.strip();
        d.name = n.isEmpty() ? null : n;
        DisplayManager.get().save();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renaming != null) {
            // (typing a name: every key is the name's, Esc included)
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) finishRename();
            else if (keyCode == GLFW.GLFW_KEY_ESCAPE) renaming = null;
            else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !renameText.isEmpty()) renameText = renameText.substring(0, renameText.offsetByCodePoints(renameText.length(), -1));
            else if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) typeName(MinecraftClient.getInstance().keyboard.getClipboard());
            return true;
        }
        if (WindowsCraftClient.SHARE_KEY.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !settingsPage && selected != null) {
            List<Integer> ids = new ArrayList<>();
            if (InGameMonitors.isOn(selected)) ids.add(TAB_WINDOWS);
            if (!selected.tablet) ids.add(TAB_PEOPLE);
            ids.add(TAB_PICTURE);
            int i = ids.indexOf(tab);
            tab = ids.get((i + 1) % ids.size());
            scroll = 0;
            refresh();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
