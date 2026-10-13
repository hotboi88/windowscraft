package dev.windowscraft.client;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.Share;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Keeps the game smooth when your PC (or the server you play on) is struggling: if the game's
 * world slows down (the server can't keep 20 ticks a second) or your own frame rate drops low
 * while your screens are being sent, the screens send lighter pictures — before you, or anyone
 * playing with you, feels it as lag. Each step waits a while to show its effect, and the screens
 * go back up by themselves once things are calm (the stream's own "try better again").
 */
public final class BusyGuard {
    private static long lastWorldTime = -1, lastAt, lastStepAt, toldAt;
    private static int slowSeconds, lowFpsSeconds;
    /** For tests and the report: the world's speed (ticks a second) as last measured. */
    public static volatile double tps = 20;
    /** Tests that hold the game at a low frame rate on purpose turn it off. */
    public static volatile boolean enabled = true;

    public static void tick(MinecraftClient mc) {
        if (mc.world == null) {
            lastWorldTime = -1;
            return;
        }
        long now = System.nanoTime();
        if (lastWorldTime < 0) {
            lastWorldTime = mc.world.getTime();
            lastAt = now;
            return;
        }
        if (now - lastAt < 2_000_000_000L) return;
        long ticks = mc.world.getTime() - lastWorldTime;
        double secs = (now - lastAt) / 1e9;
        lastWorldTime = mc.world.getTime();
        lastAt = now;
        if (mc.isPaused()) return;
        tps = ticks / secs;
        boolean sending = DisplayManager.get().liveShares().stream().anyMatch(s -> s.publisher() != null && s.publisher().streaming());
        slowSeconds = tps < 15 ? slowSeconds + 2 : 0;
        lowFpsSeconds = mc.getCurrentFps() < 25 && !TheaterMode.active() ? lowFpsSeconds + 2 : 0;
        if (!enabled || !sending || now - lastStepAt < 20_000_000_000L) return;
        String why = slowSeconds >= 6 ? String.format(java.util.Locale.ROOT, "the game world is lagging (%.0f ticks a second)", tps)
                : lowFpsSeconds >= 10 ? "your game's frame rate is low (" + mc.getCurrentFps() + " fps)" : null;
        if (why == null) return;
        lastStepAt = now;
        int lowered = 0;
        for (Share s : DisplayManager.get().liveShares()) {
            if (s.publisher() != null && s.publisher().streaming() && s.publisher().busy(why)) lowered++;
        }
        if (lowered == 0) return;
        WindowsCraftClient.LOGGER.warn("Busy: {}; {} screen stream(s) step down", why, lowered);
        if (now - toldAt > 120_000_000_000L && mc.player != null) {
            toldAt = now;
            mc.player.sendMessage(Text.literal("Your PC is busy (" + why + "): your screens send lighter pictures for now").formatted(Formatting.GRAY), true);
        }
    }

    private BusyGuard() {}
}
