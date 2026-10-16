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
 *
 * <p>The performance settings are Automatic (WindowsCraft decides) or Advanced (your own pick for
 * the ones you set; the rest stay Automatic). A screen can have its own pick too, which comes
 * first: the screen's own > yours in Advanced > Automatic ({@link #on(Auto, Display)}).
 */
public final class ClientSettings {
    /** The settings that can be Automatic; the key is how they're saved. */
    public enum Auto {
        GPU_SHARING("gpuSharing"),
        OFFSCREEN_PAUSE("offscreenPause"),
        DISTANCE_LIMIT("distanceFpsLimit"),
        /** Copy your own screens for your walls only as sharp as they look from where you are. */
        SHARP_AS_SEEN("sharpAsSeen");

        public final String key;

        Auto(String key) { this.key = key; }

        static Auto of(String key) {
            for (Auto a : values()) if (a.key.equals(key)) return a;
            return null;
        }
    }

    private static boolean perfAdvanced;
    /** Your picks in Advanced (absent: Automatic for that one). */
    private static final java.util.EnumMap<Auto, Boolean> picks = new java.util.EnumMap<>(Auto.class);
    private static CaptureSettings current;
    private static boolean remoteDirect;
    private static double ownVolume = 1.0;
    private static boolean screensWithoutShaders = false;
    private static double pointerSpeed = 1.0;
    /** The WindowsCraft menu's own size, on top of Minecraft's GUI scale (0.6–2). */
    private static double hubScale = 1.0;
    /** Your upload for screens, shared among the ones people are watching (kbit/s). */
    private static int uploadKbps = 10_000;
    /** Sitting at your own in-game monitor shows it flat on your screen, pixel for pixel. */
    private static boolean deskView = true;
    /** How close you were (× the fitted distance) when you last got up from a screen you use / one you watch. */
    private static double seatZoom = -1, watchZoom = -1;
    /** Programs you picked to always run normally (never as a monitor's own copy). */
    private static java.util.List<String> isoPrograms = new java.util.ArrayList<>();
    private static boolean lightMipmaps = false; // (every picture sharp, from up close or far)
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
                    if (o.has("pointerSpeed")) pointerSpeed = o.get("pointerSpeed").getAsDouble();
        if (o.has("hubScale")) hubScale = Math.max(0.6, Math.min(2, o.get("hubScale").getAsDouble()));
                    readPerf(o);
                    if (o.has("deskView")) deskView = o.get("deskView").getAsBoolean();
                    if (o.has("seatZoom")) seatZoom = o.get("seatZoom").getAsDouble();
                    if (o.has("isoPrograms")) { isoPrograms = new java.util.ArrayList<>(); for (var e : o.getAsJsonArray("isoPrograms")) isoPrograms.add(e.getAsString()); }
                    if (o.has("watchZoom")) watchZoom = o.get("watchZoom").getAsDouble();
                    if (o.has("uploadKbps")) uploadKbps = Math.max(1000, o.get("uploadKbps").getAsInt());
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

    /**
     * Draw screens with their true colours, untouched by shader packs? On a WindowsCraft server
     * that's one setting for everyone (the server's: shaded unless someone turned it off), and
     * flipping it flips it for everyone. Elsewhere, your own (shaded by default).
     */
    public static boolean screensWithoutShaders() {
        Boolean server = serverShaded;
        if (server != null) return !server;
        get();
        return screensWithoutShaders;
    }

    public static void setScreensWithoutShaders(boolean on) {
        if (serverShaded != null && dev.windowscraft.client.ClientNet.setScreensShadedForEveryone(!on)) {
            serverShaded = !on; // (the server tells everyone, us included, straight after)
            return;
        }
        get();
        screensWithoutShaders = on;
        set(current);
    }

    /** Is the shader-pack-on-screens setting the server's (everyone's)? */
    public static boolean screensShadedForEveryone() { return serverShaded != null; }

    /** The server's setting for everyone (null: not on a server that has one). */
    private static volatile Boolean serverShaded;

    public static void serverScreensShaded(Boolean on) { serverShaded = on; }

    /**
     * Direct GPU sharing for your own walls (Windows). Automatic: on, wherever this PC's check
     * says it works (GpuInterop: the safety test, the in-game check, never again with a driver it
     * closed the game with).
     */
    public static boolean gpuSharing() { return on(Auto.GPU_SHARING); }

    // ---- Automatic / Advanced ----

    /** What Automatic picks (all on for now; GPU sharing then still needs this PC's check). */
    public static boolean automatic(Auto a) {
        return true;
    }

    /** What's in effect for all your screens. */
    public static boolean on(Auto a) {
        get();
        Boolean p = perfAdvanced ? picks.get(a) : null;
        return p != null ? p : automatic(a);
    }

    /** What's in effect for this screen: its own pick, if it has one; otherwise like all screens. */
    public static boolean on(Auto a, dev.windowscraft.display.Display d) {
        String v = d == null ? null : d.overrides.get(a.key);
        return v != null ? Boolean.parseBoolean(v) : on(a);
    }

