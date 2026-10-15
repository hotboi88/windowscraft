package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.ui.HubScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

/**
 * Dev only: screenshots of the settings pages (Performance in Automatic and Advanced, a screen's
 * Picture page with its own picks) at two GUI sizes, and a check that the picks do what they say.
 * -Dwindowscraft.uishots=true (singleplayer; the bench world works). Pictures in screenshots/.
 */
public final class UiShots implements ClientModInitializer {
    private int tick = -1, ground;
    private Display wall;
    private boolean wasAdvanced;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("windowscraft.uishots")) return;
        dev.windowscraft.client.BusyGuard.enabled = false;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[uishots] {}", s); }

    private void shot(MinecraftClient mc, String name) {
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, "ui_" + name + ".png", mc.getFramebuffer(), m -> log(m.getString()));
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok: " : "CHECK FAILED: ") + what);
    }

    private static void scroll(HubScreen h, double v) {
        try {
            var f = HubScreen.class.getDeclaredField("scroll");
            f.setAccessible(true);
            f.setDouble(h, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        String dim = mc.world.getRegistryKey().getValue().toString();
        switch (tick) {
            case 60 -> {
                wasAdvanced = ClientSettings.perfAdvanced();
                var sw = mc.getServer().getOverworld();
                ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
                mc.getServer().execute(() -> {
                    for (int x = -3; x <= 2; x++) for (int y = 1; y <= 4; y++) sw.setBlockState(new BlockPos(x, ground + y, -6), Blocks.BLACK_CONCRETE.getDefaultState());
                });
            }
            case 80 -> {
                wall = DisplayManager.get().create(dim, new WallRect(Direction.SOUTH, new BlockPos(-3, ground + 1, -6), new BlockPos(2, ground + 4, -6)));
                DisplayManager.get().start(wall, dev.windowscraft.capture.SourceInfo.testPattern(), new CaptureSettings(1080, 30, true));
                mc.player.setPitch(0);
                mc.player.setYaw(180);
            }
            case 120 -> {
                ClientSettings.setPerfAdvanced(false);
                HubScreen h = HubScreen.settings();
                mc.setScreen(h);
                scroll(h, 70);
            }
            case 130 -> shot(mc, "settings_automatic");
            case 132 -> ClientSettings.setPerfAdvanced(true);
            case 140 -> shot(mc, "settings_advanced");
            case 142 -> {
                // the picks do what they say
                ClientSettings.setPick(ClientSettings.Auto.SHARP_AS_SEEN, false);
                check(!ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN), "Advanced, Off: off");
                ClientSettings.setPerfAdvanced(false);
                check(ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN), "back to Automatic: on again (the pick is kept, not used)");
                ClientSettings.setPerfAdvanced(true);
                check(!ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN), "Advanced again: the pick is back");
                ClientSettings.setPick(ClientSettings.Auto.SHARP_AS_SEEN, null);
                check(ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN), "Advanced, Automatic: as Automatic");
                wall.overrides.put(ClientSettings.Auto.SHARP_AS_SEEN.key, "false");
                check(!ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN, wall) && ClientSettings.on(ClientSettings.Auto.SHARP_AS_SEEN),
                        "a screen's own pick comes first (only for that screen)");
                DisplayManager.get().save();
            }
            case 150 -> {
                HubScreen h = new HubScreen(wall, HubScreen.TAB_PICTURE);
                mc.setScreen(h);
                scroll(h, 1000); // (to its end)
            }
            case 160 -> shot(mc, "picture_own_wall");
            case 162 -> {
                mc.options.getGuiScale().setValue(3);
                mc.onResolutionChanged();
                HubScreen h = HubScreen.settings();
                mc.setScreen(h);
                scroll(h, 60);
            }
            case 172 -> shot(mc, "settings_advanced_gui3");
            case 174 -> {
                HubScreen h = new HubScreen(wall, HubScreen.TAB_PICTURE);
                mc.setScreen(h);
                scroll(h, 1000);
            }
            case 184 -> shot(mc, "picture_own_wall_gui3");
            case 190 -> {
                mc.setScreen(null);
                mc.options.getGuiScale().setValue(2);
                wall.overrides.clear();
                ClientSettings.setPerfAdvanced(wasAdvanced);
                DisplayManager.get().remove(wall);
                log("UI SHOTS DONE");
                mc.scheduleStop();
            }
            default -> { }
        }
    }
}
