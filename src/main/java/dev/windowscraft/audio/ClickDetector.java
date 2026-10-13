package dev.windowscraft.audio;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.audio.dsp.Biquad;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Diagnostics: listens to a stream of S16LE stereo for sudden bursts of high-frequency energy —
 * what clicks and pops look like — counts them for the log, and saves a few seconds of sound
 * around the first few as WAV files (windowscraft/diagnostics), so a crackle report comes with
 * the actual sound and shows <em>where</em> in the chain the pops appear (as captured from the
 * app, or after playback processing). Cheap: three filters per sample.
 */
public final class ClickDetector {
    private static final int RATE = AudioCapture.RATE, WINDOW = RATE / 200; // 5 ms
    private static final int KEEP_BYTES = RATE * 4 * 3, AFTER_BYTES = RATE * 4; // 3 s before, 1 s after
    private static final int MAX_SAVES_PER_STAGE = 3;
    private static final java.util.Map<String, Integer> SAVED = new java.util.concurrent.ConcurrentHashMap<>();

    private final String stage;
    private final Biquad h1 = new Biquad(), h2 = new Biquad();
    private double acc, hfAvg = -1;
    private int count;
    private long clicks, reportedClicks;
    private long lastReportNanos = System.nanoTime();
    private final byte[] ring = new byte[KEEP_BYTES];
    private int ringPos;
    private long total;
    private boolean ringFull;
    private int saveIn = -1; // bytes still to collect before saving; -1 = not saving

    public ClickDetector(String stage) {
        this.stage = stage;
        h1.configure(Biquad.Type.HIGH_PASS, RATE, 7000, 0.54f);
        h2.configure(Biquad.Type.HIGH_PASS, RATE, 7000, 1.31f);
    }

    public void feed(byte[] pcm, int off, int len) {
        for (int i = off; i + 3 < off + len; i += 4) {
            float l = ((short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF))) / 32768f;
            float h = h2.process(h1.process(l));
            acc += h * h;
            if (++count == WINDOW) {
                double rms = Math.sqrt(acc / WINDOW);
                if (hfAvg < 0) hfAvg = rms;
                // a burst: far above this stream's usual treble, and loud enough to hear
                if (rms > 0.02 && rms > hfAvg * 25) onClick();
                hfAvg = hfAvg * 0.95 + rms * 0.05;
                acc = 0;
                count = 0;
            }
        }
        remember(pcm, off, len);
        report();
    }

    private void onClick() {
        clicks++;
        if (saveIn < 0 && SAVED.getOrDefault(stage, 0) < MAX_SAVES_PER_STAGE) saveIn = AFTER_BYTES;
    }

    private void remember(byte[] pcm, int off, int len) {
        int n = Math.min(len, ring.length);
        int from = off + len - n;
        int first = Math.min(n, ring.length - ringPos);
        System.arraycopy(pcm, from, ring, ringPos, first);
        System.arraycopy(pcm, from + first, ring, 0, n - first);
        ringPos = (ringPos + n) % ring.length;
        total += n;
        if (total >= ring.length) ringFull = true;
        if (saveIn >= 0) {
            saveIn -= len;
            if (saveIn <= 0) {
                saveIn = -1;
                save();
            }
        }
    }

    private void save() {
        int saved = SAVED.merge(stage, 1, Integer::sum);
        if (saved > MAX_SAVES_PER_STAGE) return;
        int size = ringFull ? ring.length : ringPos;
        byte[] pcm = new byte[size];
        if (ringFull) {
            System.arraycopy(ring, ringPos, pcm, 0, ring.length - ringPos);
            System.arraycopy(ring, 0, pcm, ring.length - ringPos, ringPos);
        } else {
            System.arraycopy(ring, 0, pcm, 0, ringPos);
        }
        Thread t = new Thread(() -> {
            try {
                Path dir = dir();
                Files.createDirectories(dir);
                String name = stage + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + ".wav";
                Files.write(dir.resolve(name), wav(pcm));
                WindowsCraftClient.LOGGER.info("Saved a sound sample with a click in it: windowscraft/diagnostics/{}", name);
            } catch (IOException e) {
                WindowsCraftClient.LOGGER.debug("couldn't save sound sample", e);
            }
        }, "WindowsCraft sound sample");
        t.setDaemon(true);
        t.start();
    }

    private void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < 10_000_000_000L) return;
        lastReportNanos = now;
        if (clicks != reportedClicks) {
            WindowsCraftClient.LOGGER.info("Possible clicks in screen sound ({}): {} in the last 10 s", stage, clicks - reportedClicks);
            reportedClicks = clicks;
        }
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("diagnostics");
    }

    static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
