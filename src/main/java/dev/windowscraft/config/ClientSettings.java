package dev.windowscraft.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The options you picked last time: share-dialog defaults, and how you like to hear other
 * people's screens (3D from the wall, or direct).
 */
public final class ClientSettings {
    private static CaptureSettings current;
    private static boolean remoteDirect;
    private static double ownVolume = 1.0;
    private static boolean screensWithoutShaders = true;
    private static boolean gpuSharing = true;
    private static double pointerSpeed = 1.0;
    private static boolean offscreenPause = true, distanceFpsLimit = true, lightMipmaps = false; // (every picture sharp, from up close or far)
    private static int tabletW = 1920, tabletH = 1080; // (wide, like a monitor)

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("windowscraft").resolve("settings.json");
    }

    public static CaptureSettings get() {
        if (current == null) {
            current = new CaptureSettings(1080, 30, true);
            try {
                if (Files.exists(file())) {
                    JsonObject o = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
                    current = new CaptureSettings(o.get("maxHeight").getAsInt(), o.get("fps").getAsInt(), o.get("cursor").getAsBoolean(),
                            !o.has("audio") || o.get("audio").getAsBoolean(), o.has("fit") ? o.get("fit").getAsInt() : CaptureSettings.FIT_AUTO,
                            !o.has("hearInGame") || o.get("hearInGame").getAsBoolean());
                    remoteDirect = o.has("remoteDirect") && o.get("remoteDirect").getAsBoolean();
                    if (o.has("ownVolume")) ownVolume = o.get("ownVolume").getAsDouble();
                    if (o.has("screensWithoutShaders")) screensWithoutShaders = o.get("screensWithoutShaders").getAsBoolean();
                    // a new key for the new way of sharing (OpenGL external memory): on unless it was turned off
                    if (o.has("gpuSharingExt")) gpuSharing = o.get("gpuSharingExt").getAsBoolean();
                    if (o.has("pointerSpeed")) pointerSpeed = o.get("pointerSpeed").getAsDouble();
                    if (o.has("offscreenPause")) offscreenPause = o.get("offscreenPause").getAsBoolean();
                    if (o.has("distanceFpsLimit")) distanceFpsLimit = o.get("distanceFpsLimit").getAsBoolean();
                    if (o.has("fewerMipmaps")) lightMipmaps = o.get("fewerMipmaps").getAsBoolean(); // (a new key: the old default was on)
                    if (o.has("tabletWide")) { // (the earlier 4:3 default isn't kept: only a size you picked)
                        tabletW = o.get("tabletWide").getAsInt();
                        tabletH = o.get("tabletHigh").getAsInt();
                    }
                }
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.warn("Couldn't read settings, using defaults", e);
            }
        }
        return current;
    }

    /** Default for other people's screens: true = direct (crisp, no 3D effects). */
    public static boolean remoteDirect() {
        get();
        return remoteDirect;
    }

    public static void setRemoteDirect(boolean direct) {
        get();
        remoteDirect = direct;
        set(current);
    }

    /** How loud you hear your own shared app from the wall ("Game" audio), 0–5. */
    public static double ownVolume() {
        get();
        return ownVolume;
    }

    public static void setOwnVolume(double v) {
        get();
        ownVolume = Math.max(0, Math.min(5, v));
        set(current);
    }

    /** Draw screens with their true colours, untouched by shader packs (default on). */
    public static boolean screensWithoutShaders() {
        get();
        return screensWithoutShaders;
    }

    public static void setScreensWithoutShaders(boolean on) {
        get();
        screensWithoutShaders = on;
        set(current);
    }

    /** Direct GPU sharing for your own walls (Windows; opt-in; switched off automatically after a crash). */
    public static boolean gpuSharing() {
        get();
        return gpuSharing;
    }

    public static void setGpuSharing(boolean on) {
        get();
        gpuSharing = on;
        set(current);
    }

    /** How fast the screen's pointer moves with the mouse (theater mode), 0.25–3. */
    public static double pointerSpeed() {
        get();
        return pointerSpeed;
    }

    public static void setPointerSpeed(double v) {
        get();
        pointerSpeed = Math.max(0.25, Math.min(3, v));
        set(current);
    }

    /** Off-screen pause: no per-frame work for a wall that isn't on your screen. */
    public static boolean offscreenPause() { get(); return offscreenPause; }
    /** Distance frame-rate limit: walls that look small (far away) update at most 15 times a second. */
    public static boolean distanceFpsLimit() { get(); return distanceFpsLimit; }
    /** Fewer mipmap updates during video: the smaller copies used from a distance are redone 5 times a second, not every frame. */
    public static boolean lightMipmaps() { get(); return lightMipmaps; }

    public static void setOffscreenPause(boolean v) { get(); offscreenPause = v; set(current); }
    public static void setDistanceFpsLimit(boolean v) { get(); distanceFpsLimit = v; set(current); }
    public static void setLightMipmaps(boolean v) { get(); lightMipmaps = v; set(current); }

    /** The size your tablet's monitor last had (picked in the U menu). */
    public static int[] tabletSize() { get(); return new int[]{tabletW, tabletH}; }
    public static void setTabletSize(int w, int h) { get(); tabletW = w; tabletH = h; set(current); }

    public static void set(CaptureSettings s) {
        current = s;
        JsonObject o = new JsonObject();
        o.addProperty("maxHeight", s.maxHeight());
        o.addProperty("fps", s.fps());
        o.addProperty("cursor", s.showCursor());
        o.addProperty("audio", s.audio());
        o.addProperty("fit", s.fit());
        o.addProperty("hearInGame", s.hearInGame());
        o.addProperty("remoteDirect", remoteDirect);
        o.addProperty("ownVolume", ownVolume);
        o.addProperty("screensWithoutShaders", screensWithoutShaders);
        o.addProperty("gpuSharingExt", gpuSharing);
        o.addProperty("pointerSpeed", pointerSpeed);
        o.addProperty("offscreenPause", offscreenPause);
        o.addProperty("distanceFpsLimit", distanceFpsLimit);
        o.addProperty("fewerMipmaps", lightMipmaps);
        o.addProperty("tabletWide", tabletW);
        o.addProperty("tabletHigh", tabletH);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.warn("Couldn't save settings", e);
        }
    }

    private ClientSettings() {}
}
