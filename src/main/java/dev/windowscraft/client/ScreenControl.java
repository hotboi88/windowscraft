package dev.windowscraft.client;

import dev.windowscraft.apps.InputBridge;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.capture.win.WinGeom;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.net.Payloads;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Mouse and keyboard for what you're sharing on your own walls.
 *
 * <p>Walking around: hold Alt and the wall's pointer follows your view; Alt + click and Alt +
 * wheel use it (a plain click on the wall does nothing, and never mines it; sneak to build).
 * Theater mode on your own wall is the chair: the mouse moves the pointer like your desktop
 * cursor, every key goes to the app, Alt + mouse looks around, Alt + wheel zooms, Esc gets up.
 * The pointer is only drawn while you're pointing with Alt or sitting at the wall.
 *
 * <p>Input reaches the shared window as the messages Windows would send it (wc-input); the
 * window itself never moves. Pointers are kept in the app's own coordinates (0..1 across its
 * client area, or across the monitor), so every picture of it — your wall, the stream, which may
 * show the title bar too — puts the pointer on the same spot.
 *
 * <p>Someone else's screen works exactly the same way, but only if its owner let you (per screen,
 * in the share menu's Settings). Your input travels to the owner through the server, which
 * passes it on only for players the owner allowed; the owner's game checks again before it
 * touches anything. Everyone sees the screen's pointer while someone points at it or sits at it.
 */
public final class ScreenControl {
    private static final double REACH = 48;
    private static Display pointed;
    /** Someone else's wall under your view that they let you use, or null. */
    private static RemoteDisplays.Remote pointedRemote;
    /** Pointer per wall, in the picture's own 0..1 coordinates. */
    private static final Map<UUID, double[]> POINTER = new HashMap<>();
    private static long target;
    private static int buttons;
    private static Display pressedOn;
    private static WinGeom.At pressAt;
    private static double lastX = -1, lastY = -1;
    private static boolean hovering;
    /** Monitor shares: the window under the pointer (looked up off the game's thread). */
    private static volatile WinGeom.At under;
    private static long underAsked;

    // ---- which wall, where -------------------------------------------------------------------

    /** Tests: a test pattern counts as something you can use (its input goes to the test sink). */
    public static volatile boolean testControlPattern;

