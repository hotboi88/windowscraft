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
                look(mc, 6.5, ground + 1, -1.5, Vec3d.ofCenter(mon()).add(0, 0.05, 0));
                stage++;
                tick = 0;
            }
            case 4 -> {
                if (tick < 10) return;
                log("pressing U at the gaming monitor block");
                pressU();
                await("U on a gaming monitor block turns it on", 60, () -> InGameMonitors.isOn(DisplayManager.get().findAt(dim(mc), mon(), Direction.SOUTH)));
            }
            case 5 -> {
                if (tick < 40) return;
                shot(mc, "2_monitor_block_on");
                // a second monitor joined to its right
                mc.player.getInventory().selectedSlot = 7;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 7);
                stage++;
                tick = 0;
            }
            case 6 -> {
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
            case 7 -> {
                if (tick < 20) return;
                look(mc, 7.0, ground + 1, -1.5, DisplayManager.get().findAt(dim(mc), mon(), Direction.SOUTH) != null
                        ? dev.windowscraft.block.MonitorBlock.rectAt(mc.world, mon().east()).center() : Vec3d.ofCenter(mon().east()));
                stage++;
                tick = 0;
            }
            case 8 -> {
                if (tick < 10) return;
                pressU();
                await("U on the joined monitor turns it on too", 60, () -> InGameMonitors.isOn(DisplayManager.get().findAt(dim(mc), mon().east(), Direction.SOUTH)));
            }
            case 9 -> {
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
                shot(mc, "3_joined");
                // sit down at the wall
                look(mc, 0.5, ground + 1, -1.5, Vec3d.ofCenter(wall(0, 0)));
                stage++;
                tick = 0;
            }
            case 10 -> {
                if (tick < 10) return;
                dev.windowscraft.client.TheaterMode.toggle(mc);
                await("O sits you down at it (your mouse and keyboard go to it)", 20, () -> InGameMonitors.seated() != null);
            }
            case 11 -> {
                if (tick < 60) return;
                shot(mc, "4_seated");
                dev.windowscraft.client.TheaterMode.leaveNow();
                await("getting up gives the mouse and keyboard back", 20, () -> InGameMonitors.seated() == null);
            }
            case 12 -> {
                // a tablet: holding one turns it on, right-click holds it up
                mc.player.getInventory().selectedSlot = 8;
                mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayerList().get(0).getInventory().selectedSlot = 8);
                await("holding a tablet turns it on", 60, () -> InGameMonitors.isOn(dev.windowscraft.client.Tablet.display()));
            }
            case 13 -> {
                if (tick < 20) return;
                mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                await("right-click holds it up and you use it", 20, () -> dev.windowscraft.client.Tablet.raised() && InGameMonitors.seated() != null);
            }
            case 14 -> {
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
            case 15 -> {
                InGameMonitors.turnOffAll();
                await("everything turns off", 30, () -> InGameMonitors.all().isEmpty());
            }
            case 16 -> {
                if (tick < 40) return;
                shot(mc, "6_all_off");
                finish(mc);
            }
            default -> {}
        }
    }

    private void finish(MinecraftClient mc) {
        log(failures == 0 ? "WINTEST PASSED" : "WINTEST FAILED: " + failures + " failed");
        stage = 99;
        mc.scheduleStop();
    }
}
