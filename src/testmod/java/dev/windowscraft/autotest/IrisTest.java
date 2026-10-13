package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.render.ShaderCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

/**
 * With Sodium + Iris + a shader pack (run-iris/): a live screen with "Shaders on screens: Off"
 * (true colours), the same with shaders on, and a block in front of the screen (it must still
 * cover the screen in the true-colour path).
 */
public final class IrisTest implements ClientModInitializer {
    private int tick, ground;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("windowscraft.irisTest")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        try {
            switch (tick) {
                case 20 -> { if (mc.player.isDead()) mc.player.requestRespawn(); }
                case 40 -> {
                    ServerWorld sw = mc.getServer().getOverworld();
                    ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 20, 20) - 1;
                    mc.getServer().execute(() -> {
                        for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++)
                            for (int z = -6; z >= -8; z--) sw.setBlockState(new BlockPos(20 + x, ground + y, 20 + z),
                                    z == -8 ? Blocks.BLACK_CONCRETE.getDefaultState() : Blocks.AIR.getDefaultState());
                        sw.setTimeOfDay(6000);
                        mc.getServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                        var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                        sp.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
                        sp.setHealth(sp.getMaxHealth());
                        sp.teleport(sw, 20.5, ground + 1, 20.5, 180f, 0f);
                    });
                    ClientSettings.setScreensWithoutShaders(true);
                }
                case 80 -> {
                    String dim = mc.world.getRegistryKey().getValue().toString();
                    Display d = DisplayManager.get().create(dim, new WallRect(Direction.SOUTH, new BlockPos(17, ground + 1, 12), new BlockPos(23, ground + 4, 12)));
                    DisplayManager.get().start(d, SourceInfo.testPattern(), new CaptureSettings(720, 30, true, false));
                }
                case 300 -> {
                    log("shader pack in use: " + ShaderCompat.shaderPackInUse() + ", drawing after shaders: " + ShaderCompat.drawAfterShaders());
                    shot(mc, "iris_1_true_colours");
                }
                case 306, 312, 318, 324 -> { // small camera movements: specks show up when looking around
                    mc.player.setYaw(180f + (tick - 300) * 0.35f);
                    mc.player.setPitch((tick - 300) * 0.15f);
                }
                case 309, 315, 321, 327 -> shot(mc, "iris_1_wiggle_" + tick);
                case 330 -> {
                    mc.player.setYaw(180f);
                    mc.player.setPitch(0f);
                    ClientSettings.setScreensWithoutShaders(false);
                    var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    mc.getServer().execute(() -> sp.teleport(mc.getServer().getOverworld(), 20.5, ground + 1, 20.5, 180f, 0f));
                }
                case 360 -> shot(mc, "iris_2_shaded");
                case 340, 346, 352 -> { mc.player.setYaw(180f + (tick - 334) * 0.35f); mc.player.setPitch((tick - 334) * 0.15f); }
                case 343, 349, 355 -> shot(mc, "iris_2_wiggle_" + tick);
                case 362 -> { mc.player.setYaw(180f); mc.player.setPitch(0f); }
                case 365 -> {
                    ClientSettings.setScreensWithoutShaders(true);
                    mc.getServer().execute(() -> {
                        ServerWorld sw = mc.getServer().getOverworld();
                        for (int y = 1; y <= 3; y++) sw.setBlockState(new BlockPos(19, ground + y, 15), Blocks.OAK_LOG.getDefaultState());
                    });
                }
                case 420 -> shot(mc, "iris_3_block_in_front");
                case 425 -> mc.setScreen(dev.windowscraft.ui.SharePickerScreen.forDisplay(DisplayManager.get().all().get(0)));
                case 470 -> shot(mc, "iris_4_picker");
                case 480 -> {
                    mc.setScreen(null);
                    var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    mc.getServer().execute(() -> sp.teleport(mc.getServer().getOverworld(), 20.5, ground + 1, 18.5, 180f, 0f));
                }
                case 490 -> {}
                case 540 -> {
                    ClientSettings.setScreensWithoutShaders(true);
                    shot(mc, "iris_5_true_colours");
                }
                case 560 -> {
                    ClientSettings.setScreensWithoutShaders(false);
                }
                case 600 -> shot(mc, "iris_6_shaded");
                // steep angles from far off: the picture must stay in front of its concrete
                case 605 -> {
                    ClientSettings.setScreensWithoutShaders(true);
                    var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    mc.getServer().execute(() -> sp.teleport(mc.getServer().getOverworld(), 4.5, ground + 2, 15.0, -101.3f, 0f));
                }
                case 640, 643, 646 -> shot(mc, "iris_7_grazing_" + tick);
                case 650 -> {
                    var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    mc.getServer().execute(() -> sp.teleport(mc.getServer().getOverworld(), 18.5, ground + 1, 13.6, 200f, -55f));
                }
                case 680, 683, 686 -> shot(mc, "iris_8_below_" + tick);
                case 690 -> ClientSettings.setScreensWithoutShaders(false);
                case 720 -> shot(mc, "iris_9_below_shaded");
                case 730 -> {
                    log("IRIS TEST DONE");
                    mc.scheduleStop();
                }
                default -> {}
            }
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.error("[iris-test] failed at tick {}", tick, e);
            mc.scheduleStop();
        }
    }

    private static void shot(MinecraftClient mc, String name) {
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, name + ".png", mc.getFramebuffer(), m -> log(m.getString()));
    }

    private static void log(String s) {
        WindowsCraftClient.LOGGER.info("[iris-test] {}", s);
    }
}
