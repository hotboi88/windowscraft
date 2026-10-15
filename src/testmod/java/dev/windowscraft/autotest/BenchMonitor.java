package dev.windowscraft.autotest;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.client.MeasureCommand;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.measure.Measure;
import dev.windowscraft.monitor.InGameMonitors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

/**
 * Dev-only Step 0 benchmark of your own in-game monitor (singleplayer, real helper, real driver):
 * a 16×9 wall turned into an in-game monitor, the bench window (tools/bench) moved onto it, then
 * measured while standing in front of it and while sitting at it. -Dwindowscraft.bench.monitor=true,
 * {@code windowscraft.bench.seconds} per phase (default 45), {@code .name}; {@code .far=N}: also
 * measured standing N blocks from the wall (a screen across the room); {@code .noSit=true}: never
 * sits down (sitting takes the PC's mouse for a minute).
 */
public final class BenchMonitor implements ClientModInitializer {
    private int tick = -1, stage, ground;
    private long phaseEnd;
    private String name;
    private int seconds;
    private Display wall;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("windowscraft.bench.monitor") || Boolean.getBoolean("windowscraft.uishots")) return; // (its world, UiShots' run)
        seconds = Integer.getInteger("windowscraft.bench.seconds", 45);
        name = System.getProperty("windowscraft.bench.name", "monitor");
        dev.windowscraft.client.BusyGuard.enabled = false;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[bench monitor] {}", s); }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        long now = System.nanoTime();
        switch (stage) {
            case 0 -> {
                if (tick < 60) return;
                mc.options.getMaxFps().setValue(Integer.getInteger("windowscraft.bench.gameFps", 120));
                mc.options.getEnableVsync().setValue(false);
                var sw = mc.getServer().getOverworld();
                ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
                mc.getServer().execute(() -> {
                    for (BlockPos q : BlockPos.iterate(-10, ground + 1, -12, 10, ground + 12, 40)) sw.setBlockState(q, Blocks.AIR.getDefaultState());
                    for (int x = -8; x <= 7; x++) for (int y = 1; y <= 9; y++) sw.setBlockState(new BlockPos(x, ground + y, -10), Blocks.BLACK_CONCRETE.getDefaultState());
                    sw.setTimeOfDay(6000);
                });
                next();
            }
            case 1 -> {
                if (tick < 20) return;
                look(mc, 0.0, ground + 1, -1.0, new Vec3d(0.0, ground + 5.5, -9.5));
                next();
            }
            case 2 -> {
                if (tick < 10) return;
                // windowscraft.bench.monitorSize=WxH: the monitor at that size (as the Picture tab would set it)
                String size = System.getProperty("windowscraft.bench.monitorSize");
                if (size != null) {
                    String dim = mc.world.getRegistryKey().getValue().toString();
                    var rect = dev.windowscraft.display.WallDetector.detect(mc.world, new BlockPos(0, ground + 5, -10), net.minecraft.util.math.Direction.SOUTH, p -> false);
                    Display d = DisplayManager.get().create(dim, rect);
                    d.monitorW = Integer.parseInt(size.split("x")[0]);
                    d.monitorH = Integer.parseInt(size.split("x")[1]);
                    log("turning on a " + size + " monitor");
                    InGameMonitors.turnOn(mc, dim, rect, d);
                } else {
                    log("pressing U at the wall");
                    KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
                }
                next();
            }
            case 3 -> {
                wall = DisplayManager.get().all().stream().filter(InGameMonitors::isOn).findFirst().orElse(null);
                if (wall == null) {
                    if (tick > 20 * 60) { log("BENCH FAILED: no in-game monitor came on"); mc.scheduleStop(); stage = 99; }
                    return;
                }
                var mon = InGameMonitors.monitorOf(wall);
                // windowscraft.bench.lateWindow=S: leave the new monitor showing just its desktop for
                // S seconds first (does the "came out black" check misfire on a quiet new monitor?)
                if (mon == null || tick < Math.max(60, 20 * Integer.getInteger("windowscraft.bench.lateWindow", 0))) return;
                HWND w = User32.INSTANCE.FindWindow(null, "WindowsCraft bench window");
                if (w == null) { log("BENCH FAILED: the bench window isn't open"); mc.scheduleStop(); stage = 99; return; }
                User32.INSTANCE.SetWindowPos(w, null, mon.left(), mon.top(), 0, 0, 0x0001 | 0x0040); // SWP_NOSIZE | SWP_SHOWWINDOW
                log("monitor " + mon + "; moved the bench window onto it");
                next();
            }
            case 4 -> { // standing in front of it: warm up, then measure
                if (tick < 20 * 15) return;
                start(mc, name + "-standing");
                next();
            }
            case 5 -> {
                if (now < phaseEnd) return;
                stop();
                int far = Integer.getInteger("windowscraft.bench.far", 0);
                if (far > 0) { // step back across the room
                    look(mc, 0.0, ground + 1, -10.0 + far, new Vec3d(0.0, ground + 5.5, -9.5));
                    log("stepped back: " + far + " blocks from the wall");
                    stage = 20;
                    tick = 0;
                    return;
                }
                sitDown(mc);
            }
            case 20 -> {
                if (tick < 20 * 10) return;
                start(mc, name + "-far");
                stage = 21;
                tick = 0;
            }
            case 21 -> {
                if (now < phaseEnd) return;
                stop();
                look(mc, 0.0, ground + 1, -1.0, new Vec3d(0.0, ground + 5.5, -9.5));
                sitDown(mc);
            }
            case 6 -> {
                if (tick < 20 * 10) return;
                start(mc, name + "-seated");
                next();
            }
            case 7 -> {
                if (now < phaseEnd) return;
                stop();
                dev.windowscraft.client.TheaterMode.leaveNow();
                next();
            }
            case 8 -> {
                if (tick < 40) return;
                if (wall != null && InGameMonitors.isOn(wall)) InGameMonitors.turnOff(wall);
                next();
            }
            case 9 -> {
                if (tick < 100) return;
                log("BENCH MONITOR DONE");
                mc.scheduleStop();
                stage = 99;
            }
            default -> { }
        }
    }

    private void sitDown(MinecraftClient mc) {
        if (Boolean.getBoolean("windowscraft.bench.noSit")) {
            log("not sitting down (noSit)");
            stage = 8;
            tick = 0;
            return;
        }
        dev.windowscraft.client.TheaterMode.toggle(mc); // sit down at it
        log("sat down: seated " + (InGameMonitors.seated() != null));
        stage = 6;
        tick = 0;
    }

    private void start(MinecraftClient mc, String phase) {
        Measure.start("host-" + phase, MeasureCommand.folder());
        if (wall != null && wall.share() != null) {
            String how = wall.share().describe().replaceFirst("^'[^']*' ", "");
            Measure.note("wall", how);
            log(phase + ": " + how);
        }
        phaseEnd = System.nanoTime() + seconds * 1_000_000_000L;
    }

    private void stop() {
        if (wall != null && wall.share() != null) Measure.note("wall at the end", wall.share().describe().replaceFirst("^'[^']*' ", ""));
        log("BENCH REPORT " + Measure.stop());
    }

    private void next() {
        stage++;
        tick = 0;
    }

    private void look(MinecraftClient mc, double x, double y, double z, Vec3d target) {
        mc.player.setPosition(x, y, z);
        Vec3d to = target.subtract(mc.player.getEyePos());
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        mc.getServer().execute(() -> {
            ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayerList().get(0);
            p.teleport(mc.getServer().getOverworld(), x, y, z, yaw, pitch);
        });
    }
}
