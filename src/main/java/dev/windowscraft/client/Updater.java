package dev.windowscraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.net.Payloads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Updates WindowsCraft from the server you play on. A server with WindowsCraft says which
 * version it runs; if that's newer than yours and you said yes to updates from that server
 * (asked once per server, in chat), the new jar comes over the Minecraft connection, is checked
 * against the server's own checksum, and waits next to the old one. When you quit the game, a
 * small background step swaps them — next start, you're up to date. Nothing is ever taken from a
 * server you didn't say yes to.
 */
public final class Updater {
    private static final String SUFFIX = ".wcupdate";
    private static Payloads.UpdateOffer offered;
    private static String offeredBy;
    private static byte[][] parts;
    private static int got;
    private static boolean downloading;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(Payloads.UpdateOffer.ID, (p, ctx) -> ctx.client().execute(() -> onOffer(ctx.client(), p)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.UpdateChunk.ID, (p, ctx) -> ctx.client().execute(() -> onChunk(ctx.client(), p)));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("windowscraft").then(ClientCommandManager.literal("update")
                        .then(ClientCommandManager.literal("yes").executes(ctx -> { answer(true); return 1; }))
                        .then(ClientCommandManager.literal("no").executes(ctx -> { answer(false); return 1; })))));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> applyOnExit());
        tidyAtStartup();
    }

    // ---- versions ---------------------------------------------------------------------------------

    static String mine() {
        return FabricLoader.getInstance().getModContainer(WindowsCraftClient.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Is version a newer than b? ("0.13.3+mc1.21.1" style: the numbers before the "+") */
    static boolean newer(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] nums(String v) {
        String core = v.split("\\+", 2)[0].split("-", 2)[0];
        String[] p = core.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { out[i] = Integer.parseInt(p[i].replaceAll("\\D", "")); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    // ---- where things are -------------------------------------------------------------------------

    /** The jar this game runs WindowsCraft from (null in a development setup). */
    static Path myJar() {
        var mod = FabricLoader.getInstance().getModContainer(WindowsCraftClient.MOD_ID).orElse(null);
        if (mod == null) return null;
        for (Path p : mod.getOrigin().getPaths()) if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) return p;
        return null;
    }

    /** The mods folder updates go into: the one this jar is in (tests: the game's own). */
    static Path modsDir() {
        Path jar = myJar();
        if (jar != null) return jar.getParent();
        return Boolean.getBoolean("windowscraft.update.test") ? FabricLoader.getInstance().getGameDir().resolve("mods") : null;
    }

    /** Which server this is, for "yes, updates from this server": its address as you added it. */
    private static String serverKey(MinecraftClient mc) {
        var entry = mc.getCurrentServerEntry();
        if (entry != null) return entry.address.trim().toLowerCase(Locale.ROOT);
        return Boolean.getBoolean("windowscraft.update.test") ? "test" : null; // (your own world, or LAN: nothing to update from)
    }

    private static Path trustFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("windowscraft").resolve("update-servers.json");
    }

    /** "yes" / "no" / null (not asked yet) for that server. */
    private static String trust(String key) {
        if (Boolean.getBoolean("windowscraft.update.test")) return "yes";
        try {
            Path f = trustFile();
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (o.has(key)) return o.get(key).getAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Every server you answered: address → "yes" / "no". */
    public static java.util.Map<String, String> servers() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        try {
            Path f = trustFile();
            if (!Files.exists(f)) return out;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (String k : o.keySet()) out.put(k, o.get(k).getAsString());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Changes your answer for a server (from the settings). */
    public static void setTrusted(String key, boolean yes) { setTrust(key, yes); }

    private static void setTrust(String key, boolean yes) {
        try {
            Path f = trustFile();
            JsonObject o = Files.exists(f) ? JsonParser.parseString(Files.readString(f)).getAsJsonObject() : new JsonObject();
            o.addProperty(key, yes ? "yes" : "no");
            Files.createDirectories(f.getParent());
            Files.writeString(f, o.toString());
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.warn("Couldn't save the update choice", e);
        }
    }

    // ---- the offer and the download ---------------------------------------------------------------

    private static void onOffer(MinecraftClient mc, Payloads.UpdateOffer o) {
        String mine = mine();
        if (!newer(o.version(), mine) || modsDir() == null) return;
        String key = serverKey(mc);
        if (key == null) return;
        if (already(o)) {
            say(mc, Text.literal("WindowsCraft " + o.version() + " is downloaded already: it's used from the next time you start the game.").formatted(Formatting.GRAY));
            return;
        }
        offered = o;
        offeredBy = key;
        String t = trust(key);
        if ("yes".equals(t)) {
            download(mc);
        } else if (t == null) {
            Text yes = Text.literal("[Yes, from now on]").formatted(Formatting.GREEN, Formatting.UNDERLINE).styled(s -> s
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/windowscraft update yes"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Get WindowsCraft updates from this server by itself from now on"))));
            Text no = Text.literal("[No]").formatted(Formatting.GRAY, Formatting.UNDERLINE).styled(s -> s
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/windowscraft update no")));
            say(mc, Text.literal("This server has WindowsCraft " + o.version() + " (you have " + mine.split("\\+")[0]
                    + "). Get updates from this server automatically? ").formatted(Formatting.YELLOW).append(yes).append(" ").append(no));
        }
        // "no": quiet
    }

    private static void answer(boolean yes) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (offeredBy == null) {
            say(mc, Text.literal("No WindowsCraft update is on offer right now.").formatted(Formatting.GRAY));
            return;
        }
        setTrust(offeredBy, yes);
        if (yes) download(mc);
        else say(mc, Text.literal("OK — no updates from this server (delete config/windowscraft/update-servers.json to be asked again).").formatted(Formatting.GRAY));
    }

    private static void download(MinecraftClient mc) {
        Payloads.UpdateOffer o = offered;
        if (o == null || downloading || !ClientPlayNetworking.canSend(Payloads.UpdateRequest.ID)) return;
        downloading = true;
        int total = (o.size() + Payloads.UpdateChunk.SIZE - 1) / Payloads.UpdateChunk.SIZE;
        parts = new byte[total][];
        got = 0;
        ClientPlayNetworking.send(new Payloads.UpdateRequest(o.version()));
        say(mc, Text.literal("Downloading WindowsCraft " + o.version() + " from this server…").formatted(Formatting.GRAY));
    }

    private static void onChunk(MinecraftClient mc, Payloads.UpdateChunk c) {
        Payloads.UpdateOffer o = offered;
        if (!downloading || o == null || parts == null || c.total() != parts.length || c.index() < 0 || c.index() >= parts.length) return;
        if (parts[c.index()] == null) got++;
        parts[c.index()] = c.data();
        if (got < parts.length) return;
        downloading = false;
        try {
            byte[] all = new byte[o.size()];
            int at = 0;
            for (byte[] p : parts) {
                if (at + p.length > all.length) throw new IOException("bigger than announced");
                System.arraycopy(p, 0, all, at, p.length);
                at += p.length;
            }
            if (at != all.length) throw new IOException("smaller than announced");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(all));
            if (!sha.equalsIgnoreCase(o.sha256())) throw new IOException("checksum doesn't match");
            Path dir = modsDir();
            for (Path old : pending(dir)) Files.deleteIfExists(old); // an older download
            Path file = dir.resolve(safeName(o) + SUFFIX);
            Files.write(file, all);
            WindowsCraftClient.LOGGER.info("WindowsCraft {} downloaded to {}", o.version(), file);
            say(mc, Text.literal("WindowsCraft " + o.version() + " is downloaded: it's used from the next time you start the game.").formatted(Formatting.GREEN));
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.warn("WindowsCraft update failed", e);
            say(mc, Text.literal("Couldn't update WindowsCraft (" + e.getMessage() + "). It'll try again next time you join.").formatted(Formatting.YELLOW));
        } finally {
            parts = null;
        }
    }

    /** The jar's name as the server has it, if it's a sensible one. */
    private static String safeName(Payloads.UpdateOffer o) {
        String n = o.fileName();
        if (n.matches("[A-Za-z0-9._+-]{1,120}\\.jar") && n.toLowerCase(Locale.ROOT).startsWith("windowscraft")) return n;
        return "windowscraft-" + o.version().replaceAll("[^A-Za-z0-9._+-]", "") + ".jar";
    }

    private static boolean already(Payloads.UpdateOffer o) {
        Path dir = modsDir();
        if (dir == null) return false;
        return Files.isRegularFile(dir.resolve(safeName(o) + SUFFIX));
    }

    static List<Path> pending(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var s = Files.newDirectoryStream(dir, "windowscraft*" + SUFFIX)) {
            s.forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    // ---- swapping, once the game is closed --------------------------------------------------------

    /** A download that's no newer than what runs (it was applied, or is stale) goes. */
    private static void tidyAtStartup() {
        Path dir = modsDir();
        for (Path p : pending(dir)) {
            String name = p.getFileName().toString();
            String version = name.replaceFirst("(?i)^windowscraft-?", "").replaceFirst("\\.jar" + SUFFIX.replace(".", "\\.") + "$", "");
            if (!newer(version, mine())) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            } else {
                WindowsCraftClient.LOGGER.info("A WindowsCraft update is waiting ({}): it's put in place when the game closes", name);
            }
        }
    }

    /**
     * The game is closing: a small background step waits until it has fully exited (Windows keeps
     * the running jar locked until then), then takes the old jar out and puts the new one in.
     */
    private static void applyOnExit() {
        Path dir = modsDir(), old = myJar();
        if (old == null) return; // (a development setup: nothing to swap)
        List<Path> waiting = pending(dir);
        if (waiting.isEmpty()) return;
        Path update = waiting.get(0);
        String newName = update.getFileName().toString().substring(0, update.getFileName().toString().length() - SUFFIX.length());
        Path log = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("update-log.txt");
        long pid = ProcessHandle.current().pid();
        try {
            List<String> cmd;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                String script = "$ErrorActionPreference='Continue';"
                        + "Wait-Process -Id " + pid + " -Timeout 120 -ErrorAction SilentlyContinue;"
                        + "Start-Sleep -Milliseconds 700;"
                        + "$ok=$true;"
                        + (old != null ? "for($i=0;$i -lt 20 -and (Test-Path -LiteralPath " + q(old) + ");$i++){Remove-Item -LiteralPath " + q(old) + " -Force -ErrorAction SilentlyContinue; if(Test-Path -LiteralPath " + q(old) + "){Start-Sleep -Milliseconds 500}};"
                        + "if(Test-Path -LiteralPath " + q(old) + "){$ok=$false};" : "")
                        + "if($ok){Move-Item -LiteralPath " + q(update) + " -Destination " + q(dir.resolve(newName)) + " -Force};"
                        + "Add-Content -LiteralPath " + q(log) + " -Value ((Get-Date).ToString('s')+' update to " + newName.replace("'", "") + ": '+$(if($ok){'done'}else{'old jar still locked, trying next time'}))";
                cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
            } else {
                String sh = "while kill -0 " + pid + " 2>/dev/null; do sleep 0.2; done; "
                        + (old != null ? "rm -f '" + old + "'; " : "") + "mv -f '" + update + "' '" + dir.resolve(newName) + "'";
                cmd = List.of("sh", "-c", sh);
            }
            Files.createDirectories(log.getParent());
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            WindowsCraftClient.LOGGER.info("WindowsCraft update: {} goes in once the game has closed", newName);
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.warn("Couldn't schedule the WindowsCraft update", e);
        }
    }

    /** A path as a PowerShell single-quoted string. */
    static String q(Path p) {
        return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    private static void say(MinecraftClient mc, Text t) {
        if (mc.player != null) mc.player.sendMessage(t, false);
    }

    private Updater() {}
}
