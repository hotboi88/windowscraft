package dev.windowscraft.apps;

import dev.windowscraft.WindowsCraftClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Talks to wc-input.exe, which posts mouse and keyboard input to an app window (see its source
 * for the commands). Started on first use. Commands are queued and written by a background
 * thread, so the game never waits on the helper (a busy app can make it slow for a moment);
 * mouse moves that haven't gone out yet are merged into the newest one, so the pointer never
 * lags behind. Off Windows (tests) the lines go to {@link #testSink} straight away.
 */
public final class InputBridge {
    /** Tests: receives every command instead of a helper process. */
    public static volatile Consumer<String> testSink;
    private static Process process;
    private static OutputStream out;
    private static boolean failed;
    private static final java.util.ArrayDeque<String> QUEUE = new java.util.ArrayDeque<>();
    private static Thread writer;

    public static void send(String line) {
        Consumer<String> sink = testSink;
        if (sink != null) {
            sink.accept(line);
            return;
        }
        if (!windows() || failed) return;
        synchronized (QUEUE) {
            // a move still waiting to go out is replaced by this newer one
            // (the same person's: "slot 3 move …" only replaces "slot 3 move …")
            int mv = line.indexOf("move ");
            if (mv >= 0 && !QUEUE.isEmpty() && QUEUE.peekLast().startsWith(line.substring(0, mv + 5))
                    && (mv == 0 || line.startsWith("slot "))) QUEUE.pollLast();
            QUEUE.addLast(line);
            if (QUEUE.size() > 2000) QUEUE.pollFirst(); // the helper is stuck: don't pile up forever
            QUEUE.notifyAll();
            if (writer == null) {
                writer = new Thread(InputBridge::writeLoop, "WindowsCraft input");
                writer.setDaemon(true);
                writer.start();
            }
        }
    }

    private static void writeLoop() {
        StringBuilder batch = new StringBuilder();
        while (true) {
            batch.setLength(0);
            synchronized (QUEUE) {
                while (QUEUE.isEmpty()) {
                    try { QUEUE.wait(); } catch (InterruptedException e) { return; }
                }
                for (String l; (l = QUEUE.pollFirst()) != null; ) batch.append(l).append('\n');
            }
            try {
                if (process == null || !process.isAlive()) start();
                out.write(batch.toString().getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                WindowsCraftClient.LOGGER.warn("Couldn't send input to the app ({})", e.getMessage());
                process = null;
                if (failed) return;
            }
        }
    }

    private static void start() throws IOException {
        try {
            Path exe = extract();
            process = new ProcessBuilder(exe.toString(), "--parent-pid", Long.toString(ProcessHandle.current().pid()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            out = process.getOutputStream();
            InputStream err = process.getErrorStream();
            Thread t = new Thread(() -> {
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(err, StandardCharsets.UTF_8))) {
                    for (String l; (l = r.readLine()) != null; ) WindowsCraftClient.LOGGER.info("[wc-input] {}", l);
                } catch (IOException ignored) {
                }
            }, "WindowsCraft input log");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path extract() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("bin");
        Path exe = dir.resolve("wc-input.exe");
        try (InputStream in = InputBridge.class.getResourceAsStream("/windowscraft/natives/wc-input.exe")) {
            if (in == null) throw new IOException("wc-input.exe missing from the mod jar");
            byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(exe) || Files.size(exe) != bytes.length || !java.util.Arrays.equals(Files.readAllBytes(exe), bytes)) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve("wc-input.exe.tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return exe;
    }

    private InputBridge() {}
}
