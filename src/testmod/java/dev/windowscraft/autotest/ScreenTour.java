package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureBackends;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallDetector;
import dev.windowscraft.display.WallRect;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * Dev only: how a screen sits on its block, in pictures — a 16 × 9 wall screen and a gaming
 * monitor block, both showing the bench window (tools/bench), seen from a few spots (straight
 * on, steep, far), each saved as the game's own screenshot (shader pack and all) to
 * {@code screenshots/tour-NAME-SPOT.png}. Single player (tools/prodclient's test world, BW=1):
 * {@code -Dwindowscraft.tour=NAME}.
 */
public final class ScreenTour implements ClientModInitializer {
    private static final String NAME = System.getProperty("windowscraft.tour");
    private int tick = -1, stage, ground, spot;
    private BlockPos monitor;

    private record Spot(String name, double x, double z, double tx, double ty, double tz) {}
    private List<Spot> spots;

    @Override
    public void onInitializeClient() {
        if (NAME == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s) { WindowsCraftClient.LOGGER.info("[tour] {}", s); }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        switch (stage) {
            case 0 -> {
                if (tick < 60) return;
                mc.options.hudHidden = true;
                mc.options.getEnableVsync().setValue(false);
                var sw = mc.getServer().getOverworld();
                ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
                monitor = new BlockPos(10, ground + 1, -6);
                var mon = Registries.BLOCK.get(Identifier.of("windowscraft", "black_gaming_monitor")).getDefaultState()
                        .with(dev.windowscraft.block.MonitorBlock.TURN, 0); // (facing south, toward the spots)
                mc.getServer().execute(() -> {
                    for (BlockPos q : BlockPos.iterate(-12, ground + 1, -12, 30, ground + 14, 45)) sw.setBlockState(q, Blocks.AIR.getDefaultState());
                    for (int x = -8; x <= 7; x++) for (int y = 1; y <= 9; y++) sw.setBlockState(new BlockPos(x, ground + y, -10), Blocks.BLACK_CONCRETE.getDefaultState());
                    sw.setBlockState(monitor, mon);
                    sw.setTimeOfDay(6000);
                    sw.setWeather(6000, 0, false, false);
                });
                double g = ground + 1;
                Vec3d wall = new Vec3d(0, g + 4.5, -9), mid = new Vec3d(10.5, g + 0.6, -5.45);
                spots = List.of(
                        new Spot("wall-front-4", 0.5, -5, wall.x, wall.y, wall.z),
                        new Spot("wall-front-16", 0.5, 7, wall.x, wall.y, wall.z),
                        new Spot("wall-steep-near", 8.7, -8.3, -4, wall.y, wall.z),
                        new Spot("wall-steep-far", 26, -4, wall.x, wall.y, wall.z),
                        new Spot("wall-far-40", 0.5, 31, wall.x, wall.y, wall.z),
                        new Spot("mon-front-2", 10.5, -3.6, mid.x, mid.y, mid.z),
                        new Spot("mon-front-1", 10.5, -4.4, mid.x, mid.y, mid.z),
                        new Spot("mon-45", 12.2, -3.8, mid.x, mid.y, mid.z),
                        new Spot("mon-steep", 12.9, -5.1, mid.x, mid.y, mid.z),
                        new Spot("mon-far-12", 10.5, 6, mid.x, mid.y, mid.z));
                next();
            }
            case 1 -> {
                if (tick < 40) return;
                SourceInfo src = CaptureBackends.get().listWindows().stream().filter(s -> s.title().equals("WindowsCraft bench window")).findFirst().orElse(null);
                if (src == null) { log("TOUR FAILED: the bench window isn't open"); mc.scheduleStop(); stage = 99; return; }
                String dim = mc.world.getRegistryKey().getValue().toString();
                WallRect wallRect = WallDetector.detect(mc.world, new BlockPos(0, ground + 5, -10), Direction.SOUTH, p -> false);
                WallRect monRect = dev.windowscraft.block.MonitorBlock.rectAt(mc.world, monitor);
                if (wallRect == null || monRect == null) {
                    if (tick > 400) { log("TOUR FAILED: wall " + wallRect + ", monitor " + monRect); mc.scheduleStop(); stage = 99; }
                    return;
                }
                for (WallRect r : new WallRect[]{wallRect, monRect}) {
                    Display d = DisplayManager.get().create(dim, r);
                    DisplayManager.get().start(d, src, new CaptureSettings(1080, 60, true, false));
                }
                log("sharing the bench window on the wall and on the monitor block");
                next();
            }
            case 2 -> {
                if (tick >= 140) { spot = 0; next(); }
            }
            case 3 -> {
                Spot s = spots.get(spot);
                if (tick == 1) { // (the server's teleport: moving the player on this side only gets put back)
                    var sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
                    double sx = s.x, sy = ground + 1, sz = s.z;
                    if (sp != null) mc.getServer().execute(() -> sp.networkHandler.requestTeleport(sx, sy, sz, sp.getYaw(), sp.getPitch()));
                }
                look(mc, s.x, ground + 1, s.z, new Vec3d(s.tx, s.ty, s.tz));
                if (tick == 50) {
                    String file = "tour-" + NAME + "-" + s.name + ".png";
                    ScreenshotRecorder.saveScreenshot(mc.runDirectory, file, mc.getFramebuffer(), t -> {});
                    log("picture " + file);
                }
                if (tick >= 56) {
                    tick = 0;
                    if (++spot >= spots.size()) stage = 4;
                }
            }
            case 4 -> {
                if (tick < 40) return;
                log("TOUR DONE");
                mc.scheduleStop();
                stage = 99;
            }
            default -> { }
        }
    }

    private void next() {
        stage++;
        tick = 0;
    }

    private static void look(MinecraftClient mc, double x, double y, double z, Vec3d target) {
        mc.player.setPosition(x, y, z);
        mc.player.setVelocity(Vec3d.ZERO);
        Vec3d to = target.subtract(mc.player.getEyePos());
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        mc.player.prevYaw = mc.player.getYaw();
        mc.player.prevPitch = mc.player.getPitch();
    }
}
