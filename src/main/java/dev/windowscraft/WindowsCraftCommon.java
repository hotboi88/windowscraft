package dev.windowscraft;

import dev.windowscraft.net.Payloads;
import dev.windowscraft.net.ServerHub;
import net.fabricmc.api.ModInitializer;

/**
 * Runs on both sides. On a server (dedicated, or a world opened to LAN) this is the companion
 * that lets players see each other's screens. It adds the gaming monitor blocks and tablets, so
 * the server and everyone on it need the same WindowsCraft.
 */
public final class WindowsCraftCommon implements ModInitializer {
    @Override
    public void onInitialize() {
        dev.windowscraft.block.Monitors.register();
        Payloads.register();
        ServerHub.init();
    }
}