    /** Your own wall showing something you can use (a window or a monitor). */
    public static boolean controllable(Display d) {
        if (d == null || !d.isLive() || d.share() == null) return false;
        SourceInfo.Kind k = d.share().source().kind();
        return k == SourceInfo.Kind.WINDOW || k == SourceInfo.Kind.SCREEN || (testControlPattern && k == SourceInfo.Kind.TEST)
                || dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share().source());
    }

    /** Someone else's wall that its owner lets you use, with a picture on it. */
    public static boolean controllable(RemoteDisplays.Remote r) {
        return r != null && r.wallOk && RemoteDisplays.all().contains(r) && r.controllableByMe() && r.textureWidth() > 0;
    }

    /** The wall under your view right now (while walking around), or null. */
    public static Display pointed() { return pointed; }
    public static RemoteDisplays.Remote pointedRemote() { return pointedRemote; }

    /** Sitting at a screen, the pointer hides after this long without moving the mouse (like a video player). */
    static final long IDLE_NANOS = 2_500_000_000L;
    /** Last time you moved the mouse, clicked or scrolled while sitting. */
    private static long lastActivity;

    private static void active() { lastActivity = System.nanoTime(); }

    /** Tests: pretend the mouse hasn't moved for a while. */
    public static void testIdle() { lastActivity = System.nanoTime() - IDLE_NANOS - 1; }

    private static boolean idle() { return System.nanoTime() - lastActivity > IDLE_NANOS; }

    /**
     * Is the pointer drawn on this wall? Only while someone points at it with Alt, or sits at it
     * and has moved the mouse in the last couple of seconds.
     */
    public static boolean pointerVisible(Display d) {
        if (d == null) return false;
        // (on an in-game monitor, the people you allowed have pointers of their own: guestPointers)
        if (d.share() != null && dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share().source())) {
            if (realSeat(d)) return dev.windowscraft.capture.win.RealCursor.current() != null;
            if (TheaterMode.controlled() == d) return !idle();
            MinecraftClient mc = MinecraftClient.getInstance();
            return pointed == d && mc.currentScreen == null && altHeld(mc);
        }
        if (realSeat(d)) return dev.windowscraft.capture.win.RealCursor.current() != null; // your real pointer (hidden when an app hides it)
        if (TheaterMode.controlled() == d) return !idle() || guestActive(d);
        MinecraftClient mc = MinecraftClient.getInstance();
        return (pointed == d && mc.currentScreen == null && altHeld(mc)) || guestActive(d);
    }

    /** Someone else's wall: yours while you use it, otherwise wherever its owner says it is. */
    public static boolean pointerVisible(RemoteDisplays.Remote r) {
        if (r == null) return false;
        if (TheaterMode.controlledRemote() == r) return !idle();
        return usingRemote(r) || RemoteDisplays.pointerShown(r);
    }

    /** The pointer on that wall (0..1 across and down the picture). */
    public static double[] pointer(Display d) { return POINTER.computeIfAbsent(d.id, k -> new double[]{0.5, 0.5}); }

    public static double[] pointer(RemoteDisplays.Remote r) {
        if (usingRemote(r)) return mine(r);
        return new double[]{r.pointerX, r.pointerY};
    }

    /** Your own pointer on someone else's wall (starts where theirs is). */
    private static double[] mine(RemoteDisplays.Remote r) {
        return POINTER.computeIfAbsent(r.id, k -> new double[]{r.pointerX, r.pointerY});
    }

    /** Are you using that remote wall right now (pointing with Alt, or sitting at it)? */
    private static boolean usingRemote(RemoteDisplays.Remote r) {
        if (r == null) return false;
        if (TheaterMode.controlledRemote() == r) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        return pointedRemote == r && mc.currentScreen == null && altHeld(mc);
    }

    /** Where the app's client area is in your wall's picture (0..1 across and down it). */
    public static Box box(Display d) { return d.share() == null ? Box.FULL : d.share().wallBox(); }

    public static Box box(RemoteDisplays.Remote r) { return r.box(); }

    /** The pointer as a spot of that wall's picture (0..1), for drawing it. */
    public static double[] pictureSpot(Display d) { return box(d).toPicture(pointer(d)); }

    public static double[] pictureSpot(RemoteDisplays.Remote r) { return box(r).toPicture(pointer(r)); }

    /** The picture's corners on the wall (letterboxed as it's shown): TL, TR, BR, BL. */
    public static Vec3d[] pictureCorners(Display d) { return pictureCorners(d.rect, d.textureWidth(), d.textureHeight(), d.fit()); }
    public static Vec3d[] pictureCorners(RemoteDisplays.Remote r) { return pictureCorners(r.rect, r.textureWidth(), r.textureHeight(), r.fit()); }

    private static Vec3d[] pictureCorners(WallRect rect, int cw, int ch, int fit) {
        if (cw <= 0 || ch <= 0) return rect.corners(1, 1);
        boolean fill = CaptureSettings.shouldFill(fit, cw, ch, rect.width(), rect.height());
        return fill ? rect.corners(1, 1) : rect.corners(cw, ch);
    }

    /** Each frame: which wall is under your view; with Alt held its pointer follows your view. */
    public static void frame(MinecraftClient mc) {
        Display real = dev.windowscraft.monitor.InGameMonitors.seated();
        if (real != null) {
            double[] at = dev.windowscraft.monitor.InGameMonitors.pointerOnSeat();
            double[] p = pointer(real);
            if (at != null && (Math.abs(at[0] - p[0]) > 1e-6 || Math.abs(at[1] - p[1]) > 1e-6)) {
                p[0] = Math.max(0, Math.min(1, at[0]));
                p[1] = Math.max(0, Math.min(1, at[1]));
                active();
            }
        }
        Display before = pointed;
        pointed = null;
        pointedRemote = null;
        if (mc.world == null || mc.player == null) { stopUsingRemote(); return; }
        Display seat = TheaterMode.controlled();
        RemoteDisplays.Remote seatR = TheaterMode.controlledRemote();
        if (seat != null) {
            pointed = seat;
        } else if (seatR != null) {
            pointedRemote = seatR;
        } else if (mc.currentScreen == null && !onOwnTablet(mc)) {
            Camera cam = mc.gameRenderer.getCamera();
            Vector3f f = cam.getHorizontalPlane();
            Vec3d from = cam.getPos(), dir = new Vec3d(f.x(), f.y(), f.z());
            String dim = mc.world.getRegistryKey().getValue().toString();
            double best = REACH;
            double[] bestUv = null;
            for (Display d : DisplayManager.get().all()) {
                if (d.tablet || !d.dimension.equals(dim) || !controllable(d)) continue;
                double[] hit = intersect(pictureCorners(d), from, dir);
                if (hit != null && hit[2] < best) { best = hit[2]; pointed = d; bestUv = hit; }
            }
            for (RemoteDisplays.Remote r : RemoteDisplays.all()) {
                if (!r.dimension().equals(dim) || !controllable(r)) continue;
                double[] hit = intersect(pictureCorners(r), from, dir);
                if (hit != null && hit[2] < best) { best = hit[2]; pointed = null; pointedRemote = r; bestUv = hit; }
            }
            if (bestUv != null) {
                var res = mc.world.raycast(new RaycastContext(from, from.add(dir.multiply(Math.max(0, best - 0.05))),
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.getCameraEntity()));
                WallRect own = pointed != null ? pointed.rect : pointedRemote.rect;
                // (a monitor's own body — or a joined neighbour's, which its panel may reach into — isn't in the way)
                boolean ownBody = res instanceof net.minecraft.util.hit.BlockHitResult bh && own.isMonitor()
                        && (bh.getBlockPos().equals(own.min()) || (bh.getBlockPos().getManhattanDistance(own.min()) <= 2
                        && mc.world.getBlockState(bh.getBlockPos()).getBlock() instanceof dev.windowscraft.block.MonitorBlock));
                if (res.getType() != HitResult.Type.MISS && !ownBody) { pointed = null; pointedRemote = null; } // something's in the way
                else if (altHeld(mc)) {
                    if (pointed != null) {
                        double[] c = box(pointed).toClient(bestUv[0], bestUv[1]);
                        pointTo(pointed, c[0], c[1]);
                    } else {
                        double[] c = box(pointedRemote).toClient(bestUv[0], bestUv[1]);
                        pointToRemote(pointedRemote, c[0], c[1]);
                    }
                }
            }
        }
        ownerPointing(mc, seat, seatR);
        if (before != pointed || (pointed != null && seat == null && !altHeld(mc))) endHover();
        // someone else's wall: tell its owner where the pointer is, and when you stop pointing
        RemoteDisplays.Remote now = usingRemote(pointedRemote) ? pointedRemote : null;
        if (usingR != null && usingR != now) stopUsingRemote();
        if (now != null) {
            usingR = now;
            flushRemote(now, false);
        }
        broadcastPointers();
    }
    /** {u, v, distance} where the ray meets the quad TL, TR, BR, BL from its front, or null. */
    static double[] intersect(Vec3d[] c, Vec3d from, Vec3d dir) {
        Vec3d tl = c[0], right = c[1].subtract(c[0]), down = c[3].subtract(c[0]);
        Vec3d n = right.crossProduct(down);
        double denom = dir.dotProduct(n);
        if (denom <= 1e-9) return null;
        double t = tl.subtract(from).dotProduct(n) / denom;
        if (t <= 0) return null;
        Vec3d p = from.add(dir.multiply(t)).subtract(tl);
        double u = p.dotProduct(right) / right.lengthSquared(), v = p.dotProduct(down) / down.lengthSquared();
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new double[]{u, v, t};
    }

    private static void pointTo(Display d, double u, double v) {
        double[] p = pointer(d);
        u = Math.max(0, Math.min(1, u));
        v = Math.max(0, Math.min(1, v));
        if (u == p[0] && v == p[1]) return;
        p[0] = u;
        p[1] = v;
        if (realSeat(d)) return; // your real mouse is on that monitor: the pointer just shows where it is
        sendMove(d);
    }

    /**
     * Your real Windows cursor is on this in-game monitor (you sit at it, or you or someone you
     * allowed points at it): real input works it, nothing is sent the old way.
     */
    private static boolean realSeat(Display d) {
        return dev.windowscraft.monitor.InGameMonitors.realCursorOn(d);
    }

    /** Is anyone you allowed using that wall right now? */
    public static boolean hasGuests(Display d) {
        Map<UUID, Guest> g = d == null ? null : GUESTS.get(d.id);
        return g != null && !g.isEmpty();
    }

    private static double[] ownerSent;

    /**
     * Alt-pointing at your own in-game monitor: your real cursor goes there (real hovering,
     * clicks and scrolling, your real cursor on the wall) — just like sitting, minus the keyboard.
     */
    private static void ownerPointing(MinecraftClient mc, Display seat, RemoteDisplays.Remote seatR) {
        Display d = pointed;
        boolean mine = seat == null && seatR == null && d != null && mc.currentScreen == null && altHeld(mc)
                && d.share() != null && dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share().source());
        if (mine) {
            double[] p = pointer(d);
            if (ownerSent == null || ownerSent[0] != p[0] || ownerSent[1] != p[1] || !dev.windowscraft.monitor.InGameMonitors.ownerPointing()) {
                ownerSent = new double[]{p[0], p[1]};
                dev.windowscraft.monitor.InGameMonitors.ownerPoint(d, p[0], p[1]);
            }
        } else if (dev.windowscraft.monitor.InGameMonitors.ownerPointing()) {
            ownerSent = null;
            dev.windowscraft.monitor.InGameMonitors.ownerStopPointing();
        }
    }

    // ---- someone else's wall: input travels to its owner ------------------------------------

    /** The remote wall you're using (pointing at with Alt, or sitting at), or null. */
    private static RemoteDisplays.Remote usingR;
    private static boolean remoteDirty;
    private static long remoteSentAt, remoteBeatAt;
    private static int remoteButtons;
    private static RemoteDisplays.Remote remotePressedOn;

    private static void pointToRemote(RemoteDisplays.Remote r, double u, double v) {
        double[] p = mine(r);
        u = Math.max(0, Math.min(1, u));
        v = Math.max(0, Math.min(1, v));
        if (u == p[0] && v == p[1]) return;
        p[0] = u;
        p[1] = v;
        remoteDirty = true;
    }

    /** Sends the pointer (at most ~60 times a second) and a heartbeat (so the owner knows you're still there). */
    private static void flushRemote(RemoteDisplays.Remote r, boolean force) {
        long now = System.nanoTime();
        double[] p = mine(r);
        if (remoteDirty && (force || now - remoteSentAt >= 15_000_000L)) {
            remoteDirty = false;
            remoteSentAt = remoteBeatAt = now;
            ClientNet.control(r.id, Payloads.OP_MOVE, p[0], p[1], 0, 0);
        } else if (now - remoteBeatAt > 1_000_000_000L) {
            remoteBeatAt = now;
            ClientNet.control(r.id, Payloads.OP_POINT, p[0], p[1], 0, 0);
        }
    }

    private static void sendRemote(RemoteDisplays.Remote r, int op, int a, double f) {
        flushRemote(r, true);
        double[] p = mine(r);
        remoteSentAt = remoteBeatAt = System.nanoTime();
        ClientNet.control(r.id, op, p[0], p[1], a, f);
    }

    /** Stopped pointing at someone else's wall: its pointer can hide. */
    private static void stopUsingRemote() {
        RemoteDisplays.Remote r = usingR;
        usingR = null;
        if (r == null || TheaterMode.controlledRemote() == r) return;
        if (remoteButtons != 0 && remotePressedOn == r) return; // still holding a button: it lets go first
        double[] p = mine(r);
        r.pointerX = p[0]; // where you left it, until the owner says otherwise
        r.pointerY = p[1];
        r.pointerShown = false;
        ClientNet.control(r.id, Payloads.OP_UNPOINT, p[0], p[1], 0, 0);
        POINTER.remove(r.id);
    }

    // ---- your walls, used by people you allowed --------------------------------------------

    private static final class Guest {
        final String name;
        boolean seated;
        /** Their own pointer on an in-game monitor (0..1), the window under it, and where their press went. */
        double x = 0.5, y = 0.5;
        volatile WinGeom.At under;
        WinGeom.At pressedAt;
        int buttons;
        long lookedUp;
        long seen = System.nanoTime();
        long active = System.nanoTime();
        /** Their own input in the helper (target window, held keys and buttons): never shared with anyone's. */
        final int slot = ++slots;
        long target;
        Guest(String name) { this.name = name; }
        void send(String cmd) { InputBridge.send("slot " + slot + " " + cmd); }
    }

    /** Per wall of yours: the allowed players using it right now. */
    private static final Map<UUID, Map<UUID, Guest>> GUESTS = new HashMap<>();
    private static final Map<UUID, Long> ANNOUNCED = new HashMap<>();

    private static boolean guestActive(Display d) {
        Map<UUID, Guest> g = GUESTS.get(d.id);
        if (g == null) return false;
        long now = System.nanoTime();
        for (Guest x : g.values()) if (!x.seated || now - x.active < IDLE_NANOS) return true;
        return false;
    }

    /** Names of the people using that wall of yours right now. */
    public static java.util.List<String> guests(Display d) {
        Map<UUID, Guest> g = GUESTS.get(d.id);
        return g == null ? java.util.List.of() : g.values().stream().map(x -> x.name).toList();
    }

    /** A player used one of your walls (the server only passes this on if you allowed them; checked again here). */
    public static void fromGuest(MinecraftClient mc, Payloads.ControlFrom m) {
        Payloads.Control c = m.c();
        Display d = DisplayManager.get().byId(c.displayId());
        if (d == null || !controllable(d) || !d.controllers.containsKey(m.from())) return;
        Map<UUID, Guest> guests = GUESTS.computeIfAbsent(d.id, k -> new HashMap<>());
        Guest g = guests.get(m.from());
        if (c.op() == Payloads.OP_UNPOINT || c.op() == Payloads.OP_STAND) {
            if (g == null) return;
            if (c.op() == Payloads.OP_STAND || !g.seated) dropGuest(d, m.from());
            return;
        }
        if (g == null) guests.put(m.from(), g = new Guest(m.fromName()));
        // "… is using your screen" only once they really do something with it (a click, a key,
        // sitting down) — never for just looking at it or pointing past it
        boolean doing = c.op() == Payloads.OP_BUTTON_DOWN || c.op() == Payloads.OP_WHEEL || c.op() == Payloads.OP_SIT || c.op() == Payloads.OP_KEY_DOWN;
        if (doing) {
            Long last = ANNOUNCED.get(m.from());
            if (mc.player != null && (last == null || System.nanoTime() - last > 60_000_000_000L)) {
                ANNOUNCED.put(m.from(), System.nanoTime());
                mc.player.sendMessage(Text.literal(m.fromName() + " is using your screen").formatted(Formatting.GRAY), true);
            }
        }
        g.seen = System.nanoTime();
        if (c.op() != Payloads.OP_POINT && c.op() != Payloads.OP_KEY_DOWN && c.op() != Payloads.OP_KEY_UP) g.active = g.seen;
        double x = Math.max(0, Math.min(1, c.x())), y = Math.max(0, Math.min(1, c.y()));
        if (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share().source())) {
            guestOnMonitor(d, g, c, x, y);
            return;
        }
        switch (c.op()) {
            case Payloads.OP_MOVE, Payloads.OP_POINT -> pointTo(d, x, y);
            case Payloads.OP_BUTTON_DOWN, Payloads.OP_BUTTON_UP -> {
                if (c.a() < 0 || c.a() > 4) return;
                pointTo(d, x, y);
                button(d, c.a(), c.op() == Payloads.OP_BUTTON_DOWN, g.seated);
            }
            case Payloads.OP_WHEEL -> {
                pointTo(d, x, y);
                wheel(d, Math.max(-50, Math.min(50, c.f())));
            }
            case Payloads.OP_KEY_DOWN, Payloads.OP_KEY_UP -> {
                if (!g.seated || c.a() <= 0 || c.a() > 0x1FF) return; // keys only from the chair
                key(d, c.a(), c.op() == Payloads.OP_KEY_DOWN);
            }
            case Payloads.OP_SIT -> {
                g.seated = true;
                pointTo(d, x, y);
                sitDown(d);
            }
            default -> {}
        }
    }

    /**
     * Someone you allowed uses your in-game monitor: with their own pointer, as the window
     * messages Windows would send the app under it — never your real mouse or keyboard. Each of
     * you works your own monitor (or the same one) without getting in the other's way. (Games
     * that read the mouse and keyboard hardware directly only answer to the real ones.)
     */
    private static void guestOnMonitor(Display d, Guest g, Payloads.Control c, double x, double y) {
        if (c.op() == Payloads.OP_KEY_DOWN || c.op() == Payloads.OP_KEY_UP) {
            if (!g.seated || c.a() <= 0 || c.a() > 0x1FF || g.target == 0) return; // keys only from the chair, to what they clicked
            g.send((c.op() == Payloads.OP_KEY_DOWN ? "key down " : "key up ") + c.a());
            return;
        }
        if (c.op() == Payloads.OP_SIT) g.seated = true;
        g.x = x;
        g.y = y;
        switch (c.op()) {
            case Payloads.OP_BUTTON_DOWN, Payloads.OP_BUTTON_UP -> {
                if (c.a() < 0 || c.a() > 4) return;
                String b = switch (c.a()) { case 0 -> "l"; case 1 -> "r"; case 2 -> "m"; case 3 -> "x1"; default -> "x2"; };
                boolean down = c.op() == Payloads.OP_BUTTON_DOWN;
                // a press goes to the window really under it (looked up now); its release to the same one
                WinGeom.At at = down && g.buttons == 0 ? lookUpNow(d, x, y) : (g.pressedAt != null ? g.pressedAt : g.under);
                if (at == null) return;
                double[] n = clientSpot(d, at, x, y);
                guestAim(g, at.handle());
                if (down) {
                    if (g.buttons == 0) dev.windowscraft.audio.SoundHome.used(at.handle()); // (its sound comes from this monitor now)
                    g.buttons |= 1 << c.a();
                    g.pressedAt = at;
                } else {
                    g.buttons &= ~(1 << c.a());
                    if (g.buttons == 0) g.pressedAt = null;
                }
                g.send(String.format(Locale.ROOT, "button %s %s %.5f %.5f", b, down ? "down" : "up", n[0], n[1]));
            }
            case Payloads.OP_WHEEL -> {
                WinGeom.At at = g.under != null ? g.under : lookUpNow(d, x, y);
                if (at == null) return;
                double[] n = clientSpot(d, at, x, y);
                guestAim(g, at.handle());
                g.send(String.format(Locale.ROOT, "wheel %.3f 0 %.5f %.5f", Math.max(-50, Math.min(50, c.f())), n[0], n[1]));
            }
            case Payloads.OP_MOVE, Payloads.OP_POINT, Payloads.OP_SIT -> {
                lookUpSoon(d, g);
                WinGeom.At at = g.buttons != 0 && g.pressedAt != null ? g.pressedAt : g.under;
                if (at == null) return;
                double[] n = clientSpot(d, at, x, y);
                guestAim(g, at.handle());
                g.send(String.format(Locale.ROOT, "move %.5f %.5f", n[0], n[1]));
            }
            default -> {}
        }
    }

    /** Their pointers on that wall (picture coordinates), for drawing: only for in-game monitors. */
    public static java.util.List<double[]> guestPointers(Display d) {
        Map<UUID, Guest> gs = GUESTS.get(d.id);
        if (gs == null || d.share() == null || !dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share().source())) return java.util.List.of();
        java.util.List<double[]> out = new java.util.ArrayList<>();
        long now = System.nanoTime();
        for (Guest g : gs.values()) if (!g.seated || now - g.active < IDLE_NANOS) out.add(box(d).toPicture(new double[]{g.x, g.y}));
        return out;
    }

    /** The desktop point of (x, y) on that in-game monitor (where Windows has it now). */
    private static int[] desktopPoint(Display d, double x, double y) {
        var m = dev.windowscraft.monitor.InGameMonitors.monitorOf(d);
        SourceInfo s = d.share().source();
        int left = m != null ? m.left() : s.left(), top = m != null ? m.top() : s.top();
        int w = m != null ? m.width() : s.width(), h = m != null ? m.height() : s.height();
        return new int[]{left + (int) Math.floor(x * (w - 1)), top + (int) Math.floor(y * (h - 1))};
    }

    private static WinGeom.At lookUpNow(Display d, double x, double y) {
        int[] p = desktopPoint(d, x, y);
        try {
            return WinGeom.windowAt(p[0], p[1]).get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static void lookUpSoon(Display d, Guest g) {
        long now = System.nanoTime();
        if (now - g.lookedUp < 50_000_000L) return;
        g.lookedUp = now;
        int[] p = desktopPoint(d, g.x, g.y);
        WinGeom.windowAt(p[0], p[1]).thenAccept(a -> g.under = a);
    }

    /** (x, y) of the monitor as a spot in that window's client area (0..1). */
    private static double[] clientSpot(Display d, WinGeom.At at, double x, double y) {
        int[] p = desktopPoint(d, x, y);
        return new double[]{(p[0] - at.clientX()) / (double) at.clientW(), (p[1] - at.clientY()) / (double) at.clientH()};
    }

    /** Numbers for guests' own input slots in the helper. */
    private static int slots;

    /** That guest's input goes to that window from now on (their own; nobody else's changes). */
    private static void guestAim(Guest g, long hwnd) {
        if (hwnd == 0 || g.target == hwnd) return;
        if (g.target != 0) g.send("release");
        g.send("target " + Long.toUnsignedString(hwnd));
        g.target = hwnd;
        if (g.seated) g.send("focus");
    }


    /** That player stopped using your wall (or isn't allowed any more): let go of anything they held. */
    public static void dropGuest(Display d, UUID player) {
        Map<UUID, Guest> guests = GUESTS.get(d.id);
        Guest g = guests == null ? null : guests.remove(player);
        if (g == null) return;
        if (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(d.share() == null ? null : d.share().source())) {
            // anything they still held in the app goes up (even while you sit there yourself)
            if (g.target != 0) g.send("blur"); // (lets go of everything first)
            g.send("end");
            if (guests.isEmpty()) GUESTS.remove(d.id);
            return;
        }
        if (guests.isEmpty()) {
            GUESTS.remove(d.id);
            dev.windowscraft.monitor.InGameMonitors.guestsGone(d);
        }
        if (TheaterMode.controlled() == d) return; // you're sitting there yourself
        if (g.seated || pressedOn == d) {
            if (target != 0 || testControlPattern) {
                InputBridge.send("release");
                if (g.seated) InputBridge.send("blur");
            }
            buttons = 0;
            pressedOn = null;
            pressAt = null;
        }
        if (!pointerVisible(d)) endHover();
    }

    private static void tickGuests() {
        if (GUESTS.isEmpty()) return;
        long now = System.nanoTime();
        for (UUID id : new java.util.ArrayList<>(GUESTS.keySet())) {
            Display d = DisplayManager.get().byId(id);
            Map<UUID, Guest> guests = GUESTS.get(id);
            if (guests == null) continue;
            for (UUID who : new java.util.ArrayList<>(guests.keySet())) {
                boolean gone = d == null || !controllable(d) || !d.controllers.containsKey(who) || now - guests.get(who).seen > 3_000_000_000L;
                if (gone) {
                    if (d != null) dropGuest(d, who);
                    else guests.remove(who);
                }
            }
            if (guests.isEmpty()) GUESTS.remove(id);
        }
    }

    // ---- everyone sees your screen's pointer --------------------------------------------------

    private static final Map<UUID, double[]> SENT_POINTER = new HashMap<>(); // {visible, x, y, nanos}

    private static void broadcastPointers() {
        if (!ClientNet.streamingAvailable()) return;
        long now = System.nanoTime();
        for (Display d : DisplayManager.get().all()) {
            if (d.publisher() == null) continue;
            boolean vis = controllable(d) && pointerVisible(d) && !realSeat(d); // (the stream shows your real pointer itself)
            double[] p = pointer(d);
            double[] last = SENT_POINTER.get(d.id);
            boolean wasVis = last != null && last[0] != 0;
            if (!vis && !wasVis) continue;
            double age = last == null ? Double.MAX_VALUE : now - last[3];
            boolean moved = last == null || last[1] != p[0] || last[2] != p[1];
            if (vis == wasVis && !(vis && moved && age >= 33_000_000L) && age < 1_000_000_000L) continue;
            SENT_POINTER.put(d.id, new double[]{vis ? 1 : 0, p[0], p[1], now});
            ClientNet.pointer(d.id, vis, p[0], p[1]);
        }
    }

    // ---- where input goes --------------------------------------------------------------------

    /** Where input goes: a window, and the point in its client area (0..1). */
    private record Aim(long hwnd, double nx, double ny) {}

    /** The window at the pointer (a monitor share: whatever window is at that spot of it), or null. */
    private static Aim aimFor(Display d) {
        SourceInfo s = d.share().source();
        double[] p = pointer(d);
        if (s.kind() != SourceInfo.Kind.SCREEN) return new Aim(s.handle(), p[0], p[1]); // a window: the pointer is in its client area
        int sx = s.left() + (int) Math.floor(p[0] * s.width()), sy = s.top() + (int) Math.floor(p[1] * s.height());
        WinGeom.At w = pressedOn == d && pressAt != null ? pressAt : lookUp(sx, sy);
        if (w == null) return null;
        return new Aim(w.handle(), (sx - w.clientX()) / (double) w.clientW(), (sy - w.clientY()) / (double) w.clientH());
    }

    /** The window at a desktop point, looked up in the background (a few times a second). */
    private static WinGeom.At lookUp(int sx, int sy) {
        long now = System.nanoTime();
        if (now - underAsked > 60_000_000L) {
            underAsked = now;
            WinGeom.windowAt(sx, sy).thenAccept(a -> under = a);
        }
        return under;
    }

    private static void aim(long hwnd) {
        if (hwnd == 0) return;
        if (target != hwnd) {
            if (target != 0) InputBridge.send("release");
            InputBridge.send("target " + Long.toUnsignedString(hwnd));
            target = hwnd;
            lastX = lastY = -1;
            if (TheaterMode.controlled() != null) InputBridge.send("focus");
        }
    }

    // ---- while you use a shared window, it sits just off your monitor --------------------------
    // Chrome-based apps (Brave, Chrome, Discord…) only scroll and keep a hover on their pages when
    // nothing covers that spot of the monitor (Minecraft does). So only while you're using the
    // window (pointing with Alt, sitting at it, or someone you allowed is), it moves just off your
    // monitor at its own size, and it goes straight back when you stop, Alt+Tab, or stop sharing.

    private static final Map<Long, Long> PARKED = new HashMap<>(); // window → last time it was used
    private static final long PARK_IDLE_NANOS = 2_500_000_000L;

    private static void ensureParked(Display d) {
        if (d == null || d.share() == null) return;
        SourceInfo s = d.share().source();
        if (s.kind() != SourceInfo.Kind.WINDOW || s.handle() == 0 || !dev.windowscraft.capture.win.WinOps.available()) return;
        if (!MinecraftClient.getInstance().isWindowFocused()) return;
        long h = s.handle();
        Long was = PARKED.put(h, System.nanoTime());
        if (was != null) return;
        dev.windowscraft.capture.win.WinOps.geometry(h).thenAccept(g -> {
            if (g != null && !g.minimized()) {
                dev.windowscraft.capture.win.WinOps.park(h, g.frameW(), g.frameH());
                movedAwayAt = System.nanoTime(); // the pointer's spot is somewhere else on the desktop now: say it again
            }
        });
    }

    /** The window just moved off the monitor: the hover sent before that went to the old spot. */
    private static volatile long movedAwayAt;

    /** Is someone using that wall right now (pointing at it, sitting at it, or one of your guests)? */
    private static boolean inUse(Display d) {
        if (d == null) return false;
        if (TheaterMode.controlled() == d) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (pointed == d && mc.currentScreen == null && altHeld(mc)) return true;
        Map<UUID, Guest> g = GUESTS.get(d.id);
        return g != null && !g.isEmpty();
    }

    private static void tickParking(MinecraftClient mc) {
        if (movedAwayAt != 0 && System.nanoTime() - movedAwayAt > 100_000_000L) { // once the move has landed
            movedAwayAt = 0;
            Display d = TheaterMode.controlled() != null ? TheaterMode.controlled() : pointed;
            if (d != null && hovering) {
                lastX = lastY = -1;
                sendMove(d);
            }
        }
        if (PARKED.isEmpty()) return;
        long now = System.nanoTime();
        boolean away = !mc.isWindowFocused();
        Map<Long, Display> shared = new HashMap<>();
        for (Display d : DisplayManager.get().all())
            if (d.isLive() && d.share() != null && d.share().source().kind() == SourceInfo.Kind.WINDOW) shared.put(d.share().source().handle(), d);
        PARKED.entrySet().removeIf(e -> {
            Display d = shared.get(e.getKey());
            if (d != null && inUse(d)) e.setValue(now);
            boolean done = away || d == null || now - e.getValue() > PARK_IDLE_NANOS;
            if (done) dev.windowscraft.capture.win.WinOps.unpark(e.getKey());
            return done;
        });
    }

    private static void sendMove(Display d) {
        if (!controllable(d)) return;
        Aim a = aimFor(d);
        if (a == null) return;
        ensureParked(d);
        aim(a.hwnd());
        if (Math.abs(a.nx() - lastX) < 1e-5 && Math.abs(a.ny() - lastY) < 1e-5) return;
        lastX = a.nx();
        lastY = a.ny();
        hovering = true;
        InputBridge.send(String.format(Locale.ROOT, "move %.5f %.5f", a.nx(), a.ny()));
    }

    /** You clicked the app while pointing with Alt (not sitting): it's active until you stop pointing. */
    private static boolean pointFocused;

    private static void endHover() {
        if (hovering && target != 0 && buttons == 0) InputBridge.send("leave");
        hovering = false;
        lastX = lastY = -1;
    }

    // ---- from the input hooks (true = used; Minecraft never sees it) -------------------------

    /** Tests: pretend Alt is held. */
    public static volatile boolean testAlt;

    public static boolean altHeld(MinecraftClient mc) {
        if (testAlt) return true;
        // pointing at your in-game monitor: Alt was let go for its apps, but you still hold it
        if (dev.windowscraft.monitor.InGameMonitors.ownerPointing() && dev.windowscraft.monitor.InGameMonitors.pointReady()) return true;
        long h = mc.getWindow().getHandle();
        return net.minecraft.client.util.InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_ALT)
                || net.minecraft.client.util.InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private static boolean sneaking(MinecraftClient mc) { return mc.player != null && mc.player.isSneaking(); }

    /**
     * Holding your tablet (or using it): the walls around you aren't yours to point at — your
     * clicks and Alt belong to the tablet, and nobody's screen thinks you're using it.
     */
    static boolean onOwnTablet(MinecraftClient mc) {
        if (Tablet.raised()) return true;
        if (dev.windowscraft.monitor.InGameMonitors.seated() != null && TheaterMode.controlled() == null) return true;
        return mc.player != null && dev.windowscraft.block.Monitors.isTablet(mc.player.getMainHandStack());
    }

    public static boolean onMouseButton(MinecraftClient mc, int button, int action) {
        // letting go always reaches the app that's holding the button, wherever you look now
        if (action == GLFW.GLFW_RELEASE && buttons != 0 && pressedOn != null) {
            button(pressedOn, button, false, false);
            return true;
        }
        if (action == GLFW.GLFW_RELEASE && remoteButtons != 0 && remotePressedOn != null) {
            remoteButton(remotePressedOn, button, false);
            return true;
        }
        if (mc.currentScreen != null) return false;
        Display d = TheaterMode.controlled();
        RemoteDisplays.Remote r = TheaterMode.controlledRemote();
        if (d == null && r == null) {
            if ((pointed == null && pointedRemote == null) || sneaking(mc)) return false;
            if (!altHeld(mc)) return true; // walking around: a plain click on the wall does nothing (and never mines it)
            d = pointed;
            r = pointedRemote;
        }
        if (action == GLFW.GLFW_REPEAT) return true;
        active();
        if (r != null) remoteButton(r, button, action == GLFW.GLFW_PRESS);
        else button(d, button, action == GLFW.GLFW_PRESS, TheaterMode.controlled() == d);
        return true;
    }

    private static void remoteButton(RemoteDisplays.Remote r, int glfwButton, boolean down) {
        if (glfwButton < 0 || glfwButton > 4) return;
        if (down) {
            remoteButtons |= 1 << glfwButton;
            remotePressedOn = r;
        } else {
            remoteButtons &= ~(1 << glfwButton);
        }
        sendRemote(r, down ? Payloads.OP_BUTTON_DOWN : Payloads.OP_BUTTON_UP, glfwButton, 0);
        if (remoteButtons == 0) remotePressedOn = null;
    }

    private static void button(Display d, int glfwButton, boolean down, boolean seated) {
        String b = switch (glfwButton) {
            case 0 -> "l";
            case 1 -> "r";
            case 2 -> "m";
            case 3 -> "x1";
            case 4 -> "x2";
            default -> null;
        };
        if (b == null || !controllable(d) || realSeat(d)) { if (!down) { buttons = 0; pressedOn = null; } return; }
        Aim a = aimFor(d);
        if (a == null) return;
        ensureParked(d);
        aim(a.hwnd());
        if (down) {
            buttons |= 1 << glfwButton;
            if (pressedOn != d) pressAt = under;
            pressedOn = d;
            if (seated) InputBridge.send("focus");
        } else {
            buttons &= ~(1 << glfwButton);
        }
        InputBridge.send(String.format(Locale.ROOT, "button %s %s %.5f %.5f", b, down ? "down" : "up", a.nx(), a.ny()));
        if (buttons == 0) { pressedOn = null; pressAt = null; }
    }

    public static boolean onScroll(MinecraftClient mc, double vertical) {
        if (mc.currentScreen != null) return false;
        Display d = TheaterMode.controlled();
        RemoteDisplays.Remote r = TheaterMode.controlledRemote();
        if (d != null || r != null) {
            if (altHeld(mc)) return false; // Alt + wheel: zoom (theater handles it)
        } else {
            if ((pointed == null && pointedRemote == null) || sneaking(mc) || !altHeld(mc)) return false; // otherwise your hotbar
            d = pointed;
            r = pointedRemote;
        }
        active();
        if (r != null) sendRemote(r, Payloads.OP_WHEEL, 0, vertical);
        else wheel(d, vertical);
        return true;
    }

    private static void wheel(Display d, double amount) {
        if (realSeat(d)) return;
        Aim a = aimFor(d);
        if (a == null) return;
        ensureParked(d);
        aim(a.hwnd());
        InputBridge.send(String.format(Locale.ROOT, "wheel %.3f 0 %.5f %.5f", amount, a.nx(), a.ny()));
    }

    private static void key(Display d, int scancode, boolean down) {
        if (realSeat(d)) return; // the real keyboard reaches it already
        long t = d.share().source().kind() != SourceInfo.Kind.SCREEN ? d.share().source().handle() : target;
        if (t == 0 && !testControlPattern) return;
        aim(t);
        InputBridge.send((down ? "key down " : "key up ") + scancode);
    }

    /** Mouse movement this frame, before Minecraft's sensitivity (set by the mouse hook). */
    public static double rawDX, rawDY;
    private static boolean nativeOn;

    /** In the chair: the mouse moves the pointer (like your desktop cursor) unless Alt is held. */
    public static boolean onLook(MinecraftClient mc, double dx, double dy) {
        Display d = TheaterMode.controlled();
        RemoteDisplays.Remote r = TheaterMode.controlledRemote();
        // your real cursor is on an in-game monitor while you play: your view turns from the
        // helper's reports of your mouse (nothing of it reaches the game twice)
        if (d == null && r == null && dev.windowscraft.monitor.InGameMonitors.pointReady()
                && dev.windowscraft.monitor.InGameMonitors.seated() == null) return true;
        boolean pointer = (d != null || r != null) && !altHeld(mc) && !TheaterMode.leaving();
        // the pointer moves like your desktop cursor (Windows' own pointer movement); looking
        // around (Alt) uses Minecraft's own mouse settings, so it turns exactly like in the game
        if (pointer != nativeOn) nativePointer(mc, pointer);
        if (!pointer) return false;
        if (dx != 0 || dy != 0) {
            active();
            int[] mon = monitorSize(mc);
            double k = ClientSettings.pointerSpeed();
            // the picture fills about your whole view when you sit: crossing it takes the same hand
            // movement as crossing your real monitor
            double[] p = r != null ? mine(r) : pointer(d);
            if (r != null) pointToRemote(r, p[0] + dx / mon[0] * k, p[1] + dy / mon[1] * k);
            else pointTo(d, p[0] + dx / mon[0] * k, p[1] + dy / mon[1] * k);
        }
        return true;
    }

    /** Your monitor's size in pixels (where the game is), to move the pointer like your desktop cursor. */
    static int[] monitorSize(MinecraftClient mc) {
        try {
            long m = GLFW.glfwGetWindowMonitor(mc.getWindow().getHandle());
            if (m == 0) m = GLFW.glfwGetPrimaryMonitor();
            var mode = m == 0 ? null : GLFW.glfwGetVideoMode(m);
            if (mode != null) return new int[]{Math.max(1, mode.width()), Math.max(1, mode.height())};
        } catch (Throwable ignored) {
        }
        return new int[]{1920, 1080};
    }

    /**
     * Sitting at your wall: Windows' own pointer movement (your pointer speed and "Enhance pointer
     * precision") is what the game gets when raw input is off, so it's off while the pointer moves.
     */
    public static void nativePointer(MinecraftClient mc, boolean on) {
        nativeOn = on;
        try {
            long h = mc.getWindow().getHandle();
            if (!GLFW.glfwRawMouseMotionSupported()) return;
            boolean raw = !on && mc.options.getRawMouseInput().getValue();
            GLFW.glfwSetInputMode(h, GLFW.GLFW_RAW_MOUSE_MOTION, raw ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        } catch (Throwable ignored) {
        }
    }

    public static boolean onKey(MinecraftClient mc, int key, int scancode, int action, int mods) {
        // the Esc that got you up (or closed Start on the way) doesn't also open the pause menu
        if (key == GLFW.GLFW_KEY_ESCAPE && mc.currentScreen == null && dev.windowscraft.monitor.InGameMonitors.justGotUp()) return true;
        if (mc.currentScreen != null || !TheaterMode.active()) return false;
        // Esc gets up (Shift+Esc goes to the app instead)
        if (key == GLFW.GLFW_KEY_ESCAPE && (mods & GLFW.GLFW_MOD_SHIFT) == 0) {
            if (action == GLFW.GLFW_PRESS) TheaterMode.leaveNow();
            return true;
        }
        Display d = TheaterMode.controlled();
        RemoteDisplays.Remote r = TheaterMode.controlledRemote();
        if (d == null && r == null) return false; // a wall you only watch: theater keys as before
        if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT) return true; // Alt is for looking around
        if (altHeld(mc)) {
            if (key == GLFW.GLFW_KEY_SPACE && action == GLFW.GLFW_PRESS) TheaterMode.recenter();
            return true;
        }
        if (scancode <= 0) return true;
        if (r != null) sendRemote(r, action == GLFW.GLFW_RELEASE ? Payloads.OP_KEY_UP : Payloads.OP_KEY_DOWN, scancode, 0);
        else key(d, scancode, action != GLFW.GLFW_RELEASE);
        return true;
    }

    public static boolean onChar(MinecraftClient mc, int codePoint) {
        return (TheaterMode.controlled() != null || TheaterMode.controlledRemote() != null) && mc.currentScreen == null; // (keys go as keys)
    }

    /** Sat down: the app gets the keyboard. */
    static void sitDown(Display d) {
        active();
        if (!controllable(d)) return;
        SourceInfo s = d.share().source();
        if (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(s)) {
            // an in-game monitor: your real mouse and keyboard go to it
            double[] p = pointer(d);
            if (dev.windowscraft.monitor.InGameMonitors.seat(d, p[0], p[1])) return;
        }
        if (s.kind() != SourceInfo.Kind.SCREEN) {
            ensureParked(d);
            aim(s.handle());
            InputBridge.send("focus");
        }
    }

    /** Sat down at someone else's wall: its owner's app gets your keyboard. */
    static void sitDown(RemoteDisplays.Remote r) {
        active();
        usingR = r;
        sendRemote(r, Payloads.OP_SIT, 0, 0);
    }

    /** Got up from someone else's wall. */
    static void getUp(RemoteDisplays.Remote r) {
        if (remoteButtons != 0 && remotePressedOn == r) sendRemote(r, Payloads.OP_BUTTON_UP, Integer.numberOfTrailingZeros(remoteButtons), 0);
        remoteButtons = 0;
        remotePressedOn = null;
        ClientNet.control(r.id, Payloads.OP_STAND, mine(r)[0], mine(r)[1], 0, 0);
        if (usingR == r) usingR = null;
        r.pointerShown = false;
        POINTER.remove(r.id);
    }

    /** Got up: let go of everything held in the app. */
    static void getUp() {
        dev.windowscraft.monitor.InGameMonitors.unseat();
        if (target != 0) {
            InputBridge.send("release");
            InputBridge.send("blur");
        }
        endHover();
        buttons = 0;
        pressedOn = null;
        pressAt = null;
    }

    // ---- per tick ----------------------------------------------------------------------------

    /** Client tick: people who stopped using your screens. */
    public static void tick(MinecraftClient mc) {
        tickGuests();
        tickParking(mc);
    }

    public static void onDisconnect() {
        for (long h : PARKED.keySet()) dev.windowscraft.capture.win.WinOps.unpark(h);
        PARKED.clear();
        GUESTS.clear();
        SENT_POINTER.clear();
        usingR = null;
        pointedRemote = null;
        remoteButtons = 0;
        remotePressedOn = null;
        target = 0;
        pointed = null;
    }

    private ScreenControl() {}
}
