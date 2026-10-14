package dev.windowscraft.stream;

import dev.windowscraft.WindowsCraftClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Finds (or downloads, once) an FFmpeg executable and picks the best H.264 encoder this PC has:
 * NVIDIA NVENC → AMD AMF → Intel Quick Sync → software x264. FFmpeg runs as a separate process,
 * so it never touches Minecraft's memory or threads.
 */
public final class Ffmpeg {
    // One tested FFmpeg for everyone: the newest daily build changes under us (one changed how
    // sound timestamps are taken and nearly all stream sound was lost). FFmpeg 9.0.2 from BtbN's
    // month-end build (those are kept about two years), checked against its own SHA-256.
    private static final String PIN = "n9.0.2-17-g2a571b6068";
    private static final String PIN_ZIP = "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-30-13-08/ffmpeg-n9.0.2-17-g2a571b6068-win64-gpl-9.0.zip";
    private static final String PIN_SHA256 = "a0e45723c72141975f51d8666302e614711745f3102b704ca3f82c897a58d278";
    // if that build is gone one day: the newest of the same release line (fixes only), checked
    // against its published checksum list
    private static final String WIN_ZIP = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-win64-gpl-9.0.zip";
    private static final String WIN_SUMS = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/checksums.sha256";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public enum State { UNKNOWN, CHECKING, DOWNLOADING, READY, FAILED }

    private static volatile State state = State.UNKNOWN;
    private static volatile String status = "";
    private static volatile double progress;
    private static volatile Path executable;
    private static volatile Encoder encoder;
    private static CompletableFuture<Path> pending;

    /** How to drive one H.264 encoder at low latency. */
    public record Encoder(String name, String label, List<String> args) {}

