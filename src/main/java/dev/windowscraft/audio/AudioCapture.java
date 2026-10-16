package dev.windowscraft.audio;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.SourceInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Sources of sound for a share. All produce 48 kHz stereo S16LE and hand it to a sink (the
 * share's {@link AudioHub}, which fans it out to the stream and the in-game speaker).
 */
public abstract class AudioCapture implements AutoCloseable {
    public static final int RATE = 48_000, BYTES_PER_SECOND = RATE * 4;
    /** Receives captured PCM. */
    public interface Sink { void write(byte[] pcm, int off, int len); }

    protected final Sink sink;
    protected volatile String status = "";
    /** True once the app's own output is turned down and its sound plays from the wall instead. */
    protected volatile boolean inGame;

    protected AudioCapture(Sink sink) { this.sink = sink; }

    public String status() { return status; }
    public boolean inGame() { return inGame; }
    @Override public abstract void close();
    /** Completes once the capture has fully stopped (for the helper: it gave the app its volume back). */
    public java.util.concurrent.CompletableFuture<?> stopped() { return java.util.concurrent.CompletableFuture.completedFuture(null); }

    /** Picks the right capture for a source, or null for "no audio". */
    public static AudioCapture forSource(SourceInfo source, boolean hearInGame, Sink sink) {
        if (source.kind() == SourceInfo.Kind.TEST) return new TestTone(sink, hearInGame);
        if (!isWindows()) return null;
        if (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(source)) return new MonitorAudio(source, hearInGame, sink);
        try {
            if (source.kind() == SourceInfo.Kind.WINDOW && source.pid() > 0) {
                List<String> args = new java.util.ArrayList<>(List.of("--include-pid", Long.toString(source.pid()),
                        "--parent-pid", Long.toString(ProcessHandle.current().pid())));
                Path state = null;
                if (hearInGame) {
                    state = newDuckStateFile();
                    Files.createDirectories(state.getParent());
                    args.addAll(List.of("--duck", state.toString()));
                }
                return new WasapiHelper(args, "app audio", sink, state);
            }
            // whole screen: everything except Minecraft itself, so other people's streams don't echo back
            long me = ProcessHandle.current().pid();
            return new WasapiHelper(List.of("--exclude-pid", Long.toString(me), "--parent-pid", Long.toString(me)), "system audio", sink, null);
        } catch (Exception e) {
            WindowsCraftClient.LOGGER.warn("Audio capture unavailable", e);
            return null;
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // Each in-game capture notes the app volumes it turned down in its own file, so a crash can
    // never leave an app quiet: leftover files are replayed with "wc-audio --restore".
    private static final java.util.Set<Path> LIVE_STATE_FILES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean restoring;

    static Path stateDir() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("volumes");
    }

    static Path newDuckStateFile() {
        Path f = stateDir().resolve("ducked-" + java.util.UUID.randomUUID() + ".txt");
        LIVE_STATE_FILES.add(f);
        return f;
    }

    /**
     * Puts back app volumes that an earlier run left turned down (only possible if the game or the
     * helper died mid-share). Background thread; does nothing when there's nothing to restore.
     * Called at startup and then every minute, since an app can only be restored while it's open.
     */
    public static void restoreLeftovers() {
        if (!isWindows() || restoring || !Files.isDirectory(stateDir())) return;
        List<Path> files;
        try (var list = Files.list(stateDir())) {
            files = list.filter(f -> f.getFileName().toString().startsWith("ducked-") && f.toString().endsWith(".txt"))
                    .filter(f -> !LIVE_STATE_FILES.contains(f)).toList();
        } catch (IOException e) {
            return;
        }
        if (files.isEmpty()) return;
        restoring = true;
        Thread t = new Thread(() -> {
            try {
                for (Path f : files) {
                    Process p = new ProcessBuilder(WasapiHelper.extractHelper().toString(), "--restore", f.toString())
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
                }
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.warn("Couldn't restore app volumes", e);
            } finally {
                restoring = false;
            }
        }, "WindowsCraft restore volumes");
        t.setDaemon(true);
        t.start();
    }

    /** One short beep per second, aligned with the test pattern's flash, for checking A/V sync. */
    public static final class TestTone extends AudioCapture {
        private final Thread thread;
        private volatile boolean running = true;

        TestTone(Sink sink, boolean hearInGame) {
            super(sink);
            inGame = hearInGame;
            status = "test tone";
            thread = new Thread(this::run, "WindowsCraft test tone");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            long start = System.nanoTime();
            long written = 0; // frames
            byte[] chunk = new byte[480 * 4];
            while (running) {
                long due = (System.nanoTime() - start) * RATE / 1_000_000_000L;
                while (written + 480 <= due) {
                    for (int i = 0; i < 480; i++) {
                        long frame = written + i;
                        long wallMs = System.currentTimeMillis() - (due - frame) * 1000 / RATE;
                        boolean beep = Math.floorMod(wallMs, 1000) < 100;
                        short v = beep ? (short) (Math.sin(2 * Math.PI * 1000.0 * frame / RATE) * 12000) : 0;
                        chunk[i * 4] = (byte) v; chunk[i * 4 + 1] = (byte) (v >> 8);
                        chunk[i * 4 + 2] = (byte) v; chunk[i * 4 + 3] = (byte) (v >> 8);
                    }
                    sink.write(chunk, 0, chunk.length);
                    written += 480;
                }
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        }

        @Override public void close() { running = false; thread.interrupt(); }
    }

    /** Runs the bundled wc-audio.exe helper (WASAPI application loopback). */
    public static final class WasapiHelper extends AudioCapture {
        private final Process process;

        private final Path stateFile;
        private volatile boolean closed;

        WasapiHelper(List<String> args, String what, Sink sink, Path stateFile) throws IOException {
            super(sink);
            this.stateFile = stateFile;
            Path exe = extractHelper();
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
            cmd.add(exe.toString());
            cmd.addAll(args);
            process = new ProcessBuilder(cmd).start();
            status = "starting " + what;
            Thread out = new Thread(() -> pump(process.getInputStream()), "WindowsCraft audio capture");
            out.setDaemon(true);
            out.setPriority(Thread.MAX_PRIORITY);
            out.start();
            Thread err = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        WindowsCraftClient.LOGGER.info("[wc-audio] {}", line);
                        if (line.startsWith("mode=")) {
                            String mode = line.substring(5);
                            status = switch (mode) {
                                case "include" -> "app audio";
                                case "exclude" -> "system audio (without Minecraft)";
                                case "system-fallback" -> "system audio (incl. Minecraft — older Windows)";
                                default -> mode;
                            };
                        } else if (line.equals("duck=on")) {
                            inGame = true;
                        } else if (line.startsWith("duck=failed")) {
                            inGame = false;
                            status = status + " · plays from the app (" + line.substring(line.indexOf(':') + 1).trim() + ")";
                        } else if (line.startsWith("info=sound stays in the app")) {
                            inGame = false;
                            status = "app audio · plays from the app";
                        } else if (line.startsWith("error=") && !status.startsWith("system")) {
                            status = "audio unavailable: " + line.substring(6);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "WindowsCraft audio capture log");
            err.setDaemon(true);
            err.start();
        }

        private void pump(InputStream in) {
            byte[] buf = new byte[4096];
            FrameAligner frames = new FrameAligner(); // reads end anywhere, not on whole samples
            try {
                int n;
                while ((n = in.read(buf)) > 0 && !closed) frames.feed(buf, 0, n, sink::write);
            } catch (IOException ignored) {
            }
        }

        @Override
        public java.util.concurrent.CompletableFuture<?> stopped() { return process.onExit(); }

        /** The helper as checked this run (each new capture used to read it from the jar and the disk again). */
        private static Path extracted;

        static synchronized Path extractHelper() throws IOException {
            if (extracted != null && Files.isRegularFile(extracted)) return extracted;
            Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("bin");
            Path exe = dir.resolve("wc-audio.exe");
            try (InputStream in = AudioCapture.class.getResourceAsStream("/windowscraft/natives/wc-audio.exe")) {
                if (in == null) throw new IOException("wc-audio.exe missing from the mod jar");
                byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(exe) || Files.size(exe) != bytes.length || !java.util.Arrays.equals(Files.readAllBytes(exe), bytes)) {
                    Files.createDirectories(dir);
                    Path tmp = dir.resolve("wc-audio.exe.tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            extracted = exe;
            return exe;
        }

        @Override
        public void close() {
            // Ask it to stop (closing its stdin) so it can give the app its volume back first;
            // only force it if it doesn't exit promptly.
            closed = true;
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            Thread t = new Thread(() -> {
                try {
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroy();
                } catch (InterruptedException e) {
                    process.destroy();
                } finally {
                    // a clean exit deletes the file; if it's still there, restoreLeftovers() fixes it
                    if (stateFile != null) LIVE_STATE_FILES.remove(stateFile);
                }
            }, "WindowsCraft audio stop");
            t.setDaemon(true);
            t.start();
        }
    }
}
