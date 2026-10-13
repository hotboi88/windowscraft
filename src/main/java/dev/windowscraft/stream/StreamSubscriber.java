package dev.windowscraft.stream;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.audio.SpatialAudioPlayer;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FramePool;
import dev.windowscraft.capture.FrameSource;
import dev.windowscraft.capture.TestPatternProducer;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.relay.RelayServer;
import dev.windowscraft.relay.TsSplitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches one remote screen: relay → FFmpeg (hardware decode where available) → BGRA frames for
 * the wall texture + PCM for {@link SpatialAudioPlayer}. Reconnects on its own if anything drops.
 *
 * <p>Stays live: stream data waits in our own small queue in front of the decoder, and if that
 * queue ever holds more than ~1 s (this PC is busy and decodes slower than real time), it is cut
 * back to the newest keyframe. The decoder keeps running, so catching up costs a skipped moment,
 * not a reconnect. Raw frames come back over a loopback socket, not a process pipe (Windows pipes
 * are far too slow for 1080p60 BGRA).
 */
public final class StreamSubscriber implements FrameSource {
    private static final RelayServer.Chunk END = new RelayServer.Chunk(new byte[0], false);

    private final UUID displayId;
    private final String streamId;
    private final int width, height, fps;
    private final FramePool pool = new FramePool();
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final SpatialAudioPlayer audio;
    private final Thread supervisor;
    private volatile boolean running = true;
    private volatile String status = "Connecting…";
    private volatile long framesDecoded, catchUps, lastFrameNanos, lastDataNanos;

    // resources of the running pipeline, so stop() can tear them down immediately
    private final CopyOnWriteArrayList<AutoCloseable> live = new CopyOnWriteArrayList<>();
    private volatile Process ffmpeg;

    // test-pattern measurements (automated latency / A/V sync checks)
    private volatile long lastFlashNanos;
    private volatile double latencyMs = -1;
    private volatile long latencyMin = Long.MAX_VALUE, latencyMax;
    private boolean flashing;

