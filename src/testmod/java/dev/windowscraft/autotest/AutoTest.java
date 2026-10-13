package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureBackends;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import java.io.File;
import java.lang.reflect.Field;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

/**
 * Scripted end-to-end check (dev only): builds walls in a flat world, opens the share dialog
 * with the share key, picks sources with real mouse clicks, goes live and screenshots each step.
 */
public final class AutoTest implements ClientModInitializer {
    private final java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final dev.windowscraft.monitor.FakeMonitorBackend fakeMonitors = new dev.windowscraft.monitor.FakeMonitorBackend();

    private Display south() {
        for (Display d : DisplayManager.get().all()) if (d.rect.face() == Direction.SOUTH && !d.rect.isMonitor()) return d;
        return null;
    }

    /** A click on the south wall at a spot of its picture, the way Alt + click does it. */
    /** Share of sampled spots where mipmap level 1 isn't the 2×2 average of level 0. */
    private static double mipMismatch(int tex) {
        if (tex == 0) return 1;
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex);
        int w0 = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int h0 = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        int w1 = Math.max(1, w0 / 2), h1 = Math.max(1, h0 / 2);
        java.nio.ByteBuffer l0 = org.lwjgl.system.MemoryUtil.memAlloc(w0 * h0 * 4), l1 = org.lwjgl.system.MemoryUtil.memAlloc(w1 * h1 * 4);
        try {
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, l0);
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 1, org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, l1);
            java.util.Random rnd = new java.util.Random(1);
            int bad = 0, n = 400;
            for (int i = 0; i < n; i++) {
                int x = rnd.nextInt(w1), y = rnd.nextInt(h1);
                for (int c = 0; c < 3; c++) {
                    int sum = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                        int xx = Math.min(w0 - 1, 2 * x + dx), yy = Math.min(h0 - 1, 2 * y + dy);
                        sum += l0.get((yy * w0 + xx) * 4 + c) & 0xFF;
                    }
                    if (Math.abs(sum / 4 - (l1.get((y * w1 + x) * 4 + c) & 0xFF)) > 40) { bad++; break; }
                }
            }
            return bad / (double) n;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(l0);
            org.lwjgl.system.MemoryUtil.memFree(l1);
        }
    }

    private void altClickAt(MinecraftClient mc, double u, double v) {
        dev.windowscraft.client.ScreenControl.pointer(south())[0] = u;
        dev.windowscraft.client.ScreenControl.pointer(south())[1] = v;
        dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_PRESS);
        dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_RELEASE);
    }

    static final class KeyBindingHelperAccess {
        static InputUtil.Key bound(KeyBinding k) { return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(k); }
    }


    private int tick = -1;
    private int ground;
    private boolean fake;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("windowscraft.autotest")) return;
        fake = !Boolean.getBoolean("windowscraft.autotest.realBackend");
        if (fake) CaptureBackends.overrideForTesting(new FakeBackend());
        // start from a clean slate: no walls or choices remembered from an earlier run
        new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "windowscraft/displays.json").delete();
        new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "windowscraft/settings.json").delete();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[autotest] {}", s); }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.getServer() == null) return;
        tick++;
        try {
            step(mc);
        } catch (Throwable t) {
            WindowsCraftClient.LOGGER.error("[autotest] FAILED at tick {}", tick, t);
            mc.scheduleStop();
        }
    }

    private void step(MinecraftClient mc) throws Exception {
        switch (tick) {
            case 20 -> { // a run that ended with the player dead leaves the world on the death screen
                if (mc.player.isDead()) {
                    mc.player.requestRespawn();
                    mc.setScreen(null);
                }
            }
            case 40 -> buildScene(mc);
            case 45 -> {
                if (Boolean.getBoolean("windowscraft.autotest.quick")) { // straight to the tablet
                    dev.windowscraft.monitor.InGameMonitors.useForTesting(fakeMonitors);
                    tick = 1575;
                }
            }
            case 100 -> {
                dev.windowscraft.apps.InputBridge.testSink = sent::add;
                log("pressing U while looking at the south wall");
                KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            }
            case 110 -> check(mc.currentScreen instanceof dev.windowscraft.ui.SharePickerScreen, "U on black concrete opens the share picker");
            case 160 -> shot(mc, "01_picker_apps");
            case 165 -> {
                mc.currentScreen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); // → Screens
                check(intField(mc.currentScreen, "tab") == 1, "Tab goes to Screens");
            }
            case 180 -> mc.currentScreen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); // → Applications
            case 195 -> clickCard(mc, 0);
            case 205 -> {
                log("selected before Enter: " + intFieldOrNull(mc.currentScreen, "selectedId") + ", tab " + intField(mc.currentScreen, "tab"));
                mc.currentScreen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                log("after Enter: screen " + mc.currentScreen + ", south " + south() + ", live " + (south() != null && south().isLive()));
                check(mc.currentScreen == null && south() != null && south().isLive(), "Enter shares the window on the wall");
            }
            case 240 -> {
                shot(mc, "02_shared_window");
                check(!dev.windowscraft.client.ScreenControl.pointerVisible(south()), "no pointer while nobody points with Alt");
                dev.windowscraft.client.ScreenControl.testAlt = true;
            }
            case 245 -> {
                check(dev.windowscraft.client.ScreenControl.pointed() == south(), "looking at the wall points at it");
                check(dev.windowscraft.client.ScreenControl.pointerVisible(south()), "holding Alt shows the pointer");
                sent.clear();
                altClickAt(mc, 0.5, 0.5);
                check(sent.stream().anyMatch(l -> l.startsWith("button l down")) && sent.stream().anyMatch(l -> l.startsWith("button l up")),
                        "Alt + click goes to the shared window: " + sent);
                check(!mc.options.attackKey.isPressed(), "…and doesn't mine the wall");
                dev.windowscraft.client.ScreenControl.onScroll(mc, 1);
                check(sent.stream().anyMatch(l -> l.startsWith("wheel 1.000")), "Alt + wheel scrolls it");
            }
            case 250 -> shot(mc, "02b_pointer");
            case 255 -> {
                dev.windowscraft.client.ScreenControl.testAlt = false;
                sent.clear();
                check(!dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_PRESS) || sent.isEmpty(), "a plain click on the wall sends nothing");
                dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_RELEASE);
            }
            case 260 -> check(!dev.windowscraft.client.ScreenControl.pointerVisible(south()), "the pointer hides again without Alt");
            case 262 -> {
                // another player: only if you allowed them, and keys only from the chair
                java.util.UUID bob = java.util.UUID.randomUUID();
                java.util.function.BiConsumer<Integer, Integer> guest = (op, a) -> dev.windowscraft.client.ScreenControl.fromGuest(mc,
                        new dev.windowscraft.net.Payloads.ControlFrom(bob, "Bob", new dev.windowscraft.net.Payloads.Control(south().id, op, 0.3f, 0.4f, a, 0)));
                sent.clear();
                guest.accept(dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0);
                guest.accept(dev.windowscraft.net.Payloads.OP_BUTTON_UP, 0);
                check(sent.isEmpty() && !dev.windowscraft.client.ScreenControl.pointerVisible(south()), "a player you didn't allow can't use your screen: " + sent);
                DisplayManager.get().setController(south(), bob, "Bob", true);
                guest.accept(dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0);
                guest.accept(dev.windowscraft.net.Payloads.OP_BUTTON_UP, 0);
                check(sent.stream().anyMatch(l -> l.startsWith("button l down 0.3")), "a player you allowed can click it: " + sent);
                check(dev.windowscraft.client.ScreenControl.pointerVisible(south()), "…and the pointer shows while they use it");
                check(dev.windowscraft.client.ScreenControl.guests(south()).contains("Bob"), "…and the menu can say who's using it");
                sent.clear();
                guest.accept(dev.windowscraft.net.Payloads.OP_KEY_DOWN, 17);
                check(!sent.contains("key down 17"), "their keys only count once they sit down");
                guest.accept(dev.windowscraft.net.Payloads.OP_SIT, 0);
                guest.accept(dev.windowscraft.net.Payloads.OP_KEY_DOWN, 17);
                guest.accept(dev.windowscraft.net.Payloads.OP_KEY_UP, 17);
                check(sent.contains("focus") && sent.contains("key down 17") && sent.contains("key up 17"), "sitting, their keys reach the app: " + sent);
                sent.clear();
                DisplayManager.get().setController(south(), bob, "Bob", false);
                check(sent.contains("release") && sent.contains("blur"), "taking it away lets go of everything they held: " + sent);
                check(!dev.windowscraft.client.ScreenControl.pointerVisible(south()), "…and the pointer hides");
                sent.clear();
                guest.accept(dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0);
                check(sent.isEmpty(), "after that they can't use it any more");
                DisplayManager.get().setController(south(), bob, "Bob", true); // saved with the wall
            }
            case 266 -> {
                try {
                    String json = java.nio.file.Files.readString(new File(mc.runDirectory, "config/windowscraft/displays.json").toPath());
                    check(json.contains("\"controllers\"") && json.contains("Bob"), "who may use a wall is saved with it");
                } catch (java.io.IOException e) { check(false, "displays.json readable"); }
                java.util.UUID bob = south().controllers.keySet().iterator().next();
                DisplayManager.get().setController(south(), bob, "Bob", false);
            }
            // the wall changes while live: the screen follows it, and is never left hanging over air
            case 268 -> mc.getServer().execute(() -> {
                for (int x = -3; x <= 3; x++) mc.getServer().getOverworld().setBlockState(new BlockPos(x, ground + 1, -8), Blocks.STONE.getDefaultState());
            });
            case 298 -> {
                check(south() != null && south().isLive() && south().rect.heightBlocks() == 3 && south().rect.min().getY() == ground + 2,
                        "taking the bottom row away shrinks the screen to its wall and it stays live (" + (south() == null ? "gone" : south().rect) + ")");
                mc.getServer().execute(() -> {
                    for (int x = -3; x <= 3; x++) mc.getServer().getOverworld().setBlockState(new BlockPos(x, ground + 1, -8), Blocks.BLACK_CONCRETE.getDefaultState());
                });
            }
            case 343 -> check(south() != null && south().isLive() && south().rect.heightBlocks() == 4,
                    "building the row back grows the screen again (" + (south() == null ? "gone" : south().rect) + ")");
            case 320 -> look(mc, 4.6, ground + 1, -6.2, 143, 8); // right next to the wall's edge, looking along it
            case 345 -> {
                shot(mc, "04a_flush_edge_closeup");
                check(dev.windowscraft.client.ScreenLook.isScreen(mc.crosshairTarget), "close up, the block you aim at is a screen (no outline drawn)");
                mc.player.setYaw(0f); // turn around, away from the wall
                mc.player.setPitch(0f);
            }
            case 358 -> check(!south().share().seenRecently(), "turned away from the wall: its per-frame work pauses (off-screen pause)");
            case 360 -> check(!dev.windowscraft.client.ScreenLook.lookingAtScreen(1f), "crosshair back when looking away from the screen");
            case 362 -> look(mc, 0.5, ground + 1, 0.5, 180, 0);
            case 380 -> {
                check(dev.windowscraft.client.ScreenLook.lookingAtScreen(1f), "no crosshair looking at a screen from across the room");
                check(south().share().seenRecently(), "looking at it again: it updates again");
                shot(mc, "04_screen_front");
                look(mc, 6.5, ground + 1, -2.5, 120, 5); // off to the side, looking back at the wall
            }
            case 402 -> shot(mc, "05_screen_angle");
            case 405 -> {
                // a plain shared picture on the west wall (the test pattern, with sound)
                var rect = new WallRect(Direction.WEST, new BlockPos(9, ground + 1, -7), new BlockPos(9, ground + 3, -2));
                Display d = DisplayManager.get().create(mc.world.getRegistryKey().getValue().toString(), rect);
                DisplayManager.get().start(d, dev.windowscraft.capture.SourceInfo.testPattern(), new CaptureSettings(720, 30, true));
                look(mc, 1.5, ground + 1, -4.5, -90, 0); // stand west of it, facing east
            }
            case 470 -> shot(mc, "06_west_wall_pattern");
            case 466 -> {
                // the smaller copies a wall shows from a distance (mipmaps) must be of the same
                // picture as the full-size one, frame after frame — never an older one
                var test = DisplayManager.get().liveShares().stream().filter(x -> x.source().kind() == dev.windowscraft.capture.SourceInfo.Kind.TEST).findFirst().orElse(null);
                double bad = test == null ? 1 : mipMismatch(test.glTextureId());
                check(bad < 0.05, "the distance copies of a moving picture match the picture (" + Math.round(bad * 100) + " % off)");
            }
            // ---- theater mode on your own wall: the chair ----
            case 475 -> look(mc, 0.5, ground + 1, 0.5, 180, 0);
            case 485 -> {
                sent.clear();
                KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_O, 0));
            }
            case 495 -> {
                check(dev.windowscraft.client.TheaterMode.controlled() == south(), "O at your wall: theater mode works the window");
                check(sent.contains("focus"), "the app gets the keyboard");
                check(dev.windowscraft.client.ScreenControl.pointerVisible(south()), "sitting shows the pointer");
                double before = dev.windowscraft.client.ScreenControl.pointer(south())[0];
                float yaw = mc.player.getYaw();
                dev.windowscraft.client.ScreenControl.onLook(mc, 80, 30);
                check(dev.windowscraft.client.ScreenControl.pointer(south())[0] > before && mc.player.getYaw() == yaw, "the mouse moves the pointer, not the view");
                long h = mc.getWindow().getHandle();
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_W, 17, GLFW.GLFW_PRESS, 0);
                check(sent.contains("key down 17") && !mc.options.forwardKey.isPressed(), "keys go to the app, not to Minecraft");
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_W, 17, GLFW.GLFW_RELEASE, 0);
                sent.clear();
                dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_PRESS);
                dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, GLFW.GLFW_RELEASE);
                check(sent.stream().anyMatch(l -> l.startsWith("button l down")), "clicks go to the app while sitting");
            }
            case 540 -> shot(mc, "07_theater_own");
            case 542 -> {
                dev.windowscraft.client.ScreenControl.testIdle();
                check(!dev.windowscraft.client.ScreenControl.pointerVisible(south()), "sitting still, the pointer hides after a moment");
                dev.windowscraft.client.ScreenControl.onLook(mc, 6, 0);
                check(dev.windowscraft.client.ScreenControl.pointerVisible(south()), "…and comes back when the mouse moves");
            }
            case 545 -> {
                long h = mc.getWindow().getHandle();
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_ESCAPE, 9, GLFW.GLFW_PRESS, 0);
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_ESCAPE, 9, GLFW.GLFW_RELEASE, 0);
                check(mc.currentScreen == null, "Esc doesn't open the pause menu in theater mode");
            }
            case 580 -> {
                check(!dev.windowscraft.client.TheaterMode.active(), "Esc leaves theater mode");
                check(sent.contains("blur"), "…and the app is let go");
                check(!dev.windowscraft.client.ScreenControl.pointerVisible(south()), "getting up hides the pointer");
                long h = mc.getWindow().getHandle();
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_W, 17, GLFW.GLFW_PRESS, 0);
                check(mc.options.forwardKey.isPressed(), "after leaving, W walks again");
                mc.keyboard.onKey(h, GLFW.GLFW_KEY_W, 17, GLFW.GLFW_RELEASE, 0);
            }
            // ---- theater mode on a plain picture (like someone else's screen): look and zoom ----
            case 600 -> look(mc, 1.5, ground + 1, -4.5, -90, 0);
            case 610 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_O, 0));
            case 620 -> check(dev.windowscraft.client.TheaterMode.active() && dev.windowscraft.client.TheaterMode.controlled() == null,
                    "theater mode on a picture you only watch");
            case 660 -> {
                check(mc.gameRenderer.getCamera().isThirdPerson(), "your character stays visible in theater mode");
                shot(mc, "10_theater");
                dev.windowscraft.client.TheaterMode.scroll(4);
            }
            case 690 -> {
                shot(mc, "11_theater_closer");
                dev.windowscraft.client.TheaterMode.scroll(-4);
                float bodyYaw = mc.player.getYaw();
                dev.windowscraft.client.TheaterMode.look(250, -60);
                check(mc.player.getYaw() == bodyYaw, "looking around in theater mode doesn't turn the body");
            }
            case 820 -> {
                check(dev.windowscraft.client.TheaterMode.lookOffset() > 30, "view turned away (" + Math.round(dev.windowscraft.client.TheaterMode.lookOffset()) + "°)");
                KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_SPACE, 0)); // recenter
            }
            case 840 -> {
                check(dev.windowscraft.client.TheaterMode.lookOffset() < 0.5, "Space recenters the view (" + dev.windowscraft.client.TheaterMode.lookOffset() + "°)");
                KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_O, 0));
            }
            case 900 -> {
                check(!dev.windowscraft.client.TheaterMode.active(), "theater mode ends with the key");
                var test = DisplayManager.get().liveShares().stream().filter(x -> x.source().kind() == dev.windowscraft.capture.SourceInfo.Kind.TEST).findFirst().orElse(null);
                check(test != null && test.audio().playsInGame(), "test pattern's sound plays in the game for the sharer");
                check(test != null && test.localSoundBytesPlayed() > 48000 * 4, "sharer heard > 1 s from the wall (" + (test == null ? -1 : test.localSoundBytesPlayed()) + " bytes)");
                look(mc, 0.5, ground + 1, 0.5, 180, -90); // at the sky
            }
            case 915 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 940 -> {
                check(mc.currentScreen instanceof dev.windowscraft.ui.HubScreen, "U away from walls opens the hub");
                shot(mc, "12_hub");
                int before = DisplayManager.get().liveShares().size();
                var west = DisplayManager.get().liveShares().stream().filter(x -> x.source().kind() == dev.windowscraft.capture.SourceInfo.Kind.TEST).findFirst().get();
                DisplayManager.get().stopShare(west);
                check(DisplayManager.get().liveShares().size() == before - 1, "stopping a share from the hub");
                mc.setScreen(dev.windowscraft.ui.HubScreen.settings());
            }
            case 952 -> shot(mc, "12b_hub_settings");
            case 960 -> {
                mc.currentScreen.close();
                look(mc, 0.5, ground + 1, 0.5, 180, 0);
                // break one block of the west wall → that display must disappear
                mc.getServer().execute(() -> mc.getServer().getOverworld().setBlockState(new BlockPos(9, ground + 2, -4), Blocks.STONE.getDefaultState()));
            }
            case 1000 -> {
                var west = DisplayManager.get().all().stream().filter(x -> x != south()).findFirst().orElse(null);
                check(west != null && !west.rect.contains(new BlockPos(9, ground + 2, -4)),
                        "a hole in a wall: its screen shrinks around it, never over the hole (" + (west == null ? "gone" : west.rect) + ")");
                // now the whole wall goes
                mc.getServer().execute(() -> {
                    for (int z = -7; z <= -2; z++) for (int y = 1; y <= 3; y++)
                        mc.getServer().getOverworld().setBlockState(new BlockPos(9, ground + y, z), Blocks.STONE.getDefaultState());
                });
                File f = new File(mc.runDirectory, "config/windowscraft/displays.json");
                check(f.exists(), "displays.json written");
            }
            case 1010 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1015 -> {
                check(mc.currentScreen instanceof dev.windowscraft.ui.SharePickerScreen, "U on your wall opens its picker (manage)");
                DisplayManager.get().stop(south());
                mc.currentScreen.close();
            }
            case 1020 -> {
                check(!south().isLive(), "stop sharing leaves an idle wall");
                look(mc, 0.5, ground + 1, -3.5, 180, 0);
            }
            case 1035 -> check(DisplayManager.get().all().size() == 1, "a screen whose wall is gone is removed (left " + DisplayManager.get().all().size() + ")");
            case 1060 -> shot(mc, "09_idle");
            // a live picture seen from far off to the side, almost along the wall: it must stay in
            // front of its concrete (no jagged steps of wall showing through)
            case 1062 -> DisplayManager.get().start(south(), dev.windowscraft.capture.SourceInfo.testPattern(), new CaptureSettings(720, 30, true));
            case 1064 -> look(mc, -18, ground + 2, -3, -102.5f, 0);
            case 1095 -> shot(mc, "13_grazing_far");
            case 1097 -> look(mc, -1.5, ground + 0.2, -6.3, -150, -55);
            case 1125 -> shot(mc, "14_grazing_far_other_side");
            // very sharp: 4° off the wall, 16 blocks away, looking straight at the picture's middle
            case 1127 -> {
                net.minecraft.util.math.Vec3d c = south().rect.center();
                Direction f = south().rect.face(), r = south().rect.right();
                double a = Math.toRadians(4), dist = 16;
                double x = c.x - r.getOffsetX() * dist * Math.cos(a) + f.getOffsetX() * dist * Math.sin(a);
                double z = c.z - r.getOffsetZ() * dist * Math.cos(a) + f.getOffsetZ() * dist * Math.sin(a);
                float yaw = (float) Math.toDegrees(Math.atan2(-(c.x - x), c.z - z));
                look(mc, x, c.y - 1.62, z, yaw, 0);
                mc.options.getFov().setValue(30); // zoomed in, so a sliver of wall showing through is visible
            }
            case 1143 -> {
                shot(mc, "15_grazing_4deg");
                mc.options.getFov().setValue(70);
            }
            case 1145 -> mc.getNetworkHandler().sendChatCommand("windowscraft report");
            case 1250 -> {
                java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"), "Desktop");
                if (java.nio.file.Files.isDirectory(home)) dir = home;
                java.io.File[] zips = dir.toFile().listFiles((d, n) -> n.startsWith("WindowsCraft-report-") && n.endsWith(".zip"));
                check(zips != null && zips.length > 0, "/windowscraft report wrote a zip");
                if (zips != null && zips.length > 0) {
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zips[0])) {
                        check(z.getEntry("system.txt") != null && z.getEntry("logs/latest.log") != null, "report has the system summary and the log");
                        log("report entries: " + z.stream().map(java.util.zip.ZipEntry::getName).toList());
                    } catch (java.io.IOException e) {
                        check(false, "report zip readable: " + e);
                    }
                    for (java.io.File zf : zips) zf.delete();
                }
            }
            case 1160 -> dev.windowscraft.apps.InputBridge.testSink = null;
            // ---- in-game monitors (pretend ones: the flow around them, on any system) ----
            case 1270 -> {
                dev.windowscraft.monitor.InGameMonitors.useForTesting(fakeMonitors);
                DisplayManager.get().stop(south());
                look(mc, 0.5, ground + 1, -3.5, 180, 0);
            }
            case 1285 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1295 -> {
                check(dev.windowscraft.monitor.InGameMonitors.isOn(south()), "U on an idle wall turns on an in-game monitor");
                check(fakeMonitors.liveCount() == 1, "one monitor was made (" + fakeMonitors.calls + ")");
                check(south().share().source().title().equals("In-game monitor 1"), "it's called In-game monitor 1: " + south().share().source().title());
                check(mc.currentScreen == null, "no menu in the way");
                dev.windowscraft.ui.HubScreen menu = new dev.windowscraft.ui.HubScreen(south(), dev.windowscraft.ui.HubScreen.TAB_WINDOWS);
                mc.setScreen(menu);
                menu.showMove(1000 + dev.windowscraft.monitor.InGameMonitors.monitorOf(south()).index()); // the test's pretend window
            }
            case 1300 -> shot(mc, "16_monitor_menu");
            case 1302 -> mc.currentScreen.close();
            case 1304 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_O, 0));
            case 1315 -> {
                check(dev.windowscraft.monitor.InGameMonitors.seated() == south(), "sitting at it hands it the real mouse and keyboard");
                check(fakeMonitors.calls.contains("seat 0"), "the helper was asked to seat: " + fakeMonitors.calls);
                check(fakeMonitors.beats > 5, "the game keeps telling the helper it's there while seated (" + fakeMonitors.beats + ")");
                log("seated: controlled " + (dev.windowscraft.client.TheaterMode.controlled() == south()) + ", seated() " + dev.windowscraft.client.TheaterMode.seated()
                        + ", desk view " + dev.windowscraft.client.DeskView.showing() + ", setting " + dev.windowscraft.config.ClientSettings.deskView());
                dev.windowscraft.client.GameArea.testCoveredBottom = 60; // (your real taskbar over the game's bottom)
            }
            case 1326 -> {
                shot(mc, "16a_seated_flat");
                check(dev.windowscraft.client.DeskView.showing() == south(), "sitting at it shows it flat and sharp on your view");
                double[] r = seatedPicture(mc);
                int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
                check(r != null, "the seated picture is on your view");
                if (r != null) {
                    log("seated picture: " + Math.round(r[0]) + "," + Math.round(r[1]) + " – " + Math.round(r[2]) + "," + Math.round(r[3]) + " in " + fw + "×" + fh + " (bottom 60 covered)");
                    check(r[0] >= -1 && r[1] >= -1 && r[2] <= fw + 1 && r[3] <= fh - 60 + 1, "fully zoomed in, the screen never goes past the window's edges or under your taskbar");
                    boolean touches = (Math.abs(r[0]) <= 3 && Math.abs(r[2] - fw) <= 3) || (Math.abs(r[1]) <= 3 && Math.abs(r[3] - (fh - 60)) <= 3);
                    check(touches, "…and it goes right up to them (as big as it can be)");
                    seatedWidth = r[2] - r[0];
                }
                check(dev.windowscraft.client.DeskView.covers(south()), "the flat picture is right over the monitor (the room's own copy isn't drawn under it)");
                dev.windowscraft.client.TheaterMode.scroll(-3); // (Alt + wheel: further back)
            }
            case 1331 -> {
                shot(mc, "16a2_seated_scrolled_out");
                double[] r = seatedPicture(mc);
                check(dev.windowscraft.client.DeskView.showing() == south() && r != null && r[2] - r[0] < seatedWidth * 0.85,
                        "scrolled out, it's still flat and sharp — smaller, with the room around it" + (r == null ? "" : " (" + Math.round(r[2] - r[0]) + " wide, was " + Math.round(seatedWidth) + ")"));
                dev.windowscraft.client.GameArea.testCoveredBottom = 0;
                fakeMonitors.events().esc(); // the helper saw Esc
            }
            case 1339 -> {
                check(!dev.windowscraft.client.TheaterMode.active(), "Esc (seen by the helper) gets you up");
                check(dev.windowscraft.monitor.InGameMonitors.seated() == null && fakeMonitors.calls.contains("unseat"), "and the mouse and keyboard come back");
            }
            // Alt-pointing at it (not sitting): your real cursor goes onto it, the game keeps the keyboard
            case 1342 -> dev.windowscraft.client.ScreenControl.testAlt = true;
            case 1350 -> {
                check(fakeMonitors.calls.contains("point 0") && fakeMonitors.calls.contains("pointme true"),
                        "Alt-pointing at your in-game monitor puts your real cursor on it: " + fakeMonitors.calls);
                check(fakeMonitors.lastCursor != null && dev.windowscraft.monitor.InGameMonitors.realCursorOn(south()),
                        "the cursor follows where you look");
                check(dev.windowscraft.monitor.InGameMonitors.seated() == null, "without sitting down");
                dev.windowscraft.client.ScreenControl.testAlt = false;
                fakeMonitors.events().altUp(); // you let go of Alt
            }
            case 1360 -> check(dev.windowscraft.monitor.InGameMonitors.pointing() == null && fakeMonitors.calls.contains("unpoint"),
                    "letting go of Alt gives the cursor back to the game");
            // its own size, from the hub
            case 1362 -> mc.setScreen(new dev.windowscraft.ui.HubScreen(south(), dev.windowscraft.ui.HubScreen.TAB_PICTURE));
            case 1368 -> {
                shot(mc, "16b_monitor_picture");
                dev.windowscraft.monitor.InGameMonitors.setMode(south(), 1280, 720, 60);
            }
            case 1374 -> {
                check(fakeMonitors.calls.contains("setmode 1280x720") && south().monitorW == 1280,
                        "a new size for the in-game monitor is set and remembered: " + fakeMonitors.calls);
                var m = dev.windowscraft.monitor.InGameMonitors.monitorOf(south());
                check(m != null && m.width() == 1280, "the wall shows it at its new size (" + m + ")");
                // who can use it: "everyone except…" with nobody else here = nobody
                DisplayManager.get().setAccess(south(), dev.windowscraft.display.Display.ACCESS_ALL_BUT);
                java.util.UUID someone = java.util.UUID.randomUUID();
                DisplayManager.get().setAccess(south(), dev.windowscraft.display.Display.ACCESS_PICKED);
                DisplayManager.get().setController(south(), someone, "Someone", true);
                check(south().controllers.containsKey(someone) && south().picked.containsKey(someone), "picking someone lets them use it");
                DisplayManager.get().setAccess(south(), dev.windowscraft.display.Display.ACCESS_ME);
                check(south().controllers.isEmpty() && south().picked.containsKey(someone), "\"only me\": nobody else (the pick is kept for later)");
                DisplayManager.get().setAccess(south(), dev.windowscraft.display.Display.ACCESS_PICKED);
                DisplayManager.get().setController(south(), someone, "Someone", false);
                check(south().controllers.isEmpty(), "un-picking takes it away again");
                mc.currentScreen.close();
            }
            case 1380 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1390 -> {
                check(!south().isLive() && fakeMonitors.liveCount() == 0, "U again turns it off and removes the monitor: " + fakeMonitors.calls);
                check(dev.windowscraft.monitor.InGameMonitors.all().isEmpty(), "nothing left over");
            }
            case 1392 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1398 -> {
                check(dev.windowscraft.monitor.InGameMonitors.isOn(south()), "on again");
                var m = dev.windowscraft.monitor.InGameMonitors.monitorOf(south());
                check(m != null && m.width() == 1280, "at the size you chose for it (" + m + ")");
                fakeMonitors.events().lost(); // the helper died: its monitors are gone
            }
            case 1410 -> check(!south().isLive() && dev.windowscraft.monitor.InGameMonitors.all().isEmpty(),
                    "a stopped helper ends every in-game monitor share (nothing else gets captured instead)");
            case 1412 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1420 -> {
                check(dev.windowscraft.monitor.InGameMonitors.isOn(south()), "on once more");
                mc.getServer().execute(() -> {
                    for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++)
                        mc.getServer().getOverworld().setBlockState(new BlockPos(x, ground + y, -8), Blocks.AIR.getDefaultState());
                });
            }
            case 1450 -> {
                check(south() == null, "the wall is gone, so is its screen");
                check(fakeMonitors.calls.contains("remove 2") && dev.windowscraft.monitor.InGameMonitors.all().isEmpty(),
                        "breaking the wall removed its monitor: " + fakeMonitors.calls);
            }
            // ---- gaming monitor blocks: 8 directions, each its own screen ----
            case 1455 -> mc.getServer().execute(() -> {
                var w = mc.getServer().getOverworld();
                w.setBlockState(mon(7), dev.windowscraft.block.Monitors.MONITORS.get("red").getDefaultState().with(dev.windowscraft.block.MonitorBlock.TURN, 7));
                w.setBlockState(mon(0), dev.windowscraft.block.Monitors.MONITORS.get("white").getDefaultState().with(dev.windowscraft.block.MonitorBlock.TURN, 0));
                w.setBlockState(mon(1), dev.windowscraft.block.Monitors.MONITORS.get("blue").getDefaultState().with(dev.windowscraft.block.MonitorBlock.TURN, 1));
            });
            case 1460 -> lookAt(mc, 0.5, ground + 1, 0.5, mon(0));
            case 1465, 1480, 1495 -> KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_U, 0));
            case 1475 -> {
                Display m0 = monitorAt(mc, 0);
                check(m0 != null && m0.rect.isMonitor() && dev.windowscraft.monitor.InGameMonitors.isOn(m0), "U at a gaming monitor block turns it on");
                lookAt(mc, 0.5, ground + 1, 0.5, mon(1));
            }
            case 1490 -> {
                check(dev.windowscraft.monitor.InGameMonitors.isOn(monitorAt(mc, 1)), "a diagonal one too");
                lookAt(mc, 0.5, ground + 1, 0.5, mon(7));
            }
            case 1505 -> {
                check(dev.windowscraft.monitor.InGameMonitors.isOn(monitorAt(mc, 7)), "and the other diagonal");
                check(dev.windowscraft.monitor.InGameMonitors.all().size() == 3, "three monitors, three screens: " + fakeMonitors.calls);
                look(mc, 0.5, ground + 1, 1.5, 180, 25);
            }
            case 1515 -> {
                shot(mc, "17_monitor_blocks");
                BlockPos p = mon(1);
                // straight in front of the diagonal one, a block and a half away
                lookAt(mc, p.getX() + 0.5 - 1.6 * 0.7071, ground + 1, p.getZ() + 0.5 + 1.6 * 0.7071, p);
            }
            case 1525 -> {
                shot(mc, "17b_monitor_diagonal");
                dev.windowscraft.client.ScreenControl.testAlt = true;
            }
            case 1535 -> {
                check(dev.windowscraft.monitor.InGameMonitors.pointing() == monitorAt(mc, 1),
                        "Alt-pointing at a diagonal monitor puts the cursor on it (" + dev.windowscraft.monitor.InGameMonitors.pointing() + ", pointed "
                                + dev.windowscraft.client.ScreenControl.pointed() + ", at " + mc.player.getPos() + " " + mc.player.getYaw() + "/" + mc.player.getPitch() + ")");
                dev.windowscraft.client.ScreenControl.testAlt = false;
                fakeMonitors.events().altUp();
                mc.getServer().execute(() -> mc.getServer().getOverworld().setBlockState(mon(0), Blocks.AIR.getDefaultState()));
            }
            case 1560 -> {
                check(monitorAt(mc, 0) == null && dev.windowscraft.monitor.InGameMonitors.all().size() == 2, "breaking a monitor block ends its screen: " + fakeMonitors.calls);
                dev.windowscraft.monitor.InGameMonitors.turnOffAll();
            }
            // ---- a tablet: your own hand-held monitor ----
            case 1580 -> {
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory()
                        .setStack(0, new net.minecraft.item.ItemStack(dev.windowscraft.block.Monitors.TABLETS.get("cyan"))));
                mc.player.getInventory().selectedSlot = 0;
                look(mc, 0.5, ground + 1, 0.5, 180, 0);
            }
            case 1590 -> mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND); // right-click
            case 1610 -> {
                Display t = dev.windowscraft.client.Tablet.display();
                check(t != null && dev.windowscraft.monitor.InGameMonitors.isOn(t), "right-clicking a tablet turns on its own monitor");
                var tm = dev.windowscraft.monitor.InGameMonitors.monitorOf(t);
                check(tm != null && tm.width() == 1920 && tm.height() == 1080, "wide, like a monitor: " + tm);
                check(dev.windowscraft.client.Tablet.raised() && dev.windowscraft.monitor.InGameMonitors.seated() == t,
                        "and holds it up, with your mouse and keyboard on it");
                check(t.publisher() == null && DisplayManager.get().findAt(t.dimension, t.rect.min(), Direction.NORTH) == null,
                        "only you see it (nothing sent to the server, nothing in the world)");
            }
            case 1615 -> shot(mc, "18_tablet_up");
            case 1617 -> fakeMonitors.events().esc();
            case 1622 -> {
                Display t = dev.windowscraft.client.Tablet.display();
                check(!dev.windowscraft.client.Tablet.raised() && dev.windowscraft.monitor.InGameMonitors.seated() == null
                        && dev.windowscraft.monitor.InGameMonitors.isOn(t), "Esc puts it down, still on");
            }
            case 1630 -> shot(mc, "18b_tablet_in_hand");
            case 1632 -> { // in the other hand, with something in the main hand
                mc.getServer().execute(() -> {
                    var inv = mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory();
                    inv.offHand.set(0, inv.getStack(0));
                    inv.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_PICKAXE));
                });
            }
            case 1638 -> shot(mc, "18e_tablet_offhand");
            case 1639 -> mc.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT);
            case 1645 -> {
                shot(mc, "18d_tablet_third_person_front");
                mc.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
                look(mc, 0.5, ground + 1, 0.5, 180, 55); // from behind and above
            }
            case 1652 -> {
                shot(mc, "18c_tablet_third_person");
                mc.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
                look(mc, 0.5, ground + 1, 0.5, 180, 0);
            }
            case 1660 -> dev.windowscraft.block.TabletItem.onUse.accept(mc.player, true); // sneak + right-click
            case 1670 -> check(!dev.windowscraft.monitor.InGameMonitors.isOn(dev.windowscraft.client.Tablet.display()), "sneak + right-click turns it off");
            // ---- joined monitors: placed against another, no stand, hinged on its edge, facing you ----
            case 1680 -> {
                dev.windowscraft.monitor.InGameMonitors.turnOffAll();
                mc.getServer().execute(() -> {
                    var w = mc.getServer().getOverworld();
                    for (BlockPos q : BlockPos.iterate(-3, JOIN.getY(), JOIN.getZ() - 1, 4, JOIN.getY() + 2, JOIN.getZ() + 1)) w.setBlockState(q, Blocks.AIR.getDefaultState());
                    for (int x = -2; x <= 3; x++) w.setBlockState(new BlockPos(x, JOIN.getY() - 1, JOIN.getZ()), Blocks.SPRUCE_PLANKS.getDefaultState());
                    w.setBlockState(JOIN, dev.windowscraft.block.Monitors.MONITORS.get("black").getDefaultState().with(dev.windowscraft.block.MonitorBlock.TURN, 0));
                    var sp = mc.getServer().getPlayerManager().getPlayerList().get(0);
                    sp.getInventory().setStack(1, new net.minecraft.item.ItemStack(dev.windowscraft.block.Monitors.MONITOR_ITEMS.get("black"), 16));
                });
                mc.player.getInventory().selectedSlot = 1;
                look(mc, 0.5, ground + 1, JOIN.getZ() + 3.2, 180, 15);
            }
            case 1690 -> place(mc, JOIN, Direction.EAST);   // to its right
            case 1694 -> place(mc, JOIN, Direction.WEST);   // to its left
            case 1698 -> place(mc, JOIN, Direction.UP);     // above it
            case 1702 -> place(mc, JOIN.east(), Direction.EAST); // and one more on the right
            case 1706 -> place(mc, JOIN.west(), Direction.UP); // above the left one
            case 1716 -> {
                var cw = mc.world;
                var sw = mc.getServer().getOverworld();
                BlockPos[] joined = {JOIN.east(), JOIN.west(), JOIN.up(), JOIN.east(2), JOIN.west().up()};
                boolean allFree = true, same = true;
                for (BlockPos q : joined) {
                    var st = cw.getBlockState(q);
                    allFree &= st.getBlock() instanceof dev.windowscraft.block.MonitorBlock && st.get(dev.windowscraft.block.MonitorBlock.FREE);
                    var cp = dev.windowscraft.block.MonitorBlock.poseAt(cw, q);
                    var spose = java.util.concurrent.CompletableFuture.supplyAsync(() -> dev.windowscraft.block.MonitorBlock.poseAt(sw, q), mc.getServer()).join();
                    same &= cp != null && cp.near(spose);
                    log("joined monitor at " + q + ": " + st + " pose " + (cp == null ? null : cp.encode()) + " (server " + (spose == null ? null : spose.encode()) + ")");
                }
                check(allFree, "monitors placed against another one join it (no stand)");
                check(same, "a joined monitor hangs the same way for the server and the client");
                check(!cw.getBlockState(JOIN).get(dev.windowscraft.block.MonitorBlock.FREE), "the first one keeps its stand");
                // the edges meet: the right one's left screen edge is the middle one's right edge
                var mid = dev.windowscraft.block.MonitorBlock.rectAt(cw, JOIN).corners(1, 1);
                var right = dev.windowscraft.block.MonitorBlock.rectAt(cw, JOIN.east()).corners(1, 1);
                double gap = mid[1].distanceTo(right[0]);
                check(gap < 0.2, "joined screens sit edge to edge (gap " + gap + ")");
                var rn = dev.windowscraft.block.MonitorBlock.rectAt(cw, JOIN.east()).normal();
                var un = dev.windowscraft.block.MonitorBlock.rectAt(cw, JOIN.up()).normal();
                check(rn.x < -0.1, "the right one turns toward you: " + rn);
                check(un.y < -0.1, "the top one tilts down toward you: " + un);
                shot(mc, "19a_joined_off");
                String dim = cw.getRegistryKey().getValue().toString();
                dev.windowscraft.monitor.InGameMonitors.turnOn(mc, dim, dev.windowscraft.block.MonitorBlock.rectAt(cw, JOIN), null);
                for (BlockPos q : joined) dev.windowscraft.monitor.InGameMonitors.turnOn(mc, dim, dev.windowscraft.block.MonitorBlock.rectAt(cw, q), null);
            }
            case 1740 -> {
                check(dev.windowscraft.monitor.InGameMonitors.all().size() == 6, "every joined monitor is its own screen: " + dev.windowscraft.monitor.InGameMonitors.all().size());
                shot(mc, "19_joined_monitors");
                look(mc, 3.6, ground + 1, JOIN.getZ() + 1.6, 120, 20);
            }
            case 1744 -> {
                // joined in the world, joined on the Windows desktop (and only those)
                String spec = dev.windowscraft.monitor.InGameMonitors.arrangement();
                log("arrangement: " + spec + " — calls " + fakeMonitors.calls);
                String[] parts = spec.split(" ");
                long roots = java.util.Arrays.stream(parts).filter(x -> x.contains(":-1:")).count();
                check(parts.length == 6 && roots == 1, "the six joined monitors form one group on the desktop: " + spec);
                check(java.util.Arrays.stream(parts).filter(x -> x.endsWith(":R")).count() == 2
                        && java.util.Arrays.stream(parts).anyMatch(x -> x.endsWith(":L")) && java.util.Arrays.stream(parts).anyMatch(x -> x.endsWith(":T")),
                        "right, left and top neighbours as they stand: " + spec);
                check(fakeMonitors.calls.stream().anyMatch(c -> c.startsWith("arrange ") && c.split(" ").length == 7), "…and Windows is asked to lay them out so: " + fakeMonitors.calls);
            }
            case 1745 -> {
                // two people at two of your monitors at once: each their own pointer, window and
                // keys — neither lets go of the other's buttons or types into the other's window
                String dim = mc.world.getRegistryKey().getValue().toString();
                Display a = DisplayManager.get().findAt(dim, JOIN, Direction.NORTH), b = DisplayManager.get().findAt(dim, JOIN.east(2), Direction.NORTH);
                var ma = dev.windowscraft.monitor.InGameMonitors.monitorOf(a);
                dev.windowscraft.capture.win.WinGeom.testWindowAt = (x, y) -> ma != null && x >= ma.left() && x < ma.left() + ma.width()
                        ? new dev.windowscraft.capture.win.WinGeom.At(1001, ma.left(), ma.top(), ma.width(), ma.height())
                        : new dev.windowscraft.capture.win.WinGeom.At(2002, 0, 0, 100000, 100000);
                java.util.UUID bob = java.util.UUID.randomUUID(), carol = java.util.UUID.randomUUID();
                DisplayManager.get().setController(a, bob, "Bob", true);
                DisplayManager.get().setController(b, carol, "Carol", true);
                java.util.function.BiConsumer<Object[], Integer> op = (who, o) -> dev.windowscraft.client.ScreenControl.fromGuest(mc,
                        new dev.windowscraft.net.Payloads.ControlFrom((java.util.UUID) who[0], (String) who[1],
                                new dev.windowscraft.net.Payloads.Control(((Display) who[2]).id, o, 0.5f, 0.5f, o == dev.windowscraft.net.Payloads.OP_KEY_DOWN || o == dev.windowscraft.net.Payloads.OP_KEY_UP ? 30 : 0, 0)));
                Object[] B = {bob, "Bob", a}, C = {carol, "Carol", b};
                dev.windowscraft.apps.InputBridge.testSink = sent::add;
                sent.clear();
                op.accept(B, dev.windowscraft.net.Payloads.OP_SIT);
                op.accept(B, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN);  // Bob holds the button down…
                op.accept(C, dev.windowscraft.net.Payloads.OP_SIT);
                op.accept(C, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN);  // …while Carol clicks on hers
                op.accept(C, dev.windowscraft.net.Payloads.OP_BUTTON_UP);
                op.accept(B, dev.windowscraft.net.Payloads.OP_KEY_DOWN);     // and types
                op.accept(C, dev.windowscraft.net.Payloads.OP_KEY_DOWN);
                op.accept(B, dev.windowscraft.net.Payloads.OP_BUTTON_UP);
                log("two guests: " + sent + " a " + a + " ctl " + (a == null ? null : a.controllers) + " controllable " + dev.windowscraft.client.ScreenControl.controllable(a)
                        + " b " + b + " ctl " + (b == null ? null : b.controllers) + " ma " + ma);
                java.util.List<String> got = new java.util.ArrayList<>(sent);
                String sb = got.stream().filter(l -> l.endsWith("target 1001")).findFirst().map(l -> l.substring(0, l.indexOf(" target"))).orElse("?");
                String sc = got.stream().filter(l -> l.endsWith("target 2002")).findFirst().map(l -> l.substring(0, l.indexOf(" target"))).orElse("?");
                check(sb.startsWith("slot ") && sc.startsWith("slot ") && !sb.equals(sc), "each guest gets their own input: " + sb + " / " + sc);
                check(got.stream().noneMatch(l -> l.equals(sb + " release") || l.equals(sb + " blur")), "Carol clicking never lets go of Bob's held button");
                check(got.contains(sb + " key down 30") && got.contains(sc + " key down 30"), "each one's keys go to their own window");
                check(got.stream().allMatch(l -> l.startsWith("slot ")), "guests never use your own input");
                dev.windowscraft.client.ScreenControl.dropGuest(a, bob);
                dev.windowscraft.client.ScreenControl.dropGuest(b, carol);
                dev.windowscraft.capture.win.WinGeom.testWindowAt = null;
                dev.windowscraft.apps.InputBridge.testSink = null;
            }
            case 1746 -> {
                // someone you allowed is in the middle of using your monitor (sitting there, holding
                // a button) — and you sit down at it yourself: you always can
                String dim = mc.world.getRegistryKey().getValue().toString();
                Display a = DisplayManager.get().findAt(dim, JOIN, Direction.NORTH);
                java.util.UUID dave = java.util.UUID.randomUUID();
                DisplayManager.get().setController(a, dave, "Dave", true);
                dev.windowscraft.capture.win.WinGeom.testWindowAt = (x, y) -> new dev.windowscraft.capture.win.WinGeom.At(3003, 0, 0, 100000, 100000);
                dev.windowscraft.apps.InputBridge.testSink = sent::add;
                for (int op : new int[]{dev.windowscraft.net.Payloads.OP_SIT, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN})
                    dev.windowscraft.client.ScreenControl.fromGuest(mc, new dev.windowscraft.net.Payloads.ControlFrom(dave, "Dave",
                            new dev.windowscraft.net.Payloads.Control(a.id, op, 0.5f, 0.5f, 0, 0)));
                guestDave = dave;
                var c = a.rect.center();
                double ex = 0.5, ey = ground + 1, ez = JOIN.getZ() + 2.4;
                double dx = c.x - ex, dy = c.y - (ey + 1.62), dz = c.z - ez;
                look(mc, ex, ey, ez, (float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
            case 1748 -> {
                // (straight at its middle, from where you stand now)
                String dim = mc.world.getRegistryKey().getValue().toString();
                var c = DisplayManager.get().findAt(dim, JOIN, Direction.NORTH).rect.center();
                mc.player.setPosition(0.5, ground + 1, JOIN.getZ() + 2.4); // (in front of it, whatever the server has done yet)
                var to = c.subtract(mc.player.getEyePos());
                mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
                mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
                dev.windowscraft.client.TheaterMode.toggle(mc); // (O: sit down at the one you look at)
            }
            case 1749 -> {
                String dim = mc.world.getRegistryKey().getValue().toString();
                Display a = DisplayManager.get().findAt(dim, JOIN, Direction.NORTH);
                check(dev.windowscraft.client.TheaterMode.controlled() == a && dev.windowscraft.monitor.InGameMonitors.seated() == a,
                        "with a guest using your monitor, you can still sit down at it yourself (want " + a.rect.min() + ", controlled " + (dev.windowscraft.client.TheaterMode.controlled() == null ? null : dev.windowscraft.client.TheaterMode.controlled().rect.min())
                                + " looking " + mc.player.getYaw() + "/" + mc.player.getPitch() + " from " + mc.player.getEyePos()
                                + ", seated " + dev.windowscraft.monitor.InGameMonitors.seated() + ", calls " + fakeMonitors.calls + ")");
                dev.windowscraft.client.TheaterMode.leaveNow();
                dev.windowscraft.client.ScreenControl.dropGuest(a, guestDave);
                dev.windowscraft.capture.win.WinGeom.testWindowAt = null;
                dev.windowscraft.apps.InputBridge.testSink = null;
            }
            case 1750 -> shot(mc, "19b_joined_side");
            case 1752 -> look(mc, 0.5, ground + 1, JOIN.getZ() + 2.2, 180, 25);
            case 1760 -> shot(mc, "19c_joined_close");
            case 1762 -> {
                dev.windowscraft.client.ScreenControl.testAlt = true;
                // up at the middle of the top one's screen
                var c = dev.windowscraft.block.MonitorBlock.rectAt(mc.world, JOIN.up()).center();
                double ex = 0.5, ey = ground + 1, ez = JOIN.getZ() + 2.4;
                double dx = c.x - ex, dy = c.y - (ey + 1.62), dz = c.z - ez;
                look(mc, ex, ey, ez, (float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
            case 1805 -> {
                var top = DisplayManager.get().findAt(mc.world.getRegistryKey().getValue().toString(), JOIN.up(), Direction.NORTH);
                var cam = mc.gameRenderer.getCamera();
                var hitR = mc.world.raycast(new net.minecraft.world.RaycastContext(cam.getPos(), top.rect.center(),
                        net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
                check(dev.windowscraft.client.ScreenControl.pointed() == top, "pointing at a tilted monitor finds its screen (pointed "
                        + dev.windowscraft.client.ScreenControl.pointed() + ", top " + top + " live " + (top != null && top.isLive()) + ", cam " + cam.getPos()
                        + " look " + cam.getHorizontalPlane() + ", centre " + top.rect.center() + ", blocked by " + (hitR instanceof net.minecraft.util.hit.BlockHitResult bh ? bh.getType() + " " + bh.getBlockPos() + " " + mc.world.getBlockState(bh.getBlockPos()) : hitR) + ")");
                dev.windowscraft.client.ScreenControl.testAlt = false;
                fakeMonitors.events().altUp();
                mc.getServer().execute(() -> mc.getServer().getOverworld().setBlockState(JOIN.east(), Blocks.AIR.getDefaultState()));
            }
            case 1840 -> {
                check(dev.windowscraft.monitor.InGameMonitors.all().size() == 5, "breaking a joined monitor ends its screen: " + dev.windowscraft.monitor.InGameMonitors.all().size() + " block " + mc.world.getBlockState(JOIN.east()) + " calls " + fakeMonitors.calls);
                check(mc.world.getBlockState(JOIN.east(2)).get(dev.windowscraft.block.MonitorBlock.FREE), "the one beyond it stays where it hung");
                dev.windowscraft.monitor.InGameMonitors.turnOffAll();
            }
            case 1855 -> {
                log("ALL CHECKS PASSED");
                mc.scheduleStop();
            }
            default -> {}
        }
    }

    private void buildScene(MinecraftClient mc) {
        ServerWorld sw = mc.getServer().getOverworld();
        ground = sw.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
        log("ground at y=" + ground);
        JOIN = new BlockPos(0, ground + 2, -6); // (on a desk)
        mc.getServer().execute(() -> {
            sw.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, mc.getServer());
            sw.setTimeOfDay(6000);
            // (what the joined-monitors part built last time goes: it stands in front of the wall)
            for (BlockPos q : BlockPos.iterate(-3, ground + 1, -7, 4, ground + 5, -5)) sw.setBlockState(q, Blocks.AIR.getDefaultState());
            // 7×4 wall facing south (toward the player)
            for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++)
                sw.setBlockState(new BlockPos(x, ground + y, -8), Blocks.BLACK_CONCRETE.getDefaultState());
            // a stray block on the side that must NOT be included (diagonal neighbour)
            sw.setBlockState(new BlockPos(4, ground + 5, -8), Blocks.BLACK_CONCRETE.getDefaultState());
            // 6×3 wall facing west
            for (int z = -7; z <= -2; z++) for (int y = 1; y <= 3; y++)
                sw.setBlockState(new BlockPos(9, ground + y, z), Blocks.BLACK_CONCRETE.getDefaultState());
            ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayerList().get(0);
            p.getInventory().clear(); // (a tablet left in hand from last time would turn itself on)
            p.teleport(sw, 0.5, ground + 1, 0.5, 180f, 0f);
            try { // no slime or rain spoiling the run
                mc.getServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                sw.setWeather(12000, 0, false, false);
            } catch (Throwable t) {
                log("couldn't calm the world: " + t);
            }
        });
    }

    /** Where the joined-monitors test builds its row. */
    private BlockPos JOIN = BlockPos.ORIGIN;
    private java.util.UUID guestDave;

    /** Right-clicks that side of that block with what's in your hand (a real placement, client and server). */
    private void place(MinecraftClient mc, BlockPos on, Direction side) {
        net.minecraft.util.math.Vec3d hit = net.minecraft.util.math.Vec3d.ofCenter(on).add(net.minecraft.util.math.Vec3d.of(side.getVector()).multiply(0.5));
        var r = mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND,
                new net.minecraft.util.hit.BlockHitResult(hit, side, on, false));
        log("placed against " + on + " " + side + ": " + r);
    }

    /** Where the test's gaming monitor blocks stand (by their turn). */
    private BlockPos mon(int turn) {
        return new BlockPos(turn == 7 ? -3 : turn == 0 ? 0 : 3, ground + 1, -3);
    }

    private Display monitorAt(MinecraftClient mc, int turn) {
        return DisplayManager.get().findAt(mc.world.getRegistryKey().getValue().toString(), mon(turn), Direction.NORTH);
    }

    /** Stands there (feet) and looks at the middle of that monitor block's screen. */
    private void lookAt(MinecraftClient mc, double x, double y, double z, BlockPos target) {
        double dx = target.getX() + 0.5 - x, dy = target.getY() + 0.6 - (y + 1.62), dz = target.getZ() + 0.5 - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        look(mc, x, y, z, yaw, pitch);
    }

    private void look(MinecraftClient mc, double x, double y, double z, float yaw, float pitch) {
        mc.getServer().execute(() -> {
            ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayerList().get(0);
            p.teleport(mc.getServer().getOverworld(), x, y, z, yaw, pitch);
        });
    }

    private void clickCard(MinecraftClient mc, int index) throws Exception {
        var s = mc.currentScreen;
        int gridX = intField(s, "gridX"), gridY = intField(s, "gridY"), cardW = intField(s, "cardW"),
                cardH = intField(s, "cardH"), cols = intField(s, "cols"), gap = intField(s, "gap");
        double x = gridX + (index % cols) * (cardW + gap) + cardW / 2.0;
        double y = gridY + (index / cols) * (cardH + gap) + cardH / 2.0;
        log("clicking card " + index + " at " + x + "," + y);
        s.mouseClicked(x, y, 0);
    }

    private static Object intFieldOrNull(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static int intField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(o);
    }

    private static double seatedWidth;

    /** Where the seated monitor's picture is on the frame (framebuffer pixels: left, top, right, bottom). */
    private static double[] seatedPicture(MinecraftClient mc) {
        Display d = dev.windowscraft.client.DeskView.showing();
        if (d == null) return null;
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (net.minecraft.util.math.Vec3d p : dev.windowscraft.client.ScreenControl.pictureCorners(d)) {
            double[] q = dev.windowscraft.client.FrameView.project(p);
            if (q == null) return null;
            x0 = Math.min(x0, q[0]); x1 = Math.max(x1, q[0]); y0 = Math.min(y0, q[1]); y1 = Math.max(y1, q[1]);
        }
        return new double[]{x0, y0, x1, y1};
    }

    private void shot(MinecraftClient mc, String name) {
        if (mc.player != null) log("shot " + name + " from " + mc.player.getPos() + " yaw " + mc.player.getYaw() + " pitch " + mc.player.getPitch());
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, "autotest_" + name + ".png", mc.getFramebuffer(), msg -> log(msg.getString()));
    }

    private void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("CHECK FAILED: " + what);
        log("ok: " + what);
    }
}
