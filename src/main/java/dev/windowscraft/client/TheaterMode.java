package dev.windowscraft.client;

import dev.windowscraft.audio.AcousticModel;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Theater mode: the camera leaves your body and floats, centred, in front of a screen, far
 * enough back that the picture fills most of the view with the blocks around it still showing.
 * The mouse wheel (Alt + wheel on your own screen) moves the camera closer or further: in as far
 * as the picture exactly filling the view, out to a couple of times the starting distance. Press the theater key
 * again (or walk off — you can't, movement is paused) to fly back. Purely client-side: other
 * players just see you standing where you were.
 */
public final class TheaterMode {
    /** How much of the view the screen fills at the starting distance. */
    private static final double FILL = 0.82;
    /** Closest zoom: the screen's edges touch the edges of the game window (FILL = 1). */
    private static final double MIN_FACTOR = FILL, MAX_FACTOR = 2.4, MIN_DISTANCE = 0.6;
    private static final double IN_SECONDS = 0.45, OUT_SECONDS = 0.3;

    private static boolean active, leaving, finishQueued;
    private static WallRect rect;
    private static String dimension;
    private static BooleanSupplier stillThere;
    private static String title;
    private static double distance, fitDistance, allowedMax;
    private static double progress;          // 0 = in your head, 1 = in front of the screen
    private static long lastFrameNanos;
    private static boolean savedHudHidden;
    // looking around from the seat (degrees, relative to facing the screen); Space recenters
    private static double lookYaw, lookPitch;
    private static boolean recentering;
    private static long enteredNanos;
    /** Your own screen: the mouse and keyboard work it like a PC (null for someone else's screen). */
    private static Display controlled;
    /** Someone else's screen that its owner lets you use (null otherwise). */
    private static RemoteDisplays.Remote controlledRemote;

    public static boolean active() { return active; }

    /** The wall being watched (null when not in theater mode). */
    public static WallRect rect() { return active ? rect : null; }

    /** Sitting still in front of the screen, looking straight at it (not flying in or out, not looking around). */
    public static boolean seated() {
        return active && !leaving && progress >= 1 && Math.abs(lookYaw) < 0.01 && Math.abs(lookPitch) < 0.01;
    }

    /** The screen being used in theater mode, if it's one of yours (mouse/keys go to it). */
    public static Display controlled() { return active && !leaving ? controlled : null; }

    /** The screen being used in theater mode, if it's someone else's that they let you use. */
    public static RemoteDisplays.Remote controlledRemote() { return active && !leaving ? controlledRemote : null; }

    /** Esc: fly back. */
    public static void leaveNow() { if (active && !leaving) leave(); }

    /** The theater key: start on the screen you're looking at (or the nearest one ahead), or leave. */
    public static void toggle(MinecraftClient mc) {
        if (active && !leaving) { leave(); return; }
        if (active) return;
        if (mc.player == null || mc.world == null) return;
        String dim = mc.world.getRegistryKey().getValue().toString();
        if (!pickScreen(mc, dim)) {
            mc.player.sendMessage(Text.literal("Look at a live screen, then press " + keyName() + " for theater mode").formatted(Formatting.GRAY), true);
            return;
        }
        dimension = dim;
        active = true;
        leaving = false;
        progress = 0;
        lookYaw = lookPitch = 0;
        recentering = false;
        enteredNanos = System.nanoTime();
        lastFrameNanos = System.nanoTime();
        savedHudHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
        fitDistance = fitDistance(mc, rect);
        distance = fitDistance;
        allowedMax = fitDistance * MAX_FACTOR;
        if (controlled != null) {
            // your own screen: sit exactly where it fills your view, like your real monitor —
            // a 1080p desktop on a 1080p monitor is then pixel for pixel
            distance = fitDistance * MIN_FACTOR;
            KeyBinding.unpressAll(); // let go of W etc. — from now on keys belong to the screen
            ScreenControl.sitDown(controlled);
            ScreenControl.nativePointer(mc, true);
        } else if (controlledRemote != null) {
            distance = fitDistance * MIN_FACTOR;
            KeyBinding.unpressAll();
            ScreenControl.sitDown(controlledRemote);
            ScreenControl.nativePointer(mc, true);
        }
    }

    /** Starts theater mode on a specific remote screen (from its options dialog). */
    public static void watch(MinecraftClient mc, RemoteDisplays.Remote r) {
        if (active || mc.world == null) return;
        String dim = mc.world.getRegistryKey().getValue().toString();
        targetRemote(r);
        dimension = dim;
        active = true;
        leaving = false;
        progress = 0;
        lookYaw = lookPitch = 0;
        recentering = false;
        enteredNanos = System.nanoTime();
        lastFrameNanos = System.nanoTime();
        savedHudHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
        fitDistance = fitDistance(mc, rect);
        distance = fitDistance;
        allowedMax = fitDistance * MAX_FACTOR;
        if (controlledRemote != null) {
            distance = fitDistance * MIN_FACTOR;
            KeyBinding.unpressAll();
            ScreenControl.sitDown(controlledRemote);
            ScreenControl.nativePointer(mc, true);
        }
    }

    private static void targetRemote(RemoteDisplays.Remote r) {
        WallRect at = r.rect;
        target(at, r.info.title(), () -> RemoteDisplays.all().contains(r) && at.equals(r.rect) && r.wallOk);
        if (ScreenControl.controllable(r)) controlledRemote = r;
    }

    private static void target(WallRect r, String t, BooleanSupplier alive) {
        rect = r;
        title = t;
        stillThere = alive;
        controlled = null;
        controlledRemote = null;
    }

    /** Your own wall with a window or monitor on it: the mouse and keyboard work it. */
    private static void targetOwn(Display d) {
        WallRect at = d.rect;
        target(at, d.share().source().title(), () -> d.isLive() && DisplayManager.get().all().contains(d) && at.equals(d.rect));
        if (ScreenControl.controllable(d)) controlled = d;
    }

    private static boolean pickScreen(MinecraftClient mc, String dim) {
        HitResult hit = mc.player.raycast(64, 1.0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            Display d = DisplayManager.get().findAt(dim, bhr.getBlockPos(), bhr.getSide());
            if (d != null && d.isLive()) {
                targetOwn(d);
                return true;
            }
            RemoteDisplays.Remote r = RemoteDisplays.findAt(dim, bhr.getBlockPos(), bhr.getSide());
            if (r != null) {
                targetRemote(r);
                return true;
            }
        }
        // otherwise: the nearest live screen roughly in front of you, facing you
        Vec3d eye = mc.player.getEyePos(), look = mc.player.getRotationVec(1f);
        double best = 48 * 48;
        boolean found = false;
        for (Display d : DisplayManager.get().all()) {
            if (d.tablet || !d.isLive() || !d.dimension.equals(dim)) continue;
            double s = score(d.rect, eye, look);
            if (s < best) {
                best = s;
                targetOwn(d);
                found = true;
            }
        }
        for (RemoteDisplays.Remote r : RemoteDisplays.all()) {
            if (!r.info.dimension().equals(dim)) continue;
            double s = score(r.rect, eye, look);
            if (s < best) { best = s; targetRemote(r); found = true; }
        }
        return found;
    }

    /** Squared distance, or "infinitely far" if it's behind you or you're behind it. */
    private static double score(WallRect r, Vec3d eye, Vec3d look) {
        Vec3d c = r.center();
        Vec3d to = c.subtract(eye);
        Vec3d n = r.normal();
        if (to.normalize().dotProduct(look) < 0.5 || to.dotProduct(n) > 0) return Double.MAX_VALUE;
        return to.lengthSquared();
    }

    private static double fitDistance(MinecraftClient mc, WallRect r) {
        double vfov = Math.toRadians(mc.options.getFov().getValue());
        double aspect = mc.getWindow().getFramebufferWidth() / (double) Math.max(1, mc.getWindow().getFramebufferHeight());
        double tanV = Math.tan(vfov / 2), tanH = tanV * aspect;
        double dH = r.height() / 2.0 / (FILL * tanV);
        double dW = r.width() / 2.0 / (FILL * tanH);
        return Math.max(MIN_DISTANCE * 0.5 / FILL, Math.max(dH, dW));
    }

    private static void leave() {
        leaving = true;
        dev.windowscraft.monitor.InGameMonitors.unseat(); // the mouse and keyboard come back right away
    }

    private static void finish(MinecraftClient mc) {
        if (!active) return;
        active = false;
        leaving = false;
        finishQueued = false;
        if (controlled != null) {
            ScreenControl.getUp();
            ScreenControl.nativePointer(mc, false);
        }
        if (controlledRemote != null) {
            ScreenControl.getUp(controlledRemote);
            ScreenControl.nativePointer(mc, false);
        }
        controlled = null;
        controlledRemote = null;
        mc.options.hudHidden = savedHudHidden;
        KeyBinding.updatePressedStates(); // movement keys you're holding work again straight away
    }

    /** Start of each client tick: camera W/S, pause walking, end if the screen went away. */
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        if (mc.world == null || mc.player == null || !mc.world.getRegistryKey().getValue().toString().equals(dimension)
                || !stillThere.getAsBoolean() || mc.player.isDead()) {
            finish(mc);
            return;
        }
        // keep the camera in front of anything built between it and the screen
        Vec3d c = center(), n = rect.normal();
        var res = mc.world.raycast(new RaycastContext(c.add(n.multiply(rect.isMonitor() ? 0.75 : 0.05)), c.add(n.multiply(fitDistance * MAX_FACTOR)),
                RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, mc.player));
        allowedMax = res.getType() == HitResult.Type.MISS ? fitDistance * MAX_FACTOR
                : Math.max(MIN_DISTANCE, res.getPos().distanceTo(c) - 0.3);
        clampDistance();
        // Space (jump) or the middle mouse button: look straight at the screen again
        if (mc.currentScreen == null && !leaving && controlled == null && controlledRemote == null) { // a screen you watch: W / S move closer, further
            long h = mc.getWindow().getHandle();
            double step = fitDistance * 0.9 / 20.0;
            if (held(h, mc.options.forwardKey)) distance -= step;
            if (held(h, mc.options.backKey)) distance += step;
        }
        if (mc.currentScreen == null && controlled == null && controlledRemote == null && (mc.options.jumpKey.wasPressed() || mc.options.pickItemKey.wasPressed())) recenter();
        // the body stays put while you watch — and can't mine, hit or place anything unseen
        // (holding left-click would otherwise break the very wall you're watching)
        for (KeyBinding k : new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey,
                mc.options.jumpKey, mc.options.sneakKey, mc.options.sprintKey, mc.options.attackKey, mc.options.useKey,
                mc.options.pickItemKey, mc.options.dropKey, mc.options.swapHandsKey}) {
            k.setPressed(false);
            while (k.wasPressed()) { /* drain queued clicks */ }
        }
    }

    private static boolean held(long window, KeyBinding k) {
        net.minecraft.client.util.InputUtil.Key key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(k);
        return key.getCategory() == net.minecraft.client.util.InputUtil.Type.KEYSYM && net.minecraft.client.util.InputUtil.isKeyPressed(window, key.getCode());
    }

    /** Mouse movement while watching: look around from the seat. Returns true if it was used. */
    public static boolean look(double dx, double dy) {
        if (!active || leaving) return false;
        if (dx == 0 && dy == 0) return true; // the game reports the mouse every frame, moved or not
        recentering = false;
        lookYaw = MathHelper.clamp(lookYaw + dx * 0.15, -150, 150);   // same feel as normal mouse look
        lookPitch = MathHelper.clamp(lookPitch + dy * 0.15, -89, 89);
        return true;
    }

    /** Ease the view back to straight at the screen. */
    public static void recenter() {
        if (active) recentering = true;
    }

    /** How far the view is turned away from the screen, in degrees (for the on-screen hint). */
    public static double lookOffset() { return Math.hypot(lookYaw, lookPitch); }

    /** Seconds since theater mode started (for the on-screen hint). */
    public static double secondsActive() { return active ? (System.nanoTime() - enteredNanos) / 1e9 : 0; }

    public static boolean leaving() { return leaving; }

    /** Mouse wheel while watching: zoom the camera in or out. Returns true if it was used. */
    public static boolean scroll(double amount) {
        if (!active || leaving || MinecraftClient.getInstance().currentScreen != null) return false;
        distance *= Math.pow(0.9, amount);
        clampDistance();
        return true;
    }

    private static void clampDistance() {
        // fitDistance has the screen fill FILL of the view; × FILL is where it fills it exactly
        double min = Math.max(MIN_DISTANCE * 0.5, fitDistance * MIN_FACTOR);
        distance = MathHelper.clamp(distance, min, Math.max(min, Math.min(fitDistance * MAX_FACTOR, allowedMax)));
    }

    private static Vec3d center() {
        AcousticModel.SourcePlane p = RemoteDisplays.plane(rect);
        return new Vec3d(p.centerX(), p.centerY(), p.centerZ());
    }

    /**
     * Called by the camera after vanilla positioned it. Returns the overridden pose, or null to
     * leave the camera alone. Eases between your eyes and the theater seat.
     */
    public static Pose cameraPose(Vec3d vanillaPos, float vanillaYaw, float vanillaPitch, Entity focused) {
        if (!active) return null;
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        progress += leaving ? -dt / OUT_SECONDS : dt / IN_SECONDS;
        if (leaving && progress <= 0) {
            if (!finishQueued) {
                finishQueued = true;
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> finish(mc));
            }
            progress = 0;
            return null;
        }
        progress = MathHelper.clamp(progress, 0, 1);
        double e = progress * progress * (3 - 2 * progress); // smoothstep
        Vec3d n = rect.normal();
        Vec3d seat = center().add(n.multiply(distance));
        Vec3d facing = n.negate();
        float seatYaw = (float) Math.toDegrees(Math.atan2(-facing.x, facing.z));
        // (a tilted monitor: you look up or down at it, square on)
        float seatPitch = (float) -Math.toDegrees(Math.asin(MathHelper.clamp(facing.y, -1, 1)));
        Vec3d pos = vanillaPos.lerp(seat, e);
        if (recentering) { // glide back over ~0.25 s
            double k = Math.min(1, dt / 0.08);
            lookYaw -= lookYaw * k;
            lookPitch -= lookPitch * k;
            if (Math.abs(lookYaw) < 0.05 && Math.abs(lookPitch) < 0.05) { lookYaw = lookPitch = 0; recentering = false; }
        }
        float targetYaw = seatYaw + (float) lookYaw, targetPitch = MathHelper.clamp(seatPitch + (float) lookPitch, -90, 90);
        float yaw = vanillaYaw + MathHelper.wrapDegrees(targetYaw - vanillaYaw) * (float) e;
        float pitch = vanillaPitch + (targetPitch - vanillaPitch) * (float) e;
        // your character stays visible (like third person) once the camera has left your head
        return new Pose(pos, yaw, pitch, e > 0.25);
    }

    public record Pose(Vec3d pos, float yaw, float pitch, boolean showBody) {}

    public static String watchingTitle() { return active ? title : null; }

    public static String keyName() {
        return dev.windowscraft.WindowsCraftClient.THEATER_KEY.getBoundKeyLocalizedText().getString();
    }

    private TheaterMode() {}
}