    public StreamSubscriber(UUID displayId, String streamId, int width, int height, int fps) {
        this.displayId = displayId;
        this.streamId = streamId;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.audio = new SpatialAudioPlayer(displayId, "played-remote");
        supervisor = new Thread(this::supervise, "WindowsCraft viewer " + streamId.substring(0, 8));
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /**
     * Light playback: this PC fell behind playing the stream, so only every other picture is
     * converted and handed to the game (at most 30 a second) — the costly part after decoding.
     */
    private volatile boolean light;
    private volatile UUID reportAs;
    private volatile long lastTroubleNanos;

    /** The screen to name when telling its owner we can't keep up. */
    public void reportAs(UUID displayId) { reportAs = displayId; }
    public boolean light() { return light; }

    /** We had to skip ahead: play lighter, and let the owner know (their stream may send less). */
    private void fellBehind() {
        long now = System.nanoTime();
        if (reportAs != null && now - lastTroubleNanos > 10_000_000_000L) {
            lastTroubleNanos = now;
            ClientNet.trouble(reportAs, dev.windowscraft.net.Payloads.TROUBLE_DECODE);
        }
        if (!light && fps > 30) {
            light = true;
            quickRestart = true;
            WindowsCraftClient.LOGGER.info("Viewer for {} can't keep up at {} fps; playing at 30 from now on", streamId.substring(0, 8), fps);
            Process p = ffmpeg;
            if (p != null) p.destroyForcibly(); // rebuilt right away with the lighter settings
        }
    }

    private volatile boolean quickRestart;
    /** UDP didn't get through for this stream (a firewall): TCP from now on. */
    private volatile boolean udpOff;
    private volatile dev.windowscraft.relay.UdpReceiver udp;

    /** How the pictures travel: "UDP" (live-video style), "TCP", or "" before connecting. */
    public String transport() {
        dev.windowscraft.relay.UdpReceiver u = udp;
        return u != null ? String.format(Locale.ROOT, "UDP (round trip %.0f ms, %d lost bits recovered, %d skipped)", u.rttMs(), u.recovered, u.skipped) : "TCP";
    }

    /** Picture pieces given up on (they never arrived in time). */
    public long skippedPieces() { var u = udp; return u == null ? 0 : u.skipped; }
    public long recoveredPieces() { var u = udp; return u == null ? 0 : u.recovered; }
    private volatile boolean lossTroubleSent;
    private volatile long lastLossTroubleNanos;
    /** Tests: has this viewer asked the owner for a lighter stream because pieces got lost? */
    public boolean lossTroubleSent() { return lossTroubleSent; }

    /** Tests: behave as if this PC had just fallen behind. */
    public void testFallBehind() { fellBehind(); }

    public String status() { return status + (light ? " (light playback)" : ""); }
    public long framesDecoded() { return framesDecoded; }

    /** What a lost piece costs: false — a short glitch that heals by itself; true — the picture holds until the next keyframe. */
    public static volatile boolean FREEZE_ON_LOSS = Boolean.parseBoolean(System.getProperty("windowscraft.freezeOnLoss", "false"));
    private volatile boolean damaged;
    /** For tests and the report: pauses longer than a quarter second between pictures, and pictures shown with damage. */
    public volatile long stalls, stallMs, longestStallMs, damagedFrames;
    public void resetStallStats() { stalls = 0; stallMs = 0; longestStallMs = 0; damagedFrames = 0; }
    public long catchUps() { return catchUps; }
    public long lastFlashNanos() { return lastFlashNanos; }
    /** Capture-to-decode delay of the latest frame in ms (test pattern only), or -1. */
    public double latencyMs() { return latencyMs; }
    public long latencyMin() { return latencyMin; }
    public long latencyMax() { return latencyMax; }
    public SpatialAudioPlayer audio() { return audio; }

    @Override
    public Frame takeFrame() { return latest.getAndSet(null); }

    @Override
    public void recycle(Frame f) { pool.release(f); }

    public void stop() {
        running = false;
        supervisor.interrupt();
        teardown();
        audio.close();
    }

    private void teardown() {
        Process p = ffmpeg;
        if (p != null) p.destroyForcibly();
        for (AutoCloseable c : live) Proc.closeQuietly(c);
        live.clear();
    }

    private <T extends AutoCloseable> T track(T c) {
        live.add(c);
        if (!running) Proc.closeQuietly(c);
        return c;
    }

    private void supervise() {
        try {
            if (Ffmpeg.state() != Ffmpeg.State.READY) status = "Preparing FFmpeg…";
            Ffmpeg.ensure().join();
        } catch (Exception e) {
            status = "Can't play: " + Ffmpeg.status();
            return;
        }
        while (running) {
            try {
                runOnce();
            } catch (Exception e) {
                if (!running) return;
                status = "Reconnecting… (" + e.getMessage() + ")";
                WindowsCraftClient.LOGGER.info("Viewer for {} restarting: {}", streamId.substring(0, 8), e.toString());
            } finally {
                teardown();
            }
            try { Thread.sleep(quickRestart ? 200 : 1500); } catch (InterruptedException e) { return; }
            quickRestart = false;
        }
    }

    private void runOnce() throws Exception {
        status = "Connecting…";
        Socket relay;
        InputStream net;
        int udpPort = ClientNet.relayUdpPort();
        udp = null;
        if (udpPort > 0 && !udpOff && !"false".equals(System.getProperty("windowscraft.udp"))) {
            // live-video style over UDP: copes with a long, slightly lossy path (TCP backs up there)
            Proc.UdpRelay u = Proc.connectRelayUdp(ClientNet.relayHost(), ClientNet.relayPort(), ClientNet.token(), streamId);
            relay = track(u.control());
            dev.windowscraft.relay.UdpReceiver rx = track(new dev.windowscraft.relay.UdpReceiver(
                    new java.net.InetSocketAddress(ClientNet.relayHost(), u.udpPort()), u.session()));
            udp = rx;
            net = rx.stream();
            daemon("viewer control", () -> {
                try {
                    InputStream c = relay.getInputStream();
                    while (c.read() >= 0) { }
                } catch (IOException ignored) {
                }
                rx.close(); // the relay ended it
            });
            daemon("viewer UDP check", () -> {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                if (running && !rx.flowing()) {
                    udpOff = true; // nothing got through: a firewall on the way. TCP it is
                    WindowsCraftClient.LOGGER.info("Viewer for {}: nothing arrived over UDP; using TCP", streamId.substring(0, 8));
                    rx.close();
                    Proc.closeQuietly(relay);
                }
            });
        } else {
            relay = track(Proc.connectRelay(ClientNet.relayHost(), ClientNet.relayPort(), "SUB", ClientNet.token(), streamId));
            net = relay.getInputStream();
        }
        ServerSocket videoListen = track(Proc.loopbackListener());
        ServerSocket audioListen = track(Proc.loopbackListener());
        audioListen.setReceiveBufferSize(32 * 1024); // no room for a hidden audio backlog
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = new ArrayList<>(List.of(Ffmpeg.executable().toString(), "-hide_banner", "-loglevel", "warning", "-nostats",
                "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "500000"));
        if (windows) cmd.addAll(List.of("-hwaccel", "auto"));
        cmd.addAll(List.of("-f", "mpegts", "-i", "pipe:0",
                // frames out as soon as they're decoded: no frame-rate conversion, no duplicates
                "-map", "0:v:0", "-vf", (light ? "fps=30," : "") + "scale=" + width + ":" + height + ",format=bgra", "-fps_mode", "passthrough",
                "-f", "rawvideo", "tcp://127.0.0.1:" + videoListen.getLocalPort(),
                "-map", "0:a:0", "-ac", "2", "-ar", "48000", "-f", "s16le", "tcp://127.0.0.1:" + audioListen.getLocalPort()));
        Process ff = Proc.start(cmd, "ffmpeg-dec");
        ffmpeg = ff;
        OutputStream ffIn = ff.getOutputStream();

        // network → our queue (never blocks on the decoder) → decoder
        Object lock = new Object();
        ArrayDeque<RelayServer.Chunk> queue = new ArrayDeque<>();
        long[] queuedBytes = {0};
        double[] byteRate = {0}; // incoming bytes/s, smoothed
        TsSplitter splitter = new TsSplitter();
        dev.windowscraft.relay.UdpReceiver rxNow = udp;
        WindowsCraftClient.LOGGER.info("Viewer for {}: pictures over {}", streamId.substring(0, 8), rxNow != null ? "UDP" : "TCP");
        daemon("viewer net", () -> {
            byte[] buf = new byte[64 * 1024];
            long t0 = System.nanoTime(), windowBytes = 0;
            try {
                int n;
                while (running && (n = net.read(buf)) > 0) {
                    lastDataNanos = System.nanoTime();
                    List<RelayServer.Chunk> chunks = splitter.feed(buf, n);
                    synchronized (lock) {
                        for (RelayServer.Chunk c : chunks) { queue.add(c); queuedBytes[0] += c.data().length; }
                        lock.notifyAll();
                    }
                    windowBytes += n;
                    long now = System.nanoTime();
                    if (now - t0 > 1_000_000_000L) {
                        double r = windowBytes / ((now - t0) / 1e9);
                        synchronized (lock) { byteRate[0] = byteRate[0] == 0 ? r : byteRate[0] * 0.7 + r * 0.3; }
                        t0 = now;
                        windowBytes = 0;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                synchronized (lock) { queue.add(END); lock.notifyAll(); }
            }
        });
        // every 2 s, by the clock (not by how fast this PC decodes): was the connection lossy —
        // pieces given up on, or a lot of them needing a resend (more than 1 in 20)? Then the
        // stream should be lighter
        if (rxNow != null) daemon("viewer loss watch", () -> {
            long skippedAt = rxNow.skipped, recoveredAt = rxNow.recovered, receivedAt = rxNow.received;
            while (running && udp == rxNow) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime();
                long given = rxNow.skipped - skippedAt, rec = rxNow.recovered - recoveredAt, got = Math.max(1, rxNow.received - receivedAt);
                skippedAt = rxNow.skipped;
                recoveredAt = rxNow.recovered;
                receivedAt = rxNow.received;
                boolean lossy = given >= 1 || (rec >= 20 && rec * 20 > got);
                if (!lossy) continue;
                WindowsCraftClient.LOGGER.info("[loss] {}: given up {}, resent {} of {} (report as {})", streamId.substring(0, 8), given, rec, got, reportAs);
                if (reportAs != null && now - lastLossTroubleNanos > 4_000_000_000L) {
                    lastLossTroubleNanos = now;
                    WindowsCraftClient.LOGGER.info("Viewer for {}: pieces keep getting lost on the way ({} resent of {}); asking for a lighter stream",
                            streamId.substring(0, 8), rec, got);
                    ClientNet.trouble(reportAs, dev.windowscraft.net.Payloads.TROUBLE_NETWORK);
                    lossTroubleSent = true;
                }
            }
        });
        daemon("viewer decode feed", () -> {
            boolean waitForKey = false;
            // a piece of the picture never arrived: the pictures after it would be smeared and
            // streaky until the next keyframe — show the last good one until then instead (the
            // sound carries on), and if it keeps happening, ask the owner to send less
            boolean lostUntilKey = false;
            long seenSkipped = rxNow != null ? rxNow.skipped : 0;
            try {
                while (running) {
                    RelayServer.Chunk c;
                    synchronized (lock) {
                        // more than ~1 s waiting for the decoder: jump to the newest keyframe
                        if (byteRate[0] > 0 && queuedBytes[0] > Math.max(256 * 1024, byteRate[0])) {
                            waitForKey = !skipToNewestKeyframe(queue, queuedBytes);
                        }
                        while (queue.isEmpty() && running) lock.wait(1000);
                        c = queue.poll();
                        if (c == null) continue;
                        queuedBytes[0] -= c.data().length;
                    }
                    if (c == END) break;
                    if (waitForKey) {
                        if (!c.keyframe()) continue;
                        waitForKey = false;
                    }
                    if (rxNow != null && rxNow.skipped != seenSkipped) {
                        seenSkipped = rxNow.skipped;
                        lostUntilKey = true;
                    }
                    if (lostUntilKey && c.keyframe()) lostUntilKey = false;
                    damaged = lostUntilKey;
                    if (lostUntilKey && !FREEZE_ON_LOSS) {
                        // (play on through the damage: a glitch that heals by the next keyframe
                        // rather than a frozen picture until then)
                    } else if (lostUntilKey) {
                        byte[] rest = TsSplitter.without(c.data(), splitter.videoPid());
                        if (rest.length > 0) {
                            ffIn.write(rest);
                            ffIn.flush();
                        }
                        continue;
                    }
                    ffIn.write(c.data());
                    ffIn.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                Proc.closeQuietly(ffIn);
            }
        });
        // audio: FFmpeg connects once it has seen the stream
        daemon("viewer audio", () -> {
            try (Socket s = track(audioListen.accept())) {
                audio.play(s.getInputStream());
            } catch (IOException ignored) {
            }
        });
        // watchdog: no picture for 6 s (stalled decoder, half-dead connection) → rebuild. A window
        // that simply isn't changing sends no new pictures (GPU capture), but its sound still
        // flows — that's a live stream, not a stall.
        lastFrameNanos = System.nanoTime() + 6_000_000_000L; // grace period for the first keyframe
        lastDataNanos = System.nanoTime();
        daemon("viewer watchdog", () -> {
            while (running && ff.isAlive()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long now = System.nanoTime();
                if (now - lastFrameNanos > 6_000_000_000L && (now - lastDataNanos > 3_000_000_000L || framesDecoded() == 0)) {
                    WindowsCraftClient.LOGGER.info("Viewer for {} got no picture for 6 s; reconnecting", streamId.substring(0, 8));
                    ff.destroyForcibly();
                    Proc.closeQuietly(relay);
                    return;
                }
            }
        });
        Socket video = track(videoListen.accept());
        video.setReceiveBufferSize(4 << 20);
        readFrames(video.getInputStream());
        if (running) throw new IOException("no picture");
    }

    /** Drops queued data up to the newest keyframe; false if none is queued yet. Caller holds the lock. */
    private boolean skipToNewestKeyframe(ArrayDeque<RelayServer.Chunk> queue, long[] queuedBytes) {
        RelayServer.Chunk newestKey = null;
        for (RelayServer.Chunk c : queue) if (c.keyframe()) newestKey = c;
        long dropped = 0;
        if (newestKey == null) {
            boolean ended = queue.contains(END);
            for (RelayServer.Chunk c : queue) dropped += c.data().length;
            queue.clear();
            if (ended) queue.add(END);
        } else {
            while (queue.peek() != newestKey) dropped += queue.poll().data().length;
        }
        queuedBytes[0] -= dropped;
        catchUps++;
        fellBehind();
        WindowsCraftClient.LOGGER.info("Viewer for {} was behind; skipped {} KB to get back to live", streamId.substring(0, 8), dropped / 1024);
        return newestKey != null;
    }

    private void readFrames(InputStream in) throws IOException {
        int size = width * height * 4;
        byte[] buf = new byte[size];
        long windowStart = System.nanoTime();
        int windowFrames = 0;
        while (running) {
            int got = 0;
            while (got < size) {
                int n = in.read(buf, got, size - got);
                if (n < 0) return;
                got += n;
            }
            Frame f = pool.acquire(width, height);
            f.data.clear();
            f.data.put(buf, 0, size).flip();
            f.alphaValid = true;
            f.captureNanos = System.nanoTime();
            Frame old = latest.getAndSet(f);
            if (old != null) pool.release(old);
            framesDecoded++;
            windowFrames++;
            long gapNow = System.nanoTime();
            if (lastFrameNanos != 0) {
                long gap = (gapNow - lastFrameNanos) / 1_000_000L;
                if (gap > 250) {
                    stalls++;
                    stallMs += gap;
                    longestStallMs = Math.max(longestStallMs, gap);
                    var u = udp;
                    WindowsCraftClient.LOGGER.info("[pause] {}: {} ms without a new picture (lost for good so far {}, resent {}, catch-ups {}, data {} ms ago)",
                            streamId.substring(0, 8), gap, u == null ? 0 : u.skipped, u == null ? 0 : u.recovered, catchUps,
                            (gapNow - lastDataNanos) / 1_000_000L);
                }
            }
            if (damaged) damagedFrames++;
            lastFrameNanos = gapNow;
            measureTestPattern(buf);
            long now = System.nanoTime();
            if (now - windowStart > 1_000_000_000L) {
                status = String.format(Locale.ROOT, "Live · %.0f fps%s", windowFrames / ((now - windowStart) / 1e9), udp != null ? " · UDP" : "");
                windowStart = now;
                windowFrames = 0;
            }
        }
    }

    /** The test pattern carries a timing strip and a once-a-second flash (with a beep). */
    private void measureTestPattern(byte[] bgra) {
        long ts = TestPatternProducer.readTimestamp(bgra, width, height);
        if (ts > 0) {
            long lat = System.currentTimeMillis() - ts;
            latencyMs = lat;
            latencyMin = Math.min(latencyMin, lat);
            latencyMax = Math.max(latencyMax, lat);
        }
        int x = (int) (width * (1280 - 80) / 1280.0), y = (int) (height * 200 / 720.0);
        int o = (y * width + x) * 4;
        boolean white = (bgra[o] & 0xFF) > 220 && (bgra[o + 1] & 0xFF) > 220 && (bgra[o + 2] & 0xFF) > 220;
        if (white && !flashing) lastFlashNanos = System.nanoTime();
        flashing = white;
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, "WindowsCraft " + name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
