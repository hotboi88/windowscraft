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
    // what the decoder hands over this time: NV12 (coloured on the graphics card, see
    // render.Nv12Converter) or BGRA, and its size (NV12 needs even sides)
    private volatile boolean planes;
    private volatile int outW, outH;
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

    // ---- measurements (off by default) ----
    private static final dev.windowscraft.measure.Measure.Metric ARRIVAL_AGE = dev.windowscraft.measure.Measure.ms("view.arrival_age",
            "a picture arriving at this viewer: how long since it was captured (GPU-captured streams; this PC's clock vs the sharer's)");
    private static final dev.windowscraft.measure.Measure.Metric ARRIVAL_AGE_SYNCED = dev.windowscraft.measure.Measure.ms("view.arrival_age_synced",
            "the same, with this PC's clock lined up to the relay's (UDP) — exact when the server runs on the sharer's PC");
    private static final dev.windowscraft.measure.Measure.Metric ARRIVAL_GAP = dev.windowscraft.measure.Measure.ms("view.arrival_interval",
            "one picture's arrival to the next (pacing after the network)");
    private static final dev.windowscraft.measure.Measure.Metric QUEUE_WAIT = dev.windowscraft.measure.Measure.ms("view.queue_wait",
            "a piece of the stream waiting in this viewer's queue before the decoder");
    private static final dev.windowscraft.measure.Measure.Metric DECODE = dev.windowscraft.measure.Measure.ms("view.decode",
            "arrived → decoded picture back from FFmpeg (incl. scaling and conversion to BGRA; matched in order)");
    private static final dev.windowscraft.measure.Measure.Metric FRAME_GAP = dev.windowscraft.measure.Measure.ms("view.decoded_interval",
            "one decoded picture to the next");
    private static final dev.windowscraft.measure.Measure.Metric E2E = dev.windowscraft.measure.Measure.ms("view.e2e_testpattern",
            "test pattern only: captured on the sharer's PC → decoded here (from the timing strip in the picture)");
    private static final dev.windowscraft.measure.Measure.Counter DECODED = dev.windowscraft.measure.Measure.counter("view.frames_decoded", "pictures decoded");
    private static final dev.windowscraft.measure.Measure.Counter NOT_SHOWN = dev.windowscraft.measure.Measure.counter("view.frames_not_shown",
            "pictures decoded but replaced by a newer one before the game took them");
    private static final dev.windowscraft.measure.Measure.Counter CATCHUPS = dev.windowscraft.measure.Measure.counter("view.catchups", "fell behind and skipped to the newest keyframe");
    private static final dev.windowscraft.measure.Measure.Counter STALLS = dev.windowscraft.measure.Measure.counter("view.stalls", "over 250 ms without a new picture");
    private static final dev.windowscraft.measure.Measure.Counter DAMAGED = dev.windowscraft.measure.Measure.counter("view.frames_damaged", "pictures shown after a lost piece (until the next keyframe)");
    private static final dev.windowscraft.measure.Measure.Counter BYTES = dev.windowscraft.measure.Measure.counter("view.bytes", "bytes received for streams");
    private static final dev.windowscraft.measure.Measure.Counter DECODE_RESYNC = dev.windowscraft.measure.Measure.counter("view.decode_unmatched",
            "decode timing lost track (a picture dropped inside FFmpeg) and started over");
    /** Arrival times of pictures not yet decoded, oldest first (measuring only). */
    private final ArrayDeque<Long> arrivals = new ArrayDeque<>();
    private long lastArrival, lastDecoded, connectStarted;
    private volatile boolean matchDecode;
    /** When this connection's first picture came out of the decoder (0: not yet). */
    private volatile long firstPictureNanos;
    /** Catching up this soon after the first picture is the decoder starting, not this PC being too slow. */
    private static final long SETTLE_NANOS = 5_000_000_000L;
    private static final dev.windowscraft.measure.Measure.Metric FIRST_PICTURE = dev.windowscraft.measure.Measure.ms("view.first_picture",
            "starting to watch (or reconnecting) → the first picture decoded (waits for the next keyframe)");

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
        if (!light && fps > 30 && !Boolean.getBoolean("windowscraft.exp.noLight")) { // (Step 0 experiment: dev only)
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
    /** The size this viewer decodes to (fixed when it starts). */
    public int decodeWidth() { return width; }
    public int decodeHeight() { return height; }
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
    public Frame takeFrame() {
        lastTakenNanos = System.nanoTime();
        return latest.getAndSet(null);
    }

    // ---- a screen nobody looks at: only its sound is decoded ----
    /** When the game last asked for a picture (it only asks while one of the walls is drawn). */
    private volatile long lastTakenNanos = System.nanoTime();

    /** Pictures through FFmpeg without its picture-counted waits (see the decoder's command). */
    public static boolean promptDecode() {
        return !"false".equals(System.getProperty("windowscraft.promptDecode"));
    }
    /** This long without being asked: the picture side pauses (the sound goes on, from the wall). */
    private static final long HIDDEN_NANOS = 2_000_000_000L;
    private static final dev.windowscraft.measure.Measure.Counter PAUSED_BYTES = dev.windowscraft.measure.Measure.counter("view.hidden_video_skipped",
            "bytes of picture not decoded because nobody was looking at the screen");

    @Override
    public void recycle(Frame f) { pool.release(f); }

    public void stop() {
        running = false;
        supervisor.interrupt();
        teardown();
        audio.close();
    }

    private void teardown() {
        inputQueue = null;
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
        firstPictureNanos = 0;
        connectStarted = System.nanoTime();
        Socket relay;
        InputStream net;
        int udpPort = ClientNet.relayUdpPort();
        udp = null;
        if (udpPort > 0 && !udpOff && !"false".equals(System.getProperty("windowscraft.udp"))) {
            // live-video style over UDP: copes with a long, slightly lossy path (TCP backs up there)
            Proc.UdpRelay u = Proc.connectRelayUdp(ClientNet.relayHost(), ClientNet.relayPort(), ClientNet.token(), streamId);
            relay = track(u.control());
            if (u.takesInput() && !"false".equals(System.getProperty("windowscraft.relayInput"))) startInput(relay);
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
        // Each picture to the decoder as soon as it's in. FFmpeg's H.264 splitter would hold every
        // picture until the next one starts (the stream already comes one whole picture per
        // packet), and with the sender's packet lengths (StreamPublisher) the TS reader doesn't
        // wait for the next one either. Those waits are counted in pictures — about three in all:
        // 64 ms at 60 pictures/s, 114 ms at 30, 315 ms at 10 — and pictures only come when the
        // screen changes. Each wait removed is one picture less: 113 → 80 → 46 ms at 30/s outside
        // the game, decoded pictures bit-identical. The last one was FFmpeg's raw-picture writer:
        // it runs on helper threads and hands picture k out when picture k+1 is handed in
        // (FFmpeg's own latency report: 150 of 155 ms in "encode" with pictures 150 ms apart);
        // one thread hands each out as it comes — now none is held at all.
        // windowscraft.promptDecode=false: the old way (on this PC as a viewer and as a sharer).
        boolean prompt = promptDecode();
        // Pictures as the decoder makes them (NV12, 1.5 bytes a pixel), coloured on the graphics
        // card — not converted to BGRA here (4 bytes a pixel through the socket, Java and the
        // upload; 1–1.5 cores at 1080p60 with the conversion). BGRA while that isn't set up yet, or
        // if it doesn't work on this PC.
        boolean nv12 = dev.windowscraft.render.Nv12Converter.ready();
        int ow = nv12 ? width & ~1 : width, oh = nv12 ? height & ~1 : height;
        planes = nv12;
        outW = ow;
        outH = oh;
        List<String> cmd = new ArrayList<>(List.of(Ffmpeg.executable().toString(), "-hide_banner", "-loglevel", "warning", "-nostats",
                "-fflags", prompt ? "nobuffer+noparse+nofillin" : "nobuffer", "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "500000"));
        // (Step 0 experiment, dev only: windowscraft.exp.hwaccel = none / auto / d3d11va / cuda …)
        String hw = System.getProperty("windowscraft.exp.hwaccel", "auto");
        if (windows && !hw.equals("none")) cmd.addAll(List.of("-hwaccel", hw));
        cmd.addAll(List.of("-f", "mpegts", "-i", "pipe:0",
                // frames out as soon as they're decoded: no frame-rate conversion, no duplicates
                "-map", "0:v:0", "-vf", (light ? "fps=30," : "") + "scale=" + ow + ":" + oh + ",format=" + (nv12 ? "nv12" : "bgra"), "-fps_mode", "passthrough", "-enc_time_base:v", "demux",
                "-threads:v", prompt ? "1" : "0",
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
        AvSync sync = new AvSync(); // (lip sync: see AvSync)
        dev.windowscraft.relay.PtsWatch ptsWatch = new dev.windowscraft.relay.PtsWatch();
        dev.windowscraft.relay.UdpReceiver rxNow = udp;
        synchronized (arrivals) { arrivals.clear(); }
        // arrivals and decoded pictures are only matched if measuring was on from the start
        // (started mid-way, pictures already in the decoder would shift every match)
        matchDecode = dev.windowscraft.measure.Measure.ON;
        if (dev.windowscraft.measure.Measure.ON) dev.windowscraft.measure.Measure.note("view " + streamId.substring(0, 8),
                width + "×" + height + "@" + fps + " via " + (rxNow != null ? "UDP" : "TCP") + (light ? ", light playback" : "")
                        + (windows ? ", hardware decode (auto)" : ""));
        java.util.function.LongConsumer onPts = p -> {
            long nowNs = System.nanoTime();
            long nowMs = System.currentTimeMillis();
            long age = dev.windowscraft.relay.PtsWatch.ageNanos(p, nowMs);
            if (age != Long.MIN_VALUE) {
                ARRIVAL_AGE.record(age);
                long off = rxNow != null ? rxNow.clockOffsetMs() : Long.MIN_VALUE;
                if (off != Long.MIN_VALUE) {
                    long synced = dev.windowscraft.relay.PtsWatch.ageNanos(p, nowMs - off);
                    if (synced != Long.MIN_VALUE) ARRIVAL_AGE_SYNCED.record(synced);
                }
            }
            if (lastArrival != 0) ARRIVAL_GAP.record(nowNs - lastArrival);
            lastArrival = nowNs;
            synchronized (arrivals) {
                if (arrivals.size() > 240) { arrivals.clear(); DECODE_RESYNC.inc(); }
                arrivals.add(nowNs);
            }
        };
        WindowsCraftClient.LOGGER.info("Viewer for {}: pictures over {}", streamId.substring(0, 8), rxNow != null ? "UDP" : "TCP");
        daemon("viewer net", () -> {
            byte[] buf = new byte[64 * 1024];
            long t0 = System.nanoTime(), windowBytes = 0;
            try {
                int n;
                while (running && (n = net.read(buf)) > 0) {
                    lastDataNanos = System.nanoTime();
                    if (dev.windowscraft.measure.Measure.ON) {
                        BYTES.add(n);
                        ptsWatch.feed(buf, 0, n, onPts);
                    }
                    List<RelayServer.Chunk> chunks = splitter.feed(buf, n);
                    noteArrivals(chunks, splitter.videoPid(), splitter.audioPid(), sync);
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
        // a big picture (no length in its packet) handed on as soon as it's complete, not when the
        // next one starts (relay.PictureEnds; windowscraft.pictureEnds=false: not)
        dev.windowscraft.relay.PictureEnds ends = prompt && !"false".equals(System.getProperty("windowscraft.pictureEnds"))
                ? new dev.windowscraft.relay.PictureEnds() : null;
        // lip sync: while pictures are held back, they wait here (sound goes on at once)
        ArrayDeque<Held> held = new ArrayDeque<>();
        daemon("viewer decode feed", () -> {
            boolean waitForKey = false;
            long nextSyncStep = 0;
            long holdNanos = 0;
            boolean videoPaused = false; // (nobody looks at it: sound only, the picture resumes at a keyframe)
            // a piece of the picture never arrived: the pictures after it would be smeared and
            // streaky until the next keyframe — show the last good one until then instead (the
            // sound carries on), and if it keeps happening, ask the owner to send less
            boolean lostUntilKey = false;
            long seenSkipped = rxNow != null ? rxNow.skipped : 0;
            try {
                while (running) {
                    long now = System.nanoTime();
                    if (now >= nextSyncStep) { // (4 times a second: is it wanted, and how much)
                        nextSyncStep = now + 250_000_000L;
                        boolean wanted = audio.audibleLately() && !streamId.equals(dev.windowscraft.client.ScreenControl.workingStream());
                        double hold = sync.step(wanted, audio.soundQueuedMs(), now);
                        holdNanos = (long) (hold * 1e6);
                        lipHoldMs = hold;
                        if (dev.windowscraft.measure.Measure.ON) LIP_HOLD.record(holdNanos);
                    }
                    // held pictures whose time has come
                    boolean wrote = false;
                    while (!held.isEmpty() && held.peek().arrived() + holdNanos <= now) {
                        byte[] v = held.poll().data();
                        ffIn.write(ends != null ? ends.process(v, splitter.videoPid()) : v);
                        wrote = true;
                    }
                    if (wrote) ffIn.flush();
                    RelayServer.Chunk c;
                    synchronized (lock) {
                        // more than ~1 s waiting for the decoder: jump to the newest keyframe
                        if (byteRate[0] > 0 && queuedBytes[0] > Math.max(256 * 1024, byteRate[0])) {
                            waitForKey = !skipToNewestKeyframe(queue, queuedBytes);
                        }
                        if (queue.isEmpty() && running) {
                            // (wake for the next held picture, or the next step of lip sync)
                            long until = Math.min(nextSyncStep, held.isEmpty() ? Long.MAX_VALUE : held.peek().arrived() + holdNanos);
                            lock.wait(Math.max(1, Math.min(1000, (until - System.nanoTime()) / 1_000_000L)));
                        }
                        c = queue.poll();
                        if (c == null) continue;
                        queuedBytes[0] -= c.data().length;
                    }
                    if (c == END) break;
                    QUEUE_WAIT.since(c.madeNanos());
                    // nobody has looked at any of its walls for a while (and it has shown a picture):
                    // decode its sound only — decoding and converting pictures nobody sees cost a
                    // viewer 1–1.5 cores at 1080p60. Seen again: the picture comes back at the next
                    // keyframe (at most a second; until then the wall keeps its last picture).
                    boolean hidden = framesDecoded > 0 && System.nanoTime() - lastTakenNanos > HIDDEN_NANOS && splitter.videoPid() >= 0;
                    if (hidden || (videoPaused && !c.keyframe())) {
                        held.clear(); // (pictures nobody sees: not decoded at all)
                        if (!videoPaused) {
                            videoPaused = true;
                            WindowsCraftClient.LOGGER.info("Viewer for {}: nobody's looking, decoding its sound only", streamId.substring(0, 8));
                        }
                        byte[] rest = TsSplitter.without(c.data(), splitter.videoPid());
                        PAUSED_BYTES.add(c.data().length - rest.length);
                        if (rest.length > 0) {
                            ffIn.write(rest);
                            ffIn.flush();
                        }
                        continue;
                    }
                    if (videoPaused) {
                        videoPaused = false;
                        waitForKey = false;
                        WindowsCraftClient.LOGGER.info("Viewer for {}: looked at again, pictures back", streamId.substring(0, 8));
                    }
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
                    if ((holdNanos > 0 || !held.isEmpty()) && splitter.videoPid() >= 0) {
                        // lip sync: the pictures wait their turn; sound and tables go on now
                        int vpid = splitter.videoPid();
                        byte[] rest = TsSplitter.without(c.data(), vpid), pictures = TsSplitter.only(c.data(), vpid);
                        if (rest.length > 0) ffIn.write(rest);
                        if (pictures.length > 0) held.add(new Held(c.madeNanos(), pictures));
                        ffIn.flush();
                        continue;
                    }
                    ffIn.write(ends != null ? ends.process(c.data(), splitter.videoPid()) : c.data());
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

    // ---- your input for this screen, over the relay (see RelayServer.ControlRoute) ----
    private volatile java.util.concurrent.BlockingQueue<String> inputQueue;

    private void startInput(Socket control) throws IOException {
        java.util.concurrent.BlockingQueue<String> q = new java.util.concurrent.ArrayBlockingQueue<>(1024);
        OutputStream out = control.getOutputStream();
        inputQueue = q;
        daemon("viewer input", () -> {
            try {
                while (running && !control.isClosed()) {
                    String first = q.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (first == null) continue;
                    StringBuilder b = new StringBuilder(first).append('\n');
                    for (String more; (more = q.poll()) != null; ) b.append(more).append('\n');
                    out.write(b.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                if (inputQueue == q) inputQueue = null; // (the game server's way again)
            }
        });
    }

    /**
     * Your click, key or move for one of this stream's screens, over the relay (it passes it to
     * the owner at once, not between the server's ticks). False if this relay doesn't take input
     * (or the connection is being made again): then it goes through the game server.
     */
    public boolean sendControl(java.util.UUID displayId, int op, float x, float y, int a, float f) {
        java.util.concurrent.BlockingQueue<String> q = inputQueue;
        return q != null && q.offer("C " + displayId + " " + op + " " + x + " " + y + " " + a + " " + f);
    }

    /** Pictures held back for lip sync: their packets, and when they arrived. */
    private record Held(long arrived, byte[] data) {}

    /** How long pictures are held back for lip sync right now (ms; 0 = the quickest way). */
    private volatile double lipHoldMs;

    public double lipHoldMs() { return lipHoldMs; }

    private static final dev.windowscraft.measure.Measure.Metric LIP_HOLD = dev.windowscraft.measure.Measure.ms("view.lip_hold",
            "someone else's screen: how long its pictures are held back so they show with their sound (0 while you use it)");

    /** Net thread: when each picture and sound packet started arriving, with its timestamp (lip sync). */
    private static void noteArrivals(List<RelayServer.Chunk> chunks, int vpid, int apid, AvSync sync) {
        if (vpid < 0 || apid < 0) return;
        for (RelayServer.Chunk ch : chunks) {
            byte[] d = ch.data();
            for (int o = 0; o + 188 <= d.length; o += 188) {
                if ((d[o + 1] & 0x40) == 0) continue;
                int pid = ((d[o + 1] & 0x1F) << 8) | (d[o + 2] & 0xFF);
                if (pid != vpid && pid != apid) continue;
                long pts = AvSync.ptsIfStart(d, o);
                if (pts >= 0) sync.arrived(pid == vpid, pts, ch.madeNanos());
            }
        }
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
        CATCHUPS.inc();
        synchronized (arrivals) { arrivals.clear(); } // (those pictures will never be decoded)
        matchDecode = false; // (and the ones still in the decoder would shift every match from here on)
        // while the decoder is still starting (it reads ~0.5 s before its first picture), data
        // piles up in front of it once: that's not this PC being too slow — skip ahead, but don't
        // switch to light playback or ask the owner for a lighter stream over it
        long first = firstPictureNanos;
        if (first != 0 && System.nanoTime() - first > SETTLE_NANOS) fellBehind();
        else WindowsCraftClient.LOGGER.info("Viewer for {} caught up while starting (not counted as falling behind)", streamId.substring(0, 8));
        WindowsCraftClient.LOGGER.info("Viewer for {} was behind; skipped {} KB to get back to live", streamId.substring(0, 8), dropped / 1024);
        return newestKey != null;
    }

    private void readFrames(InputStream in) throws IOException {
        boolean nv12 = planes;
        int w = outW, h = outH;
        int size = Frame.bytes(w, h, nv12);
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
            if (nv12 && !dev.windowscraft.render.Nv12Converter.ready()) { // (it didn't work on this PC)
                quickRestart = true;
                throw new IOException("pictures as BGRA from now on");
            }
            Frame f = pool.acquire(w, h, nv12);
            f.data.clear();
            f.data.put(buf, 0, size).flip();
            f.alphaValid = !nv12; // (FFmpeg's BGRA is opaque with a real alpha; NV12 has none)
            f.captureNanos = System.nanoTime();
            Frame old = latest.getAndSet(f);
            if (old != null) pool.release(old);
            framesDecoded++;
            if (firstPictureNanos == 0) firstPictureNanos = System.nanoTime();
            if (connectStarted != 0) { // (the first picture since connecting: always cleared, recorded only while measuring)
                FIRST_PICTURE.since(connectStarted);
                connectStarted = 0;
            }
            if (dev.windowscraft.measure.Measure.ON) measured(f.captureNanos, old != null);
            windowFrames++;
            long gapNow = System.nanoTime();
            if (lastFrameNanos != 0) {
                long gap = (gapNow - lastFrameNanos) / 1_000_000L;
                if (gap > 250) {
                    STALLS.inc();
                    stalls++;
                    stallMs += gap;
                    longestStallMs = Math.max(longestStallMs, gap);
                    var u = udp;
                    WindowsCraftClient.LOGGER.info("[pause] {}: {} ms without a new picture (lost for good so far {}, resent {}, catch-ups {}, data {} ms ago)",
                            streamId.substring(0, 8), gap, u == null ? 0 : u.skipped, u == null ? 0 : u.recovered, catchUps,
                            (gapNow - lastDataNanos) / 1_000_000L);
                }
            }
            if (damaged) { damagedFrames++; DAMAGED.inc(); }
            lastFrameNanos = gapNow;
            measureTestPattern(buf, w, h, nv12);
            long now = System.nanoTime();
            if (now - windowStart > 1_000_000_000L) {
                status = String.format(Locale.ROOT, "Live · %.0f fps%s", windowFrames / ((now - windowStart) / 1e9), udp != null ? " · UDP" : "");
                windowStart = now;
                windowFrames = 0;
            }
        }
    }

    /** Measuring: a picture just came out of the decoder. */
    private void measured(long now, boolean replacedUnshown) {
        DECODED.inc();
        if (replacedUnshown) NOT_SHOWN.inc();
        if (lastDecoded != 0) FRAME_GAP.record(now - lastDecoded);
        lastDecoded = now;
        Long arrived;
        synchronized (arrivals) {
            arrived = arrivals.poll();
            // a handful are in flight inside the decoder; many more means some never came out
            // (skipped while waiting for a keyframe): start matching over
            if (arrivals.size() > 30) { arrivals.clear(); DECODE_RESYNC.inc(); arrived = null; }
        }
        // (light playback drops every other picture inside FFmpeg: arrivals can't be matched then)
        if (arrived != null && !light && matchDecode) DECODE.record(now - arrived);
    }

    /** The test pattern carries a timing strip and a once-a-second flash (with a beep). */
    private void measureTestPattern(byte[] px, int width, int height, boolean nv12) {
        long ts = nv12 ? TestPatternProducer.readTimestampLuma(px, width, height) : TestPatternProducer.readTimestamp(px, width, height);
        if (ts > 0) {
            long lat = System.currentTimeMillis() - ts;
            E2E.record(lat * 1_000_000L);
            latencyMs = lat;
            latencyMin = Math.min(latencyMin, lat);
            latencyMax = Math.max(latencyMax, lat);
        }
        int x = (int) (width * (1280 - 80) / 1280.0), y = (int) (height * 200 / 720.0);
        boolean white;
        if (nv12) {
            white = TestPatternProducer.lumaToLum(px[y * width + x]) > 220;
        } else {
            int o = (y * width + x) * 4;
            white = (px[o] & 0xFF) > 220 && (px[o + 1] & 0xFF) > 220 && (px[o + 2] & 0xFF) > 220;
        }
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
