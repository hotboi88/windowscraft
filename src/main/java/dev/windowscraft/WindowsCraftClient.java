package dev.windowscraft;

import dev.windowscraft.client.ClientNet;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallDetector;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.render.DisplayRenderer;
import dev.windowscraft.ui.SharePickerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "windowscraft";
    public static final Logger LOGGER = LoggerFactory.getLogger("WindowsCraft");
    /** How far away you can be from a wall when pressing the share key. */
    private static final double PICK_DISTANCE = 48;

    /** U is unbound in the COBBLEVERSE default keybindings (and in vanilla / Iris). */
    public static final KeyBinding SHARE_KEY = new KeyBinding(
            "key.windowscraft.share", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, "category.windowscraft");

    /** Theater mode. O is unbound in the COBBLEVERSE defaults too. */
    public static final KeyBinding THEATER_KEY = new KeyBinding(
            "key.windowscraft.theater", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.windowscraft");

    @Override
    public void onInitializeClient() {
        dev.windowscraft.client.MonitorPanelRenderer.register();
        KeyBindingHelper.registerKeyBinding(SHARE_KEY);
        KeyBindingHelper.registerKeyBinding(THEATER_KEY);
        ClientNet.init();
        dev.windowscraft.client.Report.register();
        dev.windowscraft.client.MeasureCommand.register(); // Step 0 measurements (off unless started)
        dev.windowscraft.client.Tablet.register();
        dev.windowscraft.client.Updater.register(); // updates from the server you play on (once you say yes)
        dev.windowscraft.render.GpuCrashGuard.checkAtStartup();
        // others' screens coloured on the graphics card: set up before the first one connects
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(mc -> dev.windowscraft.render.Nv12Converter.init());
        // a normal exit isn't a crash (only a sudden death leaves the marker behind)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            dev.windowscraft.render.GpuCrashGuard.disarm();
            dev.windowscraft.monitor.InGameMonitors.shutdown(); // in-game monitors go, their windows back to your desk
        });
        dev.windowscraft.audio.AudioCapture.restoreLeftovers();
        dev.windowscraft.capture.win.WinOps.recoverAfterCrash(); // windows a crash left just off the monitor go back
        dev.windowscraft.monitor.InGameMonitors.recoverAtStartup(); // and Windows' taskbar setting an in-game monitor changed
        ClientTickEvents.START_CLIENT_TICK.register(dev.windowscraft.client.TheaterMode::tick);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long tick0 = dev.windowscraft.measure.Measure.ON ? System.nanoTime() : 0;
            try {
                endTick(mc);
            } finally {
                CLIENT_TICK.since(tick0);
            }
        });
        registerRest();
    }

    private static final dev.windowscraft.measure.Measure.Metric CLIENT_TICK = dev.windowscraft.measure.Measure.ms("tick.wc_client",
            "WindowsCraft's work in each game tick (20 a second, game thread)");
    private static final dev.windowscraft.measure.Measure.Metric HUD = dev.windowscraft.measure.Measure.ms("frame.wc_hud",
            "the sit-down view and hints drawn over the game, per frame");

    private static void endTick(MinecraftClient mc) {
        while (THEATER_KEY.wasPressed()) if (mc.currentScreen == null) dev.windowscraft.client.TheaterMode.toggle(mc);
        while (SHARE_KEY.wasPressed()) onSharePressed(mc);
        DisplayManager.get().tick(mc);
        RemoteDisplays.tick(mc);
        dev.windowscraft.client.ScreenControl.tick(mc);
        dev.windowscraft.monitor.InGameMonitors.tick(mc);
        dev.windowscraft.client.Tablet.tick(mc);
        dev.windowscraft.client.BusyGuard.tick(mc);
        if (dev.windowscraft.capture.win.WinGeom.gameWindow == 0 && dev.windowscraft.capture.win.WinGeom.available()) {
            try {
                dev.windowscraft.capture.win.WinGeom.gameWindow = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getHandle());
                dev.windowscraft.capture.win.WinOps.gameWindow = dev.windowscraft.capture.win.WinGeom.gameWindow;
            } catch (Throwable t) {
                dev.windowscraft.capture.win.WinGeom.gameWindow = -1;
            }
        }
    }

    private static void registerRest() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> {
            DisplayManager.get().onJoin(mc);
            if (dev.windowscraft.render.GpuCrashGuard.consumeTripped() && mc.player != null) {
                mc.player.sendMessage(Text.literal("WindowsCraft: the game closed unexpectedly while \"Direct GPU sharing\" was starting, "
                        + "so it's off with this graphics driver (it's tried again after a driver update). Everything else works as before.").formatted(Formatting.YELLOW), false);
            }
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
            dev.windowscraft.client.ScreenControl.onDisconnect();
            dev.windowscraft.monitor.InGameMonitors.onDisconnect();
            DisplayManager.get().onDisconnect();
        }));
        WorldRenderEvents.AFTER_ENTITIES.register(DisplayRenderer::render);
        // no block outline on a screen: it should look like the picture is on the wall
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, hit) -> !dev.windowscraft.client.ScreenLook.isScreen(hit));
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((ctx, counter) -> {
            long t0 = dev.windowscraft.measure.Measure.ON ? System.nanoTime() : 0;
            dev.windowscraft.client.DeskView.render(ctx, counter);
            HUD.since(t0);
        });
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(dev.windowscraft.client.TheaterHint::render);
        LOGGER.info("WindowsCraft ready — look at a black concrete wall and press the share key (default U)");
    }

    public static String keyName() {
        return SHARE_KEY.getBoundKeyLocalizedText().getString();
    }

    private static void onSharePressed(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        HitResult hit = mc.player.raycast(PICK_DISTANCE, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            mc.setScreen(new dev.windowscraft.ui.HubScreen()); // all your screens and settings, from anywhere
            return;
        }
        String dim = mc.world.getRegistryKey().getValue().toString();
        DisplayManager dm = DisplayManager.get();
        Display existing = dm.findAt(dim, bhr.getBlockPos(), bhr.getSide());
        boolean menu = mc.player.isSneaking(); // sneak + U: the menu, never on/off
        boolean monitors = dev.windowscraft.monitor.InGameMonitors.supported();
        if (existing != null) {
            if (dev.windowscraft.monitor.InGameMonitors.isOn(existing)) {
                if (menu) mc.setScreen(new dev.windowscraft.ui.HubScreen(existing, dev.windowscraft.ui.HubScreen.TAB_WINDOWS));
                else dev.windowscraft.monitor.InGameMonitors.turnOff(existing);
            } else if (existing.isLive() || menu || !monitors) {
                mc.setScreen(SharePickerScreen.forDisplay(existing));
            } else if (dev.windowscraft.monitor.InGameMonitors.starting(existing)) {
                hint(mc, "Turning on an in-game monitor…");
            } else {
                dev.windowscraft.monitor.InGameMonitors.turnOn(mc, dim, existing.rect, existing);
            }
            return;
        }
        RemoteDisplays.Remote remote = RemoteDisplays.findAt(dim, bhr.getBlockPos(), bhr.getSide());
        if (remote != null) { // someone else's screen: how you hear it, volume, theater mode
            mc.setScreen(new dev.windowscraft.ui.RemoteOptionsScreen(remote));
            return;
        }
        var hitState = mc.world.getBlockState(bhr.getBlockPos());
        WallRect rect;
        if (hitState.getBlock() instanceof dev.windowscraft.block.MonitorBlock) { // a gaming monitor: its own screen
            rect = dev.windowscraft.block.MonitorBlock.rectAt(mc.world, bhr.getBlockPos());
        } else {
            if (!hitState.isOf(Blocks.BLACK_CONCRETE)) {
                mc.setScreen(new dev.windowscraft.ui.HubScreen());
                return;
            }
            if (!bhr.getSide().getAxis().isHorizontal()) {
                hint(mc, "Screens go on walls — look at the side of the concrete");
                return;
            }
            rect = WallDetector.detect(mc.world, bhr.getBlockPos(), bhr.getSide(),
                    p -> dm.isTaken(dim, bhr.getSide(), p) || RemoteDisplays.findAt(dim, p, bhr.getSide()) != null);
            if (rect == null) {
                hint(mc, "The front of that wall is covered");
                return;
            }
        }
        if (monitors && !menu) dev.windowscraft.monitor.InGameMonitors.turnOn(mc, dim, rect, null);
        else mc.setScreen(SharePickerScreen.forNewWall(dim, rect));
    }

    private static void hint(MinecraftClient mc, String msg) {
        mc.player.sendMessage(Text.literal(msg).formatted(Formatting.GRAY), true);
    }
}
