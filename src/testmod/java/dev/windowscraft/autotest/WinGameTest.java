package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.monitor.InGameMonitors;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

/**
 * The real thing on a real Windows PC (run by the Windows game test on GitHub): the real
 * in-game monitor helper, the real Parsec driver — U on a wall, on a gaming monitor block, a
 * joined monitor, sitting down, a tablet, breaking a wall. Each step waits (up to a limit) for
 * what should happen, and says what it saw. -Dwindowscraft.wintest=true
 */
public final class WinGameTest implements ClientModInitializer {
    private int tick, ground, failures;
    private final List<String> messages = new ArrayList<>();
    /** The step in progress: what it waits for, until when, and what follows. */
    private BooleanSupplier waitFor;
    private long waitUntil;
    private String waitName;
    private int stage;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("windowscraft.wintest")) return;
        InGameMonitors.testMessages = m -> {
            synchronized (messages) { messages.add(m); }
            log("message: " + m);
        };
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[wintest] {}", s); }

    private void check(boolean ok, String what) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private void shot(MinecraftClient mc, String name) {
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, "wintest_" + name + ".png", mc.getFramebuffer(), msg -> {});
    }

    private String dim(MinecraftClient mc) { return mc.world.getRegistryKey().getValue().toString(); }

    private Display at(MinecraftClient mc, BlockPos p) { return DisplayManager.get().findAt(dim(mc), p, Direction.SOUTH); }

    /** Waits for something (logging what happened), then goes on to the next stage. */
    private void await(String name, int seconds, BooleanSupplier f) {
        waitName = name;
        waitFor = f;
        waitUntil = System.nanoTime() + seconds * 1_000_000_000L;
    }

    private void look(MinecraftClient mc, double x, double y, double z, Vec3d target) {
        mc.player.setPosition(x, y, z);
        Vec3d to = target.subtract(mc.player.getEyePos());
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        mc.getServer().execute(() -> {
            ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayerList().get(0);
            p.teleport(mc.getServer().getOverworld(), x, y, z, mc.player.getYaw(), mc.player.getPitch());
        });
    }

    private void pressU() { KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0)); }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        try {
            if (waitFor != null) {
                boolean done = waitFor.getAsBoolean();
                if (!done && System.nanoTime() < waitUntil) return;
                check(done, waitName + (done ? "" : " — waited, it didn't happen. Messages: " + lastMessages()));
                StringBuilder st = new StringBuilder();
                for (Display d : DisplayManager.get().all())
                    st.append(String.format(" [%s %s live %s on %s]", d.tablet ? "tablet" : d.rect.faceString().split("~")[0], d.rect.min().toShortString(), d.isLive(), InGameMonitors.isOn(d)));
                log("screens:" + st + " · monitors " + InGameMonitors.all());
                waitFor = null;
                stage++;
                tick = 0;
                return;
            }
            step(mc);
        } catch (Throwable t) {
            WindowsCraftClient.LOGGER.error("[wintest] crashed at stage {}", stage, t);
            failures++;
            finish(mc);
        }
    }

    private String lastMessages() {
        synchronized (messages) { return messages.subList(Math.max(0, messages.size() - 6), messages.size()).toString(); }
    }

    private final BlockPos wallMid = new BlockPos(0, 0, -6);
    private BlockPos wall(int dx, int dy) { return new BlockPos(dx, ground + 2 + dy, -6); }
    private BlockPos mon() { return new BlockPos(6, ground + 1, -4); }

    private int fs;

    /** The game in fullscreen: sit down, and nothing of the screen is under your taskbar or cut off. */
    private void fullscreenSeat(MinecraftClient mc) {
        switch (fs) {
            case 1 -> {
                if (tick < 30) return;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullscreen();
                fs = 2;
                tick = 0;
            }
            case 2 -> {
                if (tick < 80) return;
                log("fullscreen: " + mc.getWindow().isFullscreen() + ", " + mc.getWindow().getFramebufferWidth() + "x" + mc.getWindow().getFramebufferHeight());
                look(mc, 0.5, ground + 1, -1.5, Vec3d.ofCenter(wall(0, 0)));
                fs = 3;
                tick = 0;
            }
            case 3 -> {
                if (tick < 10) return;
                dev.windowscraft.client.TheaterMode.toggle(mc);
                fs = 4;
                tick = 0;
            }
            case 4 -> {
                if (tick < 100) return; // (seated; the monitor's apps have the keyboard, Windows' taskbar does what it does)
                check(InGameMonitors.seated() != null, "fullscreen: O sits you down");
                log("fullscreen seated: visible part " + java.util.Arrays.toString(dev.windowscraft.client.GameArea.visible()));
                shot(mc, "4b_seated_fullscreen");
                checkUncovered(mc, "fullscreen");
                int[] vis = dev.windowscraft.client.GameArea.visible();
                check(vis[3] == mc.getWindow().getFramebufferHeight(), "fullscreen: the screen may use the full height (no taskbar shows there): " + java.util.Arrays.toString(vis));
                dev.windowscraft.client.TheaterMode.leaveNow();
                fs = 5;
                tick = 0;
            }
            case 5 -> {
                if (tick < 30) return;
                if (mc.getWindow().isFullscreen()) mc.getWindow().toggleFullscreen();
                fs = 6;
                tick = 0;
            }
            case 6 -> {
                // a wide game window that reaches down under your real taskbar, at a big wall that
                // fills the view top to bottom: the screen must stop above the taskbar
                if (tick < 40) return;
                var u = com.sun.jna.platform.win32.User32.INSTANCE;
                int sw = u.GetSystemMetrics(0), sh = u.GetSystemMetrics(1);
                long h = mc.getWindow().getHandle();
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(h, sw - 40, Math.max(300, sh * 11 / 20));
                org.lwjgl.glfw.GLFW.glfwSetWindowPos(h, 20, sh - Math.max(300, sh * 11 / 20)); // (its bottom at the screen's bottom)
                fs = 7;
                tick = 0;
            }
            case 7 -> {
                if (tick < 60) return;
                log("wide window: " + mc.getWindow().getFramebufferWidth() + "x" + mc.getWindow().getFramebufferHeight()
                        + ", inside on the desktop " + java.util.Arrays.toString(dev.windowscraft.capture.win.WinGeom.clientArea(dev.windowscraft.capture.win.WinGeom.gameWindow)));
                look(mc, 0.5, ground + 1, -1.5, Vec3d.ofCenter(wall(0, 0)));
                fs = 8;
                tick = 0;
            }
            case 8 -> {
                if (tick < 10) return;
                dev.windowscraft.client.TheaterMode.toggle(mc);
                fs = 9;
                tick = 0;
            }
            case 9 -> {
                if (tick < 100) return;
                check(InGameMonitors.seated() != null, "wide window under the taskbar: O sits you down");
                log("wide window seated: visible part " + java.util.Arrays.toString(dev.windowscraft.client.GameArea.visible()));
                shot(mc, "4c_seated_wide_under_taskbar");
                checkUncovered(mc, "wide window under your taskbar");
                dev.windowscraft.client.TheaterMode.leaveNow();
                fs = 10;
                tick = 0;
            }
            case 10 -> {
                if (tick < 30) return;
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getHandle(), 1006, 679);
                org.lwjgl.glfw.GLFW.glfwSetWindowPos(mc.getWindow().getHandle(), 9, 32);
                fs = 0;
                await("getting up gives the mouse and keyboard back", 20, () -> InGameMonitors.seated() == null);
            }
            default -> fs = 0;
        }
    }

    /** Seated in fullscreen: your real taskbar is there to see, and the screen sits right above it. */
    private void checkTaskbarShows(MinecraftClient mc) {
        var u = com.sun.jna.platform.win32.User32.INSTANCE;
        long game = dev.windowscraft.capture.win.WinGeom.gameWindow;
        var mon = u.MonitorFromWindow(new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(game)), 2);
        var mi = new com.sun.jna.platform.win32.WinUser.MONITORINFO();
        u.GetMonitorInfo(mon, mi);
        var w = mi.rcWork;
        var m = mi.rcMonitor;
        if (w.bottom >= m.bottom) { log("fullscreen: no taskbar at the bottom of this screen (work area " + w + ")"); return; }
        long at = dev.windowscraft.capture.win.WinGeom.rootAt((w.left + w.right) / 2, (w.bottom + m.bottom) / 2);
        char[] cls = new char[64];
        int n = u.GetClassName(new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(at)), cls, 64);
        String c = new String(cls, 0, Math.max(0, n));
        var tray = u.FindWindow("Shell_TrayWnd", null);
        var tr = new com.sun.jna.platform.win32.WinDef.RECT();
        if (tray != null) u.GetWindowRect(tray, tr);
        var gh = new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(game));
        log("fullscreen taskbar: visible " + (tray != null && u.IsWindowVisible(tray)) + " at " + tr + " exstyle " + Integer.toHexString(tray == null ? 0 : u.GetWindowLong(tray, -20))
                + "; game exstyle " + Integer.toHexString(u.GetWindowLong(gh, -20)) + " style " + Integer.toHexString(u.GetWindowLong(gh, -16))
                + "; in front: " + u.GetForegroundWindow() + "; monitor " + m + " work " + w);
        check(at != game && c.startsWith("Shell_"), "fullscreen, seated: your real taskbar shows (at its spot: " + c + ")");
        int[] ca = dev.windowscraft.capture.win.WinGeom.clientArea(game);
        var shown = dev.windowscraft.client.DeskView.showing();
        if (ca == null || shown == null) return;
        double y1 = -1e9;
        for (Vec3d p : dev.windowscraft.client.ScreenControl.pictureCorners(shown)) {
            double[] q = dev.windowscraft.client.FrameView.project(p);
            if (q != null) y1 = Math.max(y1, q[1]);
        }
        double bottom = ca[1] + y1 * ca[3] / (double) mc.getWindow().getFramebufferHeight();
        check(bottom <= w.bottom + 1, "…and the screen sits above it (its bottom at " + Math.round(bottom) + ", the taskbar starts at " + w.bottom + ")");
    }

    /** Along the seated picture's edges, what you really see on the desktop is the game (no taskbar over it). */
    private void checkUncovered(MinecraftClient mc, String how) {
        var shown = dev.windowscraft.client.DeskView.showing();
        long game = dev.windowscraft.capture.win.WinGeom.gameWindow;
        int[] ca = dev.windowscraft.capture.win.WinGeom.clientArea(game);
        if (shown == null || ca == null) {
            check(false, how + ": the seated picture is shown flat (" + shown + ", " + java.util.Arrays.toString(ca) + ")");
            return;
        }
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (Vec3d p : dev.windowscraft.client.ScreenControl.pictureCorners(shown)) {
            double[] q = dev.windowscraft.client.FrameView.project(p);
            if (q == null) return;
            x0 = Math.min(x0, q[0]); x1 = Math.max(x1, q[0]); y0 = Math.min(y0, q[1]); y1 = Math.max(y1, q[1]);
        }
        double sx = ca[2] / (double) mc.getWindow().getFramebufferWidth(), sy = ca[3] / (double) mc.getWindow().getFramebufferHeight();
        int bad = 0, n = 0;
        StringBuilder what = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            double fx = x0 + (x1 - x0) * i / 10.0;
            for (double fy : new double[]{y0 + 3, y1 - 3}) {
                int x = ca[0] + (int) Math.round(fx * sx), y = ca[1] + (int) Math.round(fy * sy);
                long at = dev.windowscraft.capture.win.WinGeom.rootAt(x, y);
                n++;
                if (at != game) {
                    bad++;
                    if (what.length() < 200) what.append(" ").append(x).append(",").append(y).append("→").append(Long.toHexString(at));
                }
            }
        }
        log(how + ": picture " + Math.round(x0) + "," + Math.round(y0) + " - " + Math.round(x1) + "," + Math.round(y1) + " of " + mc.getWindow().getFramebufferWidth() + "x" + mc.getWindow().getFramebufferHeight()
                + ", game's inside on the desktop " + java.util.Arrays.toString(ca));
        check(bad == 0 && y0 >= -1 && y1 <= mc.getWindow().getFramebufferHeight() + 1,
                how + ": seated, nothing covers the screen's edges (no taskbar over it, nothing cut off) — " + (n - bad) + "/" + n + " edge points show the game" + what);
    }

    private void step(MinecraftClient mc) {
        switch (stage) {
            case 0 -> {
                if (tick < 60) return; // (the world settles)
                var sw = mc.getServer().getOverworld();
                ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
                log("ground " + ground + "; driver " + InGameMonitors.knownDriver() + "; supported " + InGameMonitors.supported());
                mc.getServer().execute(() -> {
                    for (BlockPos q : BlockPos.iterate(-8, ground + 1, -8, 10, ground + 6, 2)) sw.setBlockState(q, Blocks.AIR.getDefaultState());
                    for (int x = -2; x <= 2; x++) for (int y = 1; y <= 3; y++) sw.setBlockState(new BlockPos(x, ground + y, -6), Blocks.BLACK_CONCRETE.getDefaultState());
                    sw.setBlockState(mon(), dev.windowscraft.block.Monitors.MONITORS.get("black").getDefaultState());
                    ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    p.getInventory().clear();
                    p.getInventory().setStack(7, new net.minecraft.item.ItemStack(dev.windowscraft.block.Monitors.MONITOR_ITEMS.get("black"), 8));
                    p.getInventory().setStack(8, new net.minecraft.item.ItemStack(dev.windowscraft.block.Monitors.TABLETS.get("cyan")));
                    p.getInventory().selectedSlot = 0;
                    sw.setTimeOfDay(6000);
                });
                mc.player.getInventory().selectedSlot = 0;
                stage++;
                tick = 0;
            }
            case 1 -> {
                if (tick < 20) return;
                look(mc, 0.5, ground + 1, -1.5, Vec3d.ofCenter(wall(0, 0)));
                stage++;
                tick = 0;
            }
            case 2 -> {
                if (tick < 10) return;
                log("pressing U at the wall");
                pressU();
                await("U on a black concrete wall turns on an in-game monitor", 60, () -> InGameMonitors.isOn(at(mc, wall(0, 0))));
            }
            case 3 -> {
                if (tick < 40) return;
                shot(mc, "1_wall_monitor_on");
                log("live monitors: " + InGameMonitors.all());
                stage++;
                tick = 0;
            }
            case 4 -> {
                // off and on again, a few times in a row (the "U stopped doing anything" report)
                // (once it's really on: not while it's still starting, or coming back on by itself)
                if (tick < 20 || (tick < 600 && (InGameMonitors.starting(at(mc, wall(0, 0))) || !InGameMonitors.isOn(at(mc, wall(0, 0)))))) return;
                pressU();
                await("U on a live wall monitor turns it off (round " + (cycle + 1) + ")", 30, () -> !InGameMonitors.isOn(at(mc, wall(0, 0))) && !InGameMonitors.starting(at(mc, wall(0, 0))));
            }
            case 5 -> {
                if (tick < 10) return;
                pressU();
                await("…and U turns it back on (round " + (cycle + 1) + ")", 60, () -> InGameMonitors.isOn(at(mc, wall(0, 0))));
            }
            case 6 -> {
                if (tick < 40) return;
                if (++cycle < 4) {
                    stage = 4;
                    tick = 0;
                    return;
                }
                checkPictures(mc, "after turning it off and on 4 times, the wall shows its monitor's picture");
                look(mc, 6.5, ground + 1, -1.5, Vec3d.ofCenter(mon()).add(0, 0.05, 0));
                stage++;
                tick = 0;
            }
            case 7 -> {
                if (tick < 10) return;
                log("pressing U at the gaming monitor block");
                pressU();
                await("U on a gaming monitor block turns it on", 60, () -> InGameMonitors.isOn(DisplayManager.get().findAt(dim(mc), mon(), Direction.SOUTH)));
            }
            case 8 -> {
                if (tick < 40) return;
                shot(mc, "2_monitor_block_on");
                // a second monitor joined to its right
                mc.player.getInventory().selectedSlot = 7;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 7);
                stage++;
                tick = 0;
            }
            case 9 -> {
                if (tick < 10) return;
                Vec3d hit = Vec3d.ofCenter(mon()).add(0.5, 0, 0);
                var r = mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND,
                        new net.minecraft.util.hit.BlockHitResult(hit, Direction.EAST, mon(), false));
                log("placed a monitor against it: " + r + " → " + mc.world.getBlockState(mon().east()));
                mc.player.getInventory().selectedSlot = 0;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 0);
                stage++;
                tick = 0;
            }
            case 10 -> {
                if (tick < 20) return;
                look(mc, 7.0, ground + 1, -1.5, DisplayManager.get().findAt(dim(mc), mon(), Direction.SOUTH) != null
                        ? dev.windowscraft.block.MonitorBlock.rectAt(mc.world, mon().east()).center() : Vec3d.ofCenter(mon().east()));
                stage++;
                tick = 0;
            }
            case 11 -> {
                if (tick < 10) return;
                pressU();
                await("U on the joined monitor turns it on too", 60, () -> InGameMonitors.isOn(DisplayManager.get().findAt(dim(mc), mon().east(), Direction.SOUTH)));
            }
            case 12 -> {
                if (tick < 200) return; // (the layout follows within a few seconds)
                var all = InGameMonitors.all();
                log("monitors now: " + all + " · layout asked: " + InGameMonitors.arrangement());
                Display a = DisplayManager.get().findAt(dim(mc), mon(), Direction.SOUTH), b = DisplayManager.get().findAt(dim(mc), mon().east(), Direction.SOUTH);
                var ma = InGameMonitors.monitorOf(a);
                var mb = InGameMonitors.monitorOf(b);
                check(ma != null && mb != null && mb.left() == ma.right() && mb.top() == ma.top(),
                        "joined in the world, joined on the Windows desktop (" + ma + " / " + mb + ")");
                var mw = InGameMonitors.monitorOf(at(mc, wall(0, 0)));
                boolean touches = mw != null && ma != null && (mw.right() == ma.left() || ma.right() == mw.left() || mw.bottom() == ma.top() || ma.bottom() == mw.top())
                        && !(mw.right() == ma.left() && (mw.bottom() == ma.top() || ma.bottom() == mw.top()))
                        && !(ma.right() == mw.left() && (mw.bottom() == ma.top() || ma.bottom() == mw.top()));
                check(!touches, "the wall's monitor doesn't share an edge with them (" + mw + ")");
                checkDistinct("three screens, three different monitors (never two showing the same one)");
                checkPictures(mc, "every screen shows its monitor's picture");
                shot(mc, "3_joined");
                // sit down at the wall
                look(mc, 0.5, ground + 1, -1.5, Vec3d.ofCenter(wall(0, 0)));
                stage++;
                tick = 0;
            }
            case 13 -> {
                if (tick < 10) return;
                dev.windowscraft.client.TheaterMode.toggle(mc);
                await("O sits you down at it (your mouse and keyboard go to it)", 20, () -> InGameMonitors.seated() != null);
            }
            case 14 -> {
                if (fs > 0) { fullscreenSeat(mc); return; }
                if (tick < 60) return;
                shot(mc, "4_seated");
                check(dev.windowscraft.client.DeskView.showing() != null, "sitting down shows the monitor flat on your screen, pixel for pixel");
                // never past the game window's edges, never under your real taskbar
                int[] vis = dev.windowscraft.client.GameArea.visible();
                double[] pr = null;
                var shown = dev.windowscraft.client.DeskView.showing();
                if (shown != null) {
                    double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
                    for (Vec3d p : dev.windowscraft.client.ScreenControl.pictureCorners(shown)) {
                        double[] q = dev.windowscraft.client.FrameView.project(p);
                        if (q == null) { x0 = Double.NaN; break; }
                        x0 = Math.min(x0, q[0]); x1 = Math.max(x1, q[0]); y0 = Math.min(y0, q[1]); y1 = Math.max(y1, q[1]);
                    }
                    if (!Double.isNaN(x0)) pr = new double[]{x0, y0, x1, y1};
                }
                log("seated: visible part of the game " + java.util.Arrays.toString(vis) + " of " + mc.getWindow().getFramebufferWidth() + "x" + mc.getWindow().getFramebufferHeight()
                        + ", picture " + (pr == null ? "?" : Math.round(pr[0]) + "," + Math.round(pr[1]) + " - " + Math.round(pr[2]) + "," + Math.round(pr[3])));
                double[] fpr = pr;
                check(fpr != null && fpr[0] >= vis[0] - 1 && fpr[1] >= vis[1] - 1 && fpr[2] <= vis[0] + vis[2] + 1 && fpr[3] <= vis[1] + vis[3] + 1,
                        "fully zoomed in, the screen stays inside what you see of the game (never under your taskbar)");
                checkUncovered(mc, "windowed");
                dev.windowscraft.client.TheaterMode.leaveNow();
                fs = 1; // (and the same with the game in fullscreen)
                tick = 0;
                return;
            }
            case 1400 -> {
                await("getting up gives the mouse and keyboard back", 20, () -> InGameMonitors.seated() == null);
            }
            case 15 -> {
                // an app you open from your desk (with monitors on, the game in front) opens on your desk
                if (tick < 20) return;
                try {
                    notepad = new ProcessBuilder("notepad.exe").start();
                } catch (java.io.IOException e) {
                    log("couldn't start Notepad: " + e);
                }
                await("an app opened from your desk opens on your desk, never on an in-game monitor", 20, () -> {
                    int[] r = notepadRect();
                    if (r == null) return false;
                    var prim = new int[] {0, 0, com.sun.jna.platform.win32.User32.INSTANCE.GetSystemMetrics(0), com.sun.jna.platform.win32.User32.INSTANCE.GetSystemMetrics(1)};
                    int cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
                    boolean home = cx >= prim[0] && cx < prim[2] && cy >= prim[1] && cy < prim[3];
                    if (tick % 20 == 0) log("Notepad at " + java.util.Arrays.toString(r) + (home ? " (your desk)" : " (NOT your desk)"));
                    return home && tick > 60; // (and it stays there a moment)
                });
            }
            case 16 -> {
                // a tablet: holding one turns it on, right-click holds it up
                mc.player.getInventory().selectedSlot = 8;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 8);
                await("holding a tablet turns it on", 60, () -> InGameMonitors.isOn(dev.windowscraft.client.Tablet.display()));
            }
            case 17 -> {
                if (tick < 20) return;
                if (notepad != null) notepad.destroy();
                checkDistinct("with the tablet on too, every screen has a monitor of its own");
                mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                await("right-click holds it up and you use it", 20, () -> dev.windowscraft.client.Tablet.raised() && InGameMonitors.seated() != null);
            }
            case 18 -> {
                if (tick < 60) return;
                shot(mc, "5_tablet_up");
                dev.windowscraft.client.Tablet.lower();
                mc.player.getInventory().selectedSlot = 0;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 0);
                // break the whole wall: its monitor goes
                int before = InGameMonitors.all().size();
                mc.getServer().execute(() -> {
                    var sw = mc.getServer().getOverworld();
                    for (int x = -2; x <= 2; x++) for (int y = 1; y <= 3; y++) sw.setBlockState(new BlockPos(x, ground + y, -6), Blocks.AIR.getDefaultState());
                });
                await("breaking a wall turns its monitor off", 30, () -> InGameMonitors.all().size() == before - 1);
            }
            case 19 -> {
                // a screen renamed: remembered with its settings (never what was on it), shown in the menu
                Display mb = at(mc, mon());
                if (mb == null) {
                    check(false, "the gaming monitor's screen is there to rename");
                    stage = 22;
                    return;
                }
                mb.name = "Movies";
                DisplayManager.get().save();
                String json = "";
                try {
                    json = java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("windowscraft").resolve("displays.json"));
                } catch (Exception e) {
                    log("couldn't read displays.json: " + e);
                }
                check(json.matches("(?s).*\"name\"\\s*:\\s*\"Movies\".*"), "a renamed screen's name is remembered with its settings");
                check(!json.matches("(?s).*\"last\"\\s*:.*"), "…and what was shown on a screen isn't remembered");
                mc.setScreen(new dev.windowscraft.ui.HubScreen(mb, -1));
                stage++;
                tick = 0;
            }
            case 20 -> {
                if (tick < 30) return;
                shot(mc, "7_menu_named");
                // the menu's own size: 150 %
                dev.windowscraft.config.ClientSettings.setHubScale(1.5);
                mc.setScreen(new dev.windowscraft.ui.HubScreen(at(mc, mon()), -1));
                stage++;
                tick = 0;
            }
            case 21 -> {
                if (tick < 30) return;
                shot(mc, "8_menu_150");
                mc.setScreen(dev.windowscraft.ui.HubScreen.settings());
                stage = 2100;
                tick = 0;
            }
            case 2100 -> {
                if (tick < 30) return;
                shot(mc, "9_settings_150");
                dev.windowscraft.config.ClientSettings.setHubScale(1.0);
                mc.setScreen(null);
                Display mb = at(mc, mon());
                if (mb != null) {
                    mb.name = null;
                    DisplayManager.get().save();
                }
                stage = 22;
                tick = 0;
            }
            case 22 -> {
                InGameMonitors.turnOffAll();
                await("everything turns off", 30, () -> InGameMonitors.all().isEmpty());
            }
            case 23 -> {
                if (tick < 40) return;
                shot(mc, "6_all_off");
                finish(mc);
            }
            default -> {}
        }
    }

    private int cycle;
    private Process notepad;

    private void checkDistinct(String what) {
        List<String> seen = new ArrayList<>();
        boolean ok = true;
        for (Display d : DisplayManager.get().all()) {
            var m = InGameMonitors.monitorOf(d);
            if (m == null) continue;
            String k = m.index() + " " + m.device();
            if (seen.contains(k)) ok = false;
            seen.add(k);
        }
        check(ok, what + " " + seen);
    }

    private void checkPictures(MinecraftClient mc, String what) {
        boolean ok = true;
        StringBuilder sb = new StringBuilder();
        for (Display d : DisplayManager.get().all()) {
            if (!InGameMonitors.isOn(d)) continue;
            // (a still desktop sends no new pictures: what counts is that it has one)
            var ses = d.session();
            boolean has = ses != null && ses.outputWidth() > 0 && ses.state() != dev.windowscraft.capture.CaptureSession.State.ENDED;
            if (!has) ok = false;
            sb.append(" | ").append(has ? "has a picture " : "NO PICTURE ").append(ses == null ? "" : ses.state() + " ").append(d.share().describe());
        }
        check(ok, what + sb);
    }

    private static int[] notepadRect() {
        var u = com.sun.jna.platform.win32.User32.INSTANCE;
        var h = u.FindWindow("Notepad", null);
        if (h == null) return null;
        var r = new com.sun.jna.platform.win32.WinDef.RECT();
        if (!u.GetWindowRect(h, r)) return null;
        return new int[] {r.left, r.top, r.right, r.bottom};
    }

    private void finish(MinecraftClient mc) {
        log(failures == 0 ? "WINTEST PASSED" : "WINTEST FAILED: " + failures + " failed");
        stage = 99;
        mc.scheduleStop();
    }
}
