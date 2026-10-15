package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureBackends;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.MeasureCommand;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallDetector;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.measure.Measure;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Dev-only Step 0 benchmark (no checks, just steady numbers): with the multiplayer test's server
 * and players, the sharer streams one screen at fixed settings and the viewer watches it from in
 * front of the wall; both measure for a fixed time and quit. Properties:
 * {@code windowscraft.bench.role} sharer|viewer|idle, {@code .source} pattern|window (the bench
 * window from tools/bench)|monitor (an in-game monitor with the bench window on it), {@code .height}, {@code .fps}, {@code .seconds}, {@code .warmup},
 * {@code .name} (in the report's file name). "idle" measures a player with the mod and nothing on.
 */
public final class BenchTest implements ClientModInitializer {
    private String role, name, source;
    private int height, fps, seconds, warmup, tick = -1;
    private long measureFrom = -1, measureUntil = -1;
    private boolean started, switched;

    @Override
    public void onInitializeClient() {
        role = System.getProperty("windowscraft.bench.role");
        if (role == null) return;
        source = System.getProperty("windowscraft.bench.source", "pattern");
        height = Integer.getInteger("windowscraft.bench.height", 720);
        fps = Integer.getInteger("windowscraft.bench.fps", 30);
        seconds = Integer.getInteger("windowscraft.bench.seconds", 60);
        warmup = Integer.getInteger("windowscraft.bench.warmup", 20);
        name = System.getProperty("windowscraft.bench.name", source + "-" + height + "p" + fps);
        dev.windowscraft.client.BusyGuard.enabled = false; // (steady settings: nothing turns itself down mid-run)
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // measuring from the very start, so pictures in and out of FFmpeg can be matched; the
        // warm-up goes into a report of its own (rolled over when the real window starts)
        Measure.start(role + "-" + name + "-warmup", MeasureCommand.folder());
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[bench {}] {}", role, s); }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tick++;
        // the test players' windows are often behind other windows: no pause menu over the game
        // (it would swallow the screen's input and change what's drawn)
        mc.options.pauseOnLostFocus = false;
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) mc.setScreen(null);
        if (tick == 40) {
            mc.options.getMaxFps().setValue(Integer.getInteger("windowscraft.bench.gameFps", 260));
            mc.options.getEnableVsync().setValue(false);
            mc.options.getViewDistance().setValue(8);
        }
        // the sharer goes live once its wall is there (a big modpack sends the world over slowly: look again each second)
        if (role.equals("sharer") && !live && tick >= 40 && tick % 20 == 0 && tick <= 20 * 120) live = goLive(mc, tick == 20 * 120);
        if (role.equals("viewer") && tick > 40 && tick % 20 == 0 && !dev.windowscraft.client.TheaterMode.active()) faceTheWall(mc);
        if (monitorWall != null && dev.windowscraft.monitor.InGameMonitors.monitorOf(monitorWall) != null) {
            var mon = dev.windowscraft.monitor.InGameMonitors.monitorOf(monitorWall);
            var w = com.sun.jna.platform.win32.User32.INSTANCE.FindWindow(null, "WindowsCraft bench window");
            if (w != null) {
                com.sun.jna.platform.win32.User32.INSTANCE.SetWindowPos(w, null, mon.left(), mon.top(), 0, 0, 0x0001 | 0x0040); // SWP_NOSIZE | SWP_SHOWWINDOW
                log("in-game monitor " + mon + " on; moved the bench window onto it");
            } else log("BENCH FAILED: the bench window isn't open (tools/bench)");
            monitorWall = null;
        }
        if (INPUT) input(mc);
        long now = System.nanoTime();
        if (tick == 60) {
            measureFrom = now + warmup * 1_000_000_000L;
            measureUntil = measureFrom + seconds * 1_000_000_000L;
            log("measuring '" + name + "' from " + warmup + " s for " + seconds + " s");
        }
        // windowscraft.bench.switchHeight=H@S: the sharer changes the stream to height H, S seconds in
        String sw = System.getProperty("windowscraft.bench.switchHeight");
        if (sw != null && role.equals("sharer") && !switched && measureFrom > 0
                && now >= measureFrom + Long.parseLong(sw.split("@")[1]) * 1_000_000_000L && !DisplayManager.get().all().isEmpty()) {
            switched = true;
            Display d = DisplayManager.get().all().get(0);
            DisplayManager.get().updateSettings(d, new CaptureSettings(Integer.parseInt(sw.split("@")[0]), fps, true, source.equals("pattern")));
            log("switched the stream to height " + sw.split("@")[0]);
        }
        if (role.equals("viewer") && tick % 200 == 0) {
            RemoteDisplays.Remote r = RemoteDisplays.all().stream().findFirst().orElse(null);
            if (r != null && r.subscriber() != null) log("decoding at " + r.subscriber().decodeWidth() + "x" + r.subscriber().decodeHeight()
                    + " (stream " + r.info.width() + "x" + r.info.height() + ")");
        }
        if (measureFrom > 0 && now >= measureFrom && !started && measureUntil > 0) {
            started = true;
            Measure.rollover(role + "-" + name);
            if (role.equals("sharer")) for (Display d : DisplayManager.get().all())
                if (d.publisher() != null) log("publisher: " + d.publisher().status());
        }
        if (measureUntil > 0 && now >= measureUntil && Measure.ON) {
            if (role.equals("viewer")) {
                RemoteDisplays.Remote r = RemoteDisplays.all().stream().findFirst().orElse(null);
                if (r != null && r.subscriber() != null) log("viewer: " + r.subscriber().status() + " · " + r.subscriber().transport());
            }
            var p = Measure.stop();
            log("BENCH REPORT " + p);
            measureUntil = -1;
            try { new java.io.File(mc.runDirectory, "bench-" + role + "-done").createNewFile(); } catch (java.io.IOException ignored) {}
            if (!role.equals("sharer")) mc.scheduleStop();
        }
        // the sharer stays until the viewer is done (or 10 minutes)
        if (role.equals("sharer") && measureUntil < 0 && measureFrom > 0
                && (new java.io.File(mc.runDirectory.getParentFile(), VIEWER_DIR + "/bench-viewer-done").exists() || tick > 20 * 600)) {
            log("BENCH SHARER DONE");
            mc.scheduleStop();
            measureFrom = -1;
        }
    }

    /** The viewer's game folder, next to the sharer's (run-bob; tools/prodclient's copies have their own). */
    private static final String VIEWER_DIR = System.getProperty("windowscraft.bench.viewerDir", "run-bob");
    private static final boolean INPUT = Boolean.getBoolean("windowscraft.bench.input") || "1".equals(System.getProperty("windowscraft.bench.input")); // (run.sh passes BENCH_INPUT=1)
    private boolean allowed, seated;
    private Display monitorWall;
    private RemoteDisplays.Remote ctrl;

    /** Input benchmark: the viewer asks to use the screen, the sharer allows it, the viewer sits and uses it. */
    private void input(MinecraftClient mc) {
        if (role.equals("sharer")) {
            java.io.File wants = new java.io.File(mc.runDirectory.getParentFile(), VIEWER_DIR + "/bench-wants-control");
            if (!allowed && wants.exists() && mc.getNetworkHandler() != null && !DisplayManager.get().all().isEmpty()) {
                for (var e : mc.getNetworkHandler().getPlayerList()) {
                    if (e.getProfile().getId().equals(mc.player.getUuid())) continue;
                    DisplayManager.get().setController(DisplayManager.get().all().get(0), e.getProfile().getId(), e.getProfile().getName(), true);
                    log("let " + e.getProfile().getName() + " use the screen");
                    allowed = true;
                }
            }
            return;
        }
        if (!role.equals("viewer")) return;
        if (tick == 60) {
            try { new java.io.File(mc.runDirectory, "bench-wants-control").createNewFile(); } catch (java.io.IOException ignored) {}
        }
        if (!seated && tick > 60) {
            RemoteDisplays.Remote r = RemoteDisplays.all().stream().filter(RemoteDisplays.Remote::controllableByMe).findFirst().orElse(null);
            if (r != null) {
                ctrl = r;
                dev.windowscraft.client.ClientNet.control(r.id, dev.windowscraft.net.Payloads.OP_SIT, 0.5, 0.5, 0, 0);
                seated = true;
                log("sitting at the shared screen, using it");
            }
        }
        if (seated && ctrl != null) {
            // the messages sitting at it sends (the network, server and host parts are what's timed):
            // the pointer three times a tick (~60 a second, like the real ≤66/s), a click each second
            double a = tick * 0.15;
            for (int k = 0; k < 3; k++)
                dev.windowscraft.client.ClientNet.control(ctrl.id, dev.windowscraft.net.Payloads.OP_MOVE, 0.5 + 0.3 * Math.cos(a + k * 0.05), 0.5 + 0.3 * Math.sin(a + k * 0.05), 0, 0);
            if (tick % 20 == 0) {
                log("click sent " + System.nanoTime()); // (tools/bench/clicks.py matches it with the window's "pressed")
                dev.windowscraft.client.ClientNet.control(ctrl.id, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0.5, 0.5, 0, 0);
            }
            if (tick % 20 == 2) dev.windowscraft.client.ClientNet.control(ctrl.id, dev.windowscraft.net.Payloads.OP_BUTTON_UP, 0.5, 0.5, 0, 0);
        }
    }

    private boolean live;

    /** True once it went live (or failed for good); false = no wall yet, try again. */
    private boolean goLive(MinecraftClient mc, boolean last) {
        String dim = mc.world.getRegistryKey().getValue().toString();
        int g = AutoTestServerGround.ground(mc);
        WallRect rect = WallDetector.detect(mc.world, new BlockPos(0, g + 2, -8), Direction.SOUTH, p -> false);
        if (rect == null) {
            if (last) log("BENCH FAILED: no wall");
            return last;
        }
        Display d = DisplayManager.get().create(dim, rect);
        if (source.equals("monitor")) {
            d.monitorW = height * 16 / 9;
            d.monitorH = height;
            dev.windowscraft.monitor.InGameMonitors.turnOn(mc, dim, rect, d);
            monitorWall = d;
            log("turning on an in-game monitor (" + d.monitorW + "x" + d.monitorH + ")");
            return true;
        }
        SourceInfo src = SourceInfo.testPattern();
        if (source.equals("window")) {
            src = CaptureBackends.get().listWindows().stream().filter(s -> s.title().equals("WindowsCraft bench window")).findFirst().orElse(null);
            if (src == null) { log("BENCH FAILED: the bench window isn't open (tools/bench)"); return true; }
        }
        DisplayManager.get().start(d, src, new CaptureSettings(height, fps, true, source.equals("pattern")));
        log("went live: " + src.title() + " at " + height + "p" + fps);
        return true;
    }

    /** Look straight at the middle of the 7×4 wall. */
    private void faceTheWall(MinecraftClient mc) {
        int g = AutoTestServerGround.ground(mc);
        var eye = mc.player.getEyePos();
        double dx = 0.5 - eye.x, dy = g + 3.0 - eye.y, dz = -7.5 - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        // windowscraft.bench.lookAway: turn your back on it (is a screen nobody looks at still decoded?)
        // (…and windowscraft.bench.lookBackAfter=S: face it again S seconds into the measuring)
        int back = Integer.getInteger("windowscraft.bench.lookBackAfter", -1);
        boolean away = Boolean.getBoolean("windowscraft.bench.lookAway")
                && !(back >= 0 && measureFrom > 0 && System.nanoTime() > measureFrom + back * 1_000_000_000L);
        mc.player.setYaw(away ? yaw + 180 : yaw);
        mc.player.setPitch(pitch);
    }

    /** The test world's ground level, as seen from a client (the server builds the wall on it). */
    static final class AutoTestServerGround {
        static int ground(MinecraftClient mc) {
            // the wall stands at z = -8 on the flat world's surface, right under the players' feet
            return mc.player.getBlockPos().getY() - 1;
        }
    }
}
