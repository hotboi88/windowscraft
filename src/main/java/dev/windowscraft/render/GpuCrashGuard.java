package dev.windowscraft.render;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.config.ClientSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Some graphics drivers kill the whole game (fail-fast, no error report) if Direct3D↔OpenGL
 * sharing goes wrong. A marker file is written before the risky calls and removed once sharing
 * has run smoothly for a while; if the game starts and finds the marker, the last run died during
 * those calls, so direct GPU sharing is switched off and the player is told why.
 */
public final class GpuCrashGuard {
    private static boolean armed;
    private static boolean trippedThisLaunch;

    /** Which graphics card + driver is being tried (written into the marker). */
    static volatile String driverKey = "";

    private static Path crashedFile() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("gpu-sharing-ext-crashed.txt"); // the earlier route's crashes don't count against this one
    }

    /** Did it already close the game once with this graphics card and driver? Then never again (until a driver update). */
    static boolean crashedBefore(String key) {
        try {
            return Files.exists(crashedFile()) && Files.readAllLines(crashedFile()).contains(key);
        } catch (Exception e) {
            return false;
        }
    }

    private static Path marker() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("gpu-sharing-ext-in-progress");
    }

    /** At startup: did the previous run die while direct GPU sharing was starting? */
    public static void checkAtStartup() {
        try {
            if (Files.exists(marker())) {
                String key = Files.readString(marker()).trim();
                if (!key.isEmpty()) Files.writeString(crashedFile(), key + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                Files.deleteIfExists(marker());
                ClientSettings.setGpuSharing(false);
                trippedThisLaunch = true;
                WindowsCraftClient.LOGGER.warn("The game closed unexpectedly while direct GPU sharing was starting last time; it is now off");
            }
        } catch (Exception ignored) {
        }
    }

    /** Was it switched off at this launch because of a crash? (shown once to the player) */
    public static boolean consumeTripped() {
        boolean t = trippedThisLaunch;
        trippedThisLaunch = false;
        return t;
    }

    /** Before a risky driver call. */
    public static void arm() {
        if (armed) return;
        armed = true;
        try {
            Files.createDirectories(marker().getParent());
            Files.writeString(marker(), driverKey);
        } catch (Exception ignored) {
        }
    }

    /** After sharing has worked for a while (or was turned off cleanly). */
    public static void disarm() {
        if (!armed) return;
        armed = false;
        try {
            Files.deleteIfExists(marker());
        } catch (Exception ignored) {
        }
    }

    private GpuCrashGuard() {}
}
