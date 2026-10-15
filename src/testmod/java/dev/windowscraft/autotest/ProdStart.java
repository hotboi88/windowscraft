package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * Dev only (tools/prodclient): once the game has really finished loading, opens the test world
 * (-Dwindowscraft.prod.world=NAME) or joins a server (-Dwindowscraft.prod.server=HOST:PORT).
 * ("--quickPlay…" starts while a big modpack's custom loading screen is still loading textures;
 * the two then wait on each other for ever.)
 */
public final class ProdStart implements ClientModInitializer {
    private boolean started;
    private int quietTicks;

    @Override
    public void onInitializeClient() {
        String world = System.getProperty("windowscraft.prod.world"), server = System.getProperty("windowscraft.prod.server");
        if (world == null && server == null) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (started || mc.world != null) return;
            // loading done: no loading overlay, and a menu up (the title screen, or a mod's) for a moment
            quietTicks = mc.getOverlay() == null && mc.currentScreen != null ? quietTicks + 1 : 0;
            if (quietTicks < 40) return;
            started = true;
            if (server != null) {
                WindowsCraftClient.LOGGER.info("[prod] game loaded; joining {}", server);
                ConnectScreen.connect(mc.currentScreen, mc, ServerAddress.parse(server), new ServerInfo("test", server, ServerInfo.ServerType.OTHER), false, null);
                return;
            }
            WindowsCraftClient.LOGGER.info("[prod] game loaded; opening the world '{}'", world);
            mc.createIntegratedServerLoader().start(world, () -> WindowsCraftClient.LOGGER.warn("[prod] the world '{}' couldn't be opened", world));
        });
    }
}
