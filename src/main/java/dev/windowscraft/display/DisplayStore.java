package dev.windowscraft.display;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceDescriptor;
import dev.windowscraft.capture.SourceInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Saves display walls per server/world in config/windowscraft/displays.json (client-side only). */
final class DisplayStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("windowscraft").resolve("displays.json");
    }

    private static JsonObject readRoot() {
        Path f = file();
        if (!Files.exists(f)) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            WindowsCraftClient.LOGGER.warn("Couldn't read {}, starting fresh", f, ex);
            return new JsonObject();
        }
    }

    private static void readPeople(JsonObject o, String key, java.util.Map<UUID, String> into) {
        if (!o.has(key)) return;
        for (JsonElement c : o.getAsJsonArray(key)) {
            JsonObject co = c.getAsJsonObject();
            into.put(UUID.fromString(co.get("id").getAsString()), co.get("name").getAsString());
        }
    }

    private static void writePeople(JsonObject o, String key, java.util.Map<UUID, String> from) {
        if (from.isEmpty()) return;
        JsonArray c = new JsonArray();
        from.forEach((id, name) -> {
            JsonObject co = new JsonObject();
            co.addProperty("id", id.toString());
            co.addProperty("name", name);
            c.add(co);
        });
        o.add(key, c);
    }

    static List<Display> load(String worldKey) {
        List<Display> out = new ArrayList<>();
        JsonObject worlds = readRoot().getAsJsonObject("worlds");
        if (worlds == null || !worlds.has(worldKey)) return out;
        for (JsonElement el : worlds.getAsJsonArray(worldKey)) {
            try {
                JsonObject o = el.getAsJsonObject();
                WallRect rect = WallRect.parse(o.get("face").getAsString(), pos(o.getAsJsonArray("min")), pos(o.getAsJsonArray("max")));
                if (rect == null) continue;
                Display d = new Display(UUID.fromString(o.get("id").getAsString()), o.get("dimension").getAsString(), rect);
                if (o.has("last")) {
                    JsonObject l = o.getAsJsonObject("last");
                    d.lastSource = new SourceDescriptor(SourceInfo.Kind.valueOf(l.get("kind").getAsString()),
                            l.get("title").getAsString(), l.get("exe").getAsString(), l.get("screen").getAsString());
                }
                if (o.has("settings")) {
                    JsonObject s = o.getAsJsonObject("settings");
                    d.lastSettings = new CaptureSettings(s.get("maxHeight").getAsInt(), s.get("fps").getAsInt(), s.get("cursor").getAsBoolean(),
                            !s.has("audio") || s.get("audio").getAsBoolean(), s.has("fit") ? s.get("fit").getAsInt() : CaptureSettings.FIT_AUTO,
                            !s.has("hearInGame") || s.get("hearInGame").getAsBoolean());
                }
                if (o.has("controllers")) {
                    for (JsonElement c : o.getAsJsonArray("controllers")) {
                        JsonObject co = c.getAsJsonObject();
                        d.controllers.put(UUID.fromString(co.get("id").getAsString()), co.get("name").getAsString());
                    }
                }
                if (o.has("access")) {
                    d.access = o.get("access").getAsInt();
                    readPeople(o, "picked", d.picked);
                    readPeople(o, "blocked", d.blocked);
                } else {
                    d.picked.putAll(d.controllers); // (from before: the people you'd allowed)
                }
                if (o.has("ownCopies")) d.ownCopies = o.get("ownCopies").getAsBoolean();
                if (o.has("overrides")) for (var e : o.getAsJsonObject("overrides").entrySet()) d.overrides.put(e.getKey(), e.getValue().getAsString());
                if (o.has("monitorW")) {
                    d.monitorW = o.get("monitorW").getAsInt();
                    d.monitorH = o.get("monitorH").getAsInt();
                }
                out.add(d);
            } catch (Exception ex) {
                WindowsCraftClient.LOGGER.warn("Skipping unreadable display entry {}", el, ex);
            }
        }
        return out;
    }

    static void save(String worldKey, List<Display> displays) {
        JsonObject root = readRoot();
        JsonObject worlds = root.has("worlds") ? root.getAsJsonObject("worlds") : new JsonObject();
        JsonArray arr = new JsonArray();
        for (Display d : displays) {
            if (d.tablet) continue; // (your tablet isn't anywhere in the world)
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id.toString());
            o.addProperty("dimension", d.dimension);
            o.addProperty("face", d.rect.faceString());
            o.add("min", json(d.rect.min()));
            o.add("max", json(d.rect.max()));
            if (d.lastSource != null) {
                JsonObject l = new JsonObject();
                l.addProperty("kind", d.lastSource.kind().name());
                l.addProperty("title", d.lastSource.title());
                l.addProperty("exe", d.lastSource.exeName());
                l.addProperty("screen", d.lastSource.screenKey());
                o.add("last", l);
            }
            if (d.lastSettings != null) {
                JsonObject s = new JsonObject();
                s.addProperty("maxHeight", d.lastSettings.maxHeight());
                s.addProperty("fps", d.lastSettings.fps());
                s.addProperty("cursor", d.lastSettings.showCursor());
                s.addProperty("audio", d.lastSettings.audio());
                s.addProperty("fit", d.lastSettings.fit());
                s.addProperty("hearInGame", d.lastSettings.hearInGame());
                o.add("settings", s);
            }
            o.addProperty("access", d.access);
            writePeople(o, "picked", d.picked);
            writePeople(o, "blocked", d.blocked);
            if (!d.ownCopies) o.addProperty("ownCopies", false);
            if (!d.overrides.isEmpty()) {
                JsonObject ov = new JsonObject();
                d.overrides.forEach(ov::addProperty);
                o.add("overrides", ov);
            }
            if (d.monitorW > 0) {
                o.addProperty("monitorW", d.monitorW);
                o.addProperty("monitorH", d.monitorH);
            }
            if (!d.controllers.isEmpty()) {
                JsonArray c = new JsonArray();
                d.controllers.forEach((id, name) -> {
                    JsonObject co = new JsonObject();
                    co.addProperty("id", id.toString());
                    co.addProperty("name", name);
                    c.add(co);
                });
                o.add("controllers", c);
            }
            arr.add(o);
        }
        worlds.add(worldKey, arr);
        root.add("worlds", worlds);
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("displays.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            WindowsCraftClient.LOGGER.warn("Couldn't save displays", ex);
        }
    }

    private static JsonArray json(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private DisplayStore() {}
}
