package dev.windowscraft.autotest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dev-only dedicated-server side of the multiplayer test: builds the wall and places the two players. */
public final class AutoTestServer implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("WindowsCraft mp-test server");
    static int ground;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("windowscraft.autotest.server")) return;
        ServerLifecycleEvents.SERVER_STARTED.register(AutoTestServer::build);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> place(server, handler.player)));
    }

    private static void build(MinecraftServer server) {
        ServerWorld w = server.getOverworld();
        ground = w.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0) - 1;
        w.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        w.setTimeOfDay(6000);
        // 7×4 screen facing south, a little room around it: stone floor behind, and a stone side wall
        for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++) w.setBlockState(new BlockPos(x, ground + y, -8), Blocks.BLACK_CONCRETE.getDefaultState());
        // a second, smaller wall beside it, for the mirroring test
        for (int x = 5; x <= 8; x++) for (int y = 1; y <= 3; y++) w.setBlockState(new BlockPos(x, ground + y, -8), Blocks.BLACK_CONCRETE.getDefaultState());
        // a gaming monitor block, turned diagonally (south-west)
        w.setBlockState(new BlockPos(-5, ground + 1, -5), dev.windowscraft.block.Monitors.MONITORS.get("orange").getDefaultState()
                .with(dev.windowscraft.block.MonitorBlock.TURN, 1));
        LOG.info("[mp-test] built wall at ground {}", ground);
    }

    private static void place(MinecraftServer server, ServerPlayerEntity p) {
        ServerWorld w = server.getOverworld();
        String name = p.getGameProfile().getName();
        if (name.equals("Alice")) p.teleport(w, 6.5, ground + 1, -4.5, 110f, 0f);   // off to the side, out of Bob's view
        else p.teleport(w, -1.5, ground + 1, -1.5, 180f, -4f);                    // Bob: in front of the screen, slightly left
        LOG.info("[mp-test] placed {}", name);
    }
}