    public static State state() { return state; }
    public static String status() { return status; }
    public static double progress() { return progress; }
    public static Path executable() { return executable; }
    public static Encoder encoder() { return encoder; }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("ffmpeg");
    }

    /** Starts locating/downloading FFmpeg in the background (idempotent). */
    public static synchronized CompletableFuture<Path> ensure() {
        if (pending != null && !(state == State.FAILED)) return pending;
        state = State.CHECKING;
        status = "Looking for FFmpeg…";
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                Path p = locate();
                if (p == null && WINDOWS) p = download(dir().resolve("ffmpeg.exe"));
                if (p == null) throw new IOException("FFmpeg not found. Install it (e.g. 'winget install Gyan.FFmpeg') or put ffmpeg in " + dir());
                executable = p;
                encoder = pickEncoder(p);
                status = "Ready · " + encoder.label;
                state = State.READY;
                WindowsCraftClient.LOGGER.info("FFmpeg at {}, using encoder {}", p, encoder.name);
                return p;
            } catch (Exception e) {
                state = State.FAILED;
                status = e.getMessage();
                WindowsCraftClient.LOGGER.error("FFmpeg setup failed", e);
                throw new RuntimeException(e);
            }
        });
        return pending;
    }

    private static Path locate() {
        String override = System.getProperty("windowscraft.ffmpeg");
        if (override != null && works(Path.of(override))) return Path.of(override);
        Path own = dir().resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
        if (WINDOWS) {
            // the tested build fetched last time (below) takes over from an older download now,
            // before any stream runs
            Path next = dir().resolve("ffmpeg-next.exe");
            if (Files.isRegularFile(next)) {
                try {
                    if (works(next)) Files.move(next, own, StandardCopyOption.REPLACE_EXISTING);
                    else Files.delete(next);
                } catch (IOException e) {
                    WindowsCraftClient.LOGGER.warn("Couldn't switch to the new FFmpeg yet ({}); next start", e.getMessage());
                }
            }
        }
        if (Files.isRegularFile(own) && works(own)) {
            // a download from before the pin keeps working this time; the tested build comes in the
            // background and takes over at the next start
            if (WINDOWS && !PIN.equals(version(own))) upgradeLater();
            return own;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) {
                try {
                    String dir = d.trim().replace("\"", ""); // Windows PATH entries may be quoted
                    if (dir.isEmpty()) continue;
                    Path c = Path.of(dir).resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
                    if (Files.isRegularFile(c) && works(c)) return c;
                } catch (Exception ignored) {
                    // malformed PATH entry
                }
            }
        }
        return null;
    }

    /** "n9.0.2-17-g2a571b6068" for "ffmpeg version n9.0.2-17-g2a571b6068-20260930 Copyright…" (null if unreadable). */
    static String version(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-version").redirectErrorStream(true).start();
            String first;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                first = r.readLine();
                while (r.readLine() != null) {
                    // read to the end so FFmpeg can exit
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return buildOf(first);
        } catch (Exception e) {
            return null;
        }
    }

    /** The build name in FFmpeg's first -version line, without the date BtbN appends (-20260930). */
    static String buildOf(String versionLine) {
        if (versionLine == null || !versionLine.startsWith("ffmpeg version ")) return null;
        String v = versionLine.substring("ffmpeg version ".length()).split("\\s+", 2)[0];
        int date = v.lastIndexOf('-');
        return date > 0 && v.substring(date + 1).matches("\\d{8}") ? v.substring(0, date) : v;
    }

    private static void upgradeLater() {
        Thread t = new Thread(() -> {
            try {
                download(dir().resolve("ffmpeg-next.exe"));
                WindowsCraftClient.LOGGER.info("Tested FFmpeg {} downloaded; it takes over at the next start", PIN);
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.info("Tested FFmpeg not downloaded this time ({}); the current one keeps working", e.toString());
            }
        }, "WindowsCraft FFmpeg update");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static boolean works(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads the tested BtbN Windows build (or, if it's gone, the newest of its release line),
     * checksum-verified, to {@code exe}; only ffmpeg.exe is kept. A first install shows its
     * progress; the background upgrade (to ffmpeg-next.exe) doesn't.
     */
    private static synchronized Path download(Path exe) throws Exception {
        boolean firstInstall = exe.equals(dir().resolve("ffmpeg.exe"));
        if (firstInstall) {
            state = State.DOWNLOADING;
            status = "Downloading FFmpeg (one time)…";
        }
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        String expected = PIN_SHA256;
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(PIN_ZIP)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            WindowsCraftClient.LOGGER.warn("Tested FFmpeg {} isn't available any more (HTTP {}); taking the newest of its release line", PIN, resp.statusCode());
            String sums = http.send(HttpRequest.newBuilder(URI.create(WIN_SUMS)).build(), HttpResponse.BodyHandlers.ofString()).body();
            String name = WIN_ZIP.substring(WIN_ZIP.lastIndexOf('/') + 1);
            expected = null;
            for (String line : sums.split("\n")) {
                if (line.trim().endsWith(name)) expected = line.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            }
            if (expected == null) throw new IOException("FFmpeg checksum list didn't include the Windows build");
            resp = http.send(HttpRequest.newBuilder(URI.create(WIN_ZIP)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("FFmpeg download failed (HTTP " + resp.statusCode() + ")");
            }
        }
        Files.createDirectories(dir());
        Path zip = exe.resolveSibling(exe.getFileName() + ".zip");
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                if (total > 0 && firstInstall) {
                    progress = done / (double) total;
                    status = String.format(Locale.ROOT, "Downloading FFmpeg (one time) %d%% of %d MB", (int) (progress * 100), total >> 20);
                }
            }
        }
        String got = HexFormat.of().formatHex(sha.digest());
        if (!got.equals(expected)) {
            Files.deleteIfExists(zip);
            throw new IOException("FFmpeg download was corrupted (checksum mismatch)");
        }
        if (firstInstall) status = "Unpacking FFmpeg…";
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith("/bin/ffmpeg.exe")) {
                    Path tmp = exe.resolveSibling(exe.getFileName() + ".tmp");
                    Files.copy(zin, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(zip);
        }
        if (!Files.isRegularFile(exe) || !works(exe)) throw new IOException("FFmpeg download didn't contain a working ffmpeg.exe");
        return exe;
    }

    private static List<Encoder> candidates() {
        List<Encoder> c = new ArrayList<>();
        c.add(new Encoder("h264_nvenc", "NVIDIA NVENC", List.of("-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0", "-rc", "cbr")));
        c.add(new Encoder("h264_amf", "AMD AMF", List.of("-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr")));
        c.add(new Encoder("h264_qsv", "Intel Quick Sync", List.of("-c:v", "h264_qsv", "-preset", "veryfast", "-look_ahead", "0", "-async_depth", "1")));
        c.add(new Encoder("libx264", "software (x264)", List.of("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency")));
        return c;
    }

    /** The always-available software encoder (x264). */
    public static Encoder software() {
        List<Encoder> c = candidates();
        return c.get(c.size() - 1);
    }

    /** Tries each encoder on a tiny test clip; the first that really works wins (drivers lie in -encoders). */
    private static Encoder pickEncoder(Path exe) {
        String forced = System.getProperty("windowscraft.encoder");
        for (Encoder e : candidates()) {
            if (forced != null && !forced.equals(e.name)) continue;
            status = "Testing " + e.label + "…";
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "color=c=black:s=640x360:r=30", "-frames:v", "10"));
            cmd.addAll(e.args);
            cmd.addAll(List.of("-pix_fmt", "yuv420p", "-f", "null", "-"));
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); continue; } // hung driver: skip this encoder
                if (p.exitValue() == 0) return e;
            } catch (Exception ignored) {
            }
        }
        return candidates().get(candidates().size() - 1);
    }

    /** Largest picture we send: hardware H.264 encoders stop at 4096 wide, and 4K is plenty for a wall. */
    public static final int MAX_STREAM_W = 3840, MAX_STREAM_H = 2160;

    /** Bitrate that keeps text readable: ~0.1 bit per pixel per frame, 1.5–10 Mbit/s. */
    public static int bitrateKbps(int w, int h, int fps) {
        return (int) Math.max(1500, Math.min(10_000, (long) w * h * fps / 10_000));
    }

    private Ffmpeg() {}
}
