package dev.windowscraft.monitor;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Own copies of apps on each in-game monitor, through Sandboxie-Plus (free, open source): each
 * monitor has its own box, and what you start while you sit at it is its own copy — its own sound,
 * its own logins and data, kept. Sandboxie is installed once (quietly, one permission prompt) and
 * never shows itself: WindowsCraft drives it.
 */
public final class Sandbox {
    /** The exact build that was checked (silent install, hidden, boxes) — and its fingerprint. */
    static final String VERSION = "1.18.6";
    static final String URL = "https://github.com/sandboxie-plus/Sandboxie/releases/download/v" + VERSION + "/Sandboxie-Plus-x64-v" + VERSION + ".exe";
    static final String SHA256 = "3ad29e1ec18618ee9aa4ef4e21ab0548cf2188b94c05c41cb7a07b5d2d0270e2";

    private static volatile String status = "unknown";
    private static volatile String progress;
    private static volatile List<String> autoFolders = List.of();
    private static volatile List<Path> libraries = List.of();
    private static volatile boolean scanned;

    /** "ok", "missing", "stopped", "unknown" (not asked yet). */
    public static String status() { return status; }

    public static boolean ready() { return "ok".equals(status); }

    /** While setting it up: what's happening ("Downloading… 40 %"), else null. */
    public static String progress() { return progress; }

    /** Asks the helper (once a monitor is on). */
    public static void refresh() {
        MonitorBackend b = InGameMonitors.backend();
        if (b == null) return;
        b.sandboxStatus().thenAccept(s -> {
            boolean was = ready();
            status = s;
            if (!was && ready()) pushAll();
        }).exceptionally(t -> null);
        if (!scanned) {
            scanned = true;
            Thread t = new Thread(() -> { libraries = gameLibraries(); autoFolders = antiCheatFoldersIn(libraries); pushExceptions(); }, "WindowsCraft anti-cheat scan");
            t.setDaemon(true);
            t.start();
        }
    }

    /** That monitor's settings to the helper (its box, own copies on or off). */
    static void push(MonitorBackend.Mon m, Display d) {
        MonitorBackend b = InGameMonitors.backend();
        if (b == null || m == null || d == null) return;
        // (joined monitors are one PC with several screens: one box, the group's)
        b.profileKey(m, InGameMonitors.groupKey(d));
        b.boxOptions(m, d.followDesk, !d.filesHere); // (before isolate: the box is never emptied for the wrong reason)
        b.isolate(m, d.ownCopies);
    }

    public static void pushAll() {
        pushExceptions();
        for (Display d : DisplayManager.get().all()) {
            MonitorBackend.Mon m = InGameMonitors.monitorOf(d);
            if (m != null) push(m, d);
        }
    }

    /** What never goes in a box: what you picked, WindowsCraft and the game, games with anti-cheat. */
    public static void pushExceptions() {
        MonitorBackend b = InGameMonitors.backend();
        if (b == null) return;
        List<String> folders = new ArrayList<>();
        folders.add(FabricLoader.getInstance().getGameDir().toAbsolutePath().toString());
        String jh = System.getProperty("java.home");
        if (jh != null) folders.add(Path.of(jh).toAbsolutePath().toString());
        folders.addAll(autoFolders);
        List<String> apps = new ArrayList<>();
        for (Path p : libraries) apps.add(p.toAbsolutePath().toString());
        b.isolationExceptions(ClientSettings.isoPrograms(), folders, apps);
    }

    /** Games with anti-cheat (they refuse to run in a box): their folders, found in your game libraries. */
    public static List<String> antiCheatFolders() { return antiCheatFoldersIn(gameLibraries()); }

    static List<String> antiCheatFoldersIn(List<Path> libraries) {
        List<String> out = new ArrayList<>();
        for (Path lib : libraries) {
            try (var games = Files.list(lib)) {
                for (Path g : (Iterable<Path>) games::iterator) {
                    if (Files.isDirectory(g) && hasAntiCheat(g)) out.add(g.toAbsolutePath().toString());
                }
            } catch (Exception ignored) {
            }
        }
        Path riot = Path.of("C:\\Riot Games");
        if (Files.isDirectory(riot)) out.add(riot.toString());
        return out;
    }

    static boolean hasAntiCheat(Path game) {
        try (var walk = Files.walk(game, 3)) {
            return walk.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.equals("easyanticheat") || n.equals("battleye") || n.startsWith("easyanticheat") && n.endsWith(".exe")
                        || n.startsWith("beservice") || n.equals("start_protected_game.exe") || n.equals("vgc.exe") || n.equals("anticheat");
            });
        } catch (Exception e) {
            return false;
        }
    }

    /** Folders that hold one game each: Steam libraries, Epic Games. */
    static List<Path> gameLibraries() {
        List<Path> out = new ArrayList<>();
        try {
            String steam = com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Valve\\Steam", "SteamPath");
            Path vdf = Path.of(steam, "steamapps", "libraryfolders.vdf");
            List<Path> libs = new ArrayList<>(List.of(Path.of(steam)));
            if (Files.isRegularFile(vdf)) {
                var m = java.util.regex.Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf));
                while (m.find()) libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
            }
            for (Path l : libs) {
                Path common = l.resolve("steamapps").resolve("common");
                if (Files.isDirectory(common) && !out.contains(common)) out.add(common);
            }
        } catch (Throwable ignored) {
        }
        for (String pf : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
            if (pf == null) continue;
            Path epic = Path.of(pf, "Epic Games");
            if (Files.isDirectory(epic)) out.add(epic);
        }
        return out;
    }

    /**
     * Sets Sandboxie-Plus up: downloads the checked build (its fingerprint must match), installs it
     * quietly (Windows asks for permission once). Completes with the status after.
     */
    public static CompletableFuture<String> install() {
        MonitorBackend b = InGameMonitors.backend();
        if (b == null) return CompletableFuture.completedFuture("missing");
        return CompletableFuture.supplyAsync(() -> {
            try {
                progress = "Downloading…";
                Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("downloads");
                Files.createDirectories(dir);
                Path file = dir.resolve("Sandboxie-Plus-x64-v" + VERSION + ".exe");
                if (!Files.isRegularFile(file) || !sha256(file).equals(SHA256)) {
                    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
                    HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) throw new java.io.IOException("download failed (HTTP " + resp.statusCode() + ")");
                    long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
                    Path tmp = dir.resolve(file.getFileName() + ".part");
                    try (InputStream in = resp.body(); var out = Files.newOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 16];
                        long got = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            got += n;
                            if (total > 0) progress = "Downloading… " + (got * 100 / total) + " %";
                        }
                    }
                    if (!sha256(tmp).equals(SHA256)) {
                        Files.deleteIfExists(tmp);
                        throw new java.io.IOException("the download didn't match the checked build");
                    }
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                progress = "Installing (Windows asks for permission)…";
                String s = b.installSandbox(file).get(10, java.util.concurrent.TimeUnit.MINUTES);
                status = s;
                if (ready()) pushAll();
                return s;
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.warn("Couldn't set up own copies of apps", e);
                return "failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                progress = null;
            }
        });
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private Sandbox() {}
}
