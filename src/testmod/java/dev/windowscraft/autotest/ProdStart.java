package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Dev only (tools/prodclient): opens the test world once the game has really finished loading —
 * -Dwindowscraft.prod.world=NAME. ("--quickPlaySingleplayer" starts the world while a big modpack's
 * custom loading screen is still loading textures; the two then wait on each other for ever.)
 */
public final class ProdStart implements ClientModInitializer {
    private boolean started;
    private int quietTicks;

    @Override
    public void onInitializeClient() {
        String world = System.getProperty("windowscraft.prod.world");
        if (world == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (started || mc.world != null) return;
            // loading done: no loading overlay, and a menu up (the title screen, or a mod's) for a moment
            quietTicks = mc.getOverlay() == null && mc.currentScreen != null ? quietTicks + 1 : 0;
            if (quietTicks < 40) return;
            started = true;
            WindowsCraftClient.LOGGER.info("[prod] game loaded; opening the world '{}'", world);
            mc.createIntegratedServerLoader().start(world, () -> WindowsCraftClient.LOGGER.warn("[prod] the world '{}' couldn't be opened", world));
        });
    }
}