    /** Performance settings: true = Advanced (your picks count), false = Automatic. */
    public static boolean perfAdvanced() { get(); return perfAdvanced; }
    public static void setPerfAdvanced(boolean v) { get(); perfAdvanced = v; set(current); }
    /** Your pick in Advanced, or null (Automatic). */
    public static Boolean pick(Auto a) { get(); return picks.get(a); }
    public static void setPick(Auto a, Boolean v) {
        get();
        if (v == null) picks.remove(a);
        else picks.put(a, v);
        set(current);
    }

    private static void readPerf(JsonObject o) {
        picks.clear();
        if (o.has("perf")) {
            JsonObject p = o.getAsJsonObject("perf");
            perfAdvanced = p.has("mode") && p.get("mode").getAsString().equals("advanced");
            if (p.has("picks")) for (var e : p.getAsJsonObject("picks").entrySet()) {
                Auto a = Auto.of(e.getKey());
                if (a != null) picks.put(a, e.getValue().getAsBoolean());
            }
            return;
        }
        // from before Automatic: a setting you'd turned off stays off (Advanced, for that one)
        if (o.has("gpuSharingExt") && !o.get("gpuSharingExt").getAsBoolean()) picks.put(Auto.GPU_SHARING, false);
        if (o.has("offscreenPause") && !o.get("offscreenPause").getAsBoolean()) picks.put(Auto.OFFSCREEN_PAUSE, false);
        if (o.has("distanceFpsLimit") && !o.get("distanceFpsLimit").getAsBoolean()) picks.put(Auto.DISTANCE_LIMIT, false);
        perfAdvanced = !picks.isEmpty();
    }

    private static JsonObject writePerf() {
        JsonObject p = new JsonObject();
        p.addProperty("mode", perfAdvanced ? "advanced" : "auto");
        JsonObject k = new JsonObject();
        picks.forEach((a, v) -> k.addProperty(a.key, v));
        p.add("picks", k);
        return p;
    }

    /** How fast the screen's pointer moves with the mouse (theater mode), 0.25–3. */
    public static double pointerSpeed() {
        get();
        return pointerSpeed;
    }

    public static double hubScale() {
        get();
        return hubScale;
    }

    public static void setHubScale(double v) {
        get();
        hubScale = Math.max(0.6, Math.min(2, v));
        set(current);
    }

    public static void setPointerSpeed(double v) {
        get();
        pointerSpeed = Math.max(0.25, Math.min(3, v));
        set(current);
    }

    /** Off-screen pause: no per-frame work for a wall that isn't on your screen. */
    public static boolean offscreenPause() { return on(Auto.OFFSCREEN_PAUSE); }
    /** Distance frame-rate limit: walls that look small (far away) update at most 15 times a second. */
    public static boolean distanceFpsLimit() { return on(Auto.DISTANCE_LIMIT); }
    /** Fewer mipmap updates during video: the smaller copies used from a distance are redone 5 times a second, not every frame. */
    public static boolean lightMipmaps() { get(); return lightMipmaps; }
    public static int uploadKbps() { get(); return uploadKbps; }
    public static boolean deskView() { get(); return deskView; }
    public static void setDeskView(boolean v) { get(); deskView = v; set(current); }
    public static java.util.List<String> isoPrograms() { get(); return java.util.List.copyOf(isoPrograms); }
    public static void setIsoPrograms(java.util.List<String> v) { get(); isoPrograms = new java.util.ArrayList<>(v); set(current); }

    /** The zoom you left a screen at (-1: never yet). */
    public static double seatZoom(boolean using) { get(); return using ? seatZoom : watchZoom; }
    public static void setSeatZoom(boolean using, double z) {
        get();
        if (using) seatZoom = z; else watchZoom = z;
        set(current);
    }
    public static void setUploadKbps(int v) { get(); uploadKbps = Math.max(1000, v); set(current); }

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
        o.add("perf", writePerf());
        // (what's in effect, under the old keys: an older WindowsCraft reads these)
        o.addProperty("gpuSharingExt", on(Auto.GPU_SHARING));
        o.addProperty("pointerSpeed", pointerSpeed);
        o.addProperty("hubScale", hubScale);
        o.addProperty("offscreenPause", on(Auto.OFFSCREEN_PAUSE));
        o.addProperty("distanceFpsLimit", on(Auto.DISTANCE_LIMIT));
        o.addProperty("fewerMipmaps", lightMipmaps);
        o.addProperty("uploadKbps", uploadKbps);
        o.addProperty("deskView", deskView);
        o.addProperty("seatZoom", seatZoom);
        com.google.gson.JsonArray iso = new com.google.gson.JsonArray();
        for (String p : isoPrograms) iso.add(p);
        o.add("isoPrograms", iso);
        o.addProperty("watchZoom", watchZoom);
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
