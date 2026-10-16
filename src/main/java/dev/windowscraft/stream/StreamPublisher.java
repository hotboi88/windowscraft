package dev.windowscraft.stream;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.audio.AudioCapture;
import dev.windowscraft.audio.AudioHub;
import dev.windowscraft.audio.PcmFades;
import dev.windowscraft.audio.PcmRing;
import dev.windowscraft.audio.PcmStretch;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.net.DisplayInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Streams one live screen to the server's relay: captured frames + audio → FFmpeg (hardware H.264
 * + AAC in MPEG-TS) → relay → every viewer. Encodes exactly once, however many people watch.
 *
 * <p>Raw frames reach FFmpeg over a loopback socket (Windows process pipes are far too slow for
 * 1080p60 BGRA). Everything runs on its own threads; if anything fails (relay restart, FFmpeg
 * crash, window resized) the pipeline is rebuilt after a short pause while the local picture keeps
 * going.
 */
public final class StreamPublisher {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** How long captured sound waits before FFmpeg gets it (feedAudio holds it there). (Step 0 experiment, dev only) */
    static final int CUSHION_MS = Integer.getInteger("windowscraft.exp.audioCushion", 80);
    /**
     * How long before its timestamp the sound was heard on this PC: the cushion, and the capture's
     * own delay before it gets here (measured: a film's flash and beep, shared from a browser).
     */
    static final int AUDIO_LEAD_MS = CUSHION_MS + Integer.getInteger("windowscraft.exp.audioLate", 10);
    /** How much later the sound goes into FFmpeg than its real times, for FFmpeg's scheduling only (see "-bsf:a"). */
    private static final int AUDIO_SCHEDULE_MS = AUDIO_LEAD_MS + 50;
    /** (in FFmpeg's command until the moment its sound clock starts is known) */
    private static final String AUDIO_TIME_SLOT = "asetpts=(start)";

    // measurements (off by default)
    private static final dev.windowscraft.measure.Measure.Metric OUT_AGE = dev.windowscraft.measure.Measure.ms("enc.out_age",
            "a picture leaving the encoder: how long since it was captured (GPU capture path: capture + scale + encode)");
    private static final dev.windowscraft.measure.Measure.Metric OUT_GAP = dev.windowscraft.measure.Measure.ms("enc.out_interval",
            "one encoded picture to the next (the stream's pacing; a still screen sends nothing)");
    private static final dev.windowscraft.measure.Measure.Metric RELAY_WRITE = dev.windowscraft.measure.Measure.ms("enc.relay_write",
            "handing encoded data to the relay connection (long = the connection is backed up)");
    private static final dev.windowscraft.measure.Measure.Metric FEED = dev.windowscraft.measure.Measure.ms("enc.cpu_feed_write",
            "CPU path: writing one raw picture into FFmpeg");
    private static final dev.windowscraft.measure.Measure.Counter BYTES = dev.windowscraft.measure.Measure.counter("enc.bytes", "encoded bytes sent to the relay");

    private static final dev.windowscraft.measure.Measure.Counter PICTURES = dev.windowscraft.measure.Measure.counter("enc.pictures", "encoded pictures sent");
    private static final dev.windowscraft.measure.Measure.Counter RESTARTS = dev.windowscraft.measure.Measure.counter("enc.restarts", "stream pipeline rebuilt (FFmpeg restarted)");
    private static final dev.windowscraft.measure.Measure.Counter LOWERED = dev.windowscraft.measure.Measure.counter("enc.lowered", "stream turned down a step");
    private static final dev.windowscraft.measure.Measure.Counter CPU_SKIPS = dev.windowscraft.measure.Measure.counter("enc.cpu_skips", "CPU path: FFmpeg fell half a second behind, pictures skipped");

    /** A wall showing this stream. One capture + one encode can feed several walls (mirroring). */
    public record Target(UUID displayId, String dimension, WallRect rect, int fit, List<UUID> controllers) {}

    private volatile List<Target> targets;
    private final SourceInfo source;
    private final CaptureSession session;
    private volatile CaptureSettings settings;
    private final String streamId;
    private final Thread supervisor;
    private volatile boolean running = true;

    // newest captured frame, copied out of the capture pool; buffers are recycled
    private final AtomicReference<byte[]> latest = new AtomicReference<>();
    private final ConcurrentLinkedQueue<byte[]> spare = new ConcurrentLinkedQueue<>();
    private volatile int frameW, frameH;

    // resources of the running pipeline, so stop() can tear them down immediately
    private final CopyOnWriteArrayList<AutoCloseable> live = new CopyOnWriteArrayList<>();
    private volatile Process ffmpeg;

    private volatile String status = "Starting…";
    private volatile double kbps;
    private volatile String audioStatus = "";
    private volatile int streamW, streamH, streamFps; // what the current pipeline encodes (0 = not yet)
    private volatile boolean softwareOnly;              // the GPU encoder failed (e.g. out of sessions)
    private volatile boolean gpuFailed;                 // GPU capture didn't work on this PC: use the GDI path
    /**
     * Which way of feeding the GPU capture to the encoder is being tried: 0 = straight into NVENC
     * (it takes the BGRA pictures itself), 1 = converted on the GPU (scale_d3d11) at a size that's
     * a multiple of 16 (some drivers refuse other sizes). After that: the CPU path.
     */
    private volatile int gpuAttempt;
    /** Ask the relay to pass viewers' input straight here (until it turns out not to know how). */
    private volatile boolean relayInput = !"false".equals(System.getProperty("windowscraft.relayInput"));
    private volatile boolean gpuActive;                 // the current pipeline captures on the GPU
    private volatile boolean pipelineGpu;
    private volatile long pipelineBytes;
    private volatile int pipelineGen;

    // ---- fitting the stream to the connection --------------------------------------------------
    /**
     * How far the stream is turned down because the connection to the server (or this PC's
     * encoder) can't keep up: 0 = full quality; each step sends ~40 % less, 30 fps from step 1,
     * at most 720p from step 2. Without this a too-big stream backs up: viewers get a stuttering
     * picture and sound that runs further and further behind.
     */
    private volatile int level;
    private volatile String levelWhy = "";
    private volatile long levelSince = System.nanoTime();
    private volatile boolean triedUp; // a step back up already failed: stay down for this share
    private volatile int videoSkips; // CPU path: the encoder couldn't take the frames in time
    private static final int MAX_LEVEL = 4;
    private static final int STREAM_MAX_H = Integer.getInteger("windowscraft.streamMaxHeight", 1080);
    /** A different share of your upload, and since when it has held (0: none waiting). */
    private long reshareSince;
    private int reshareKb;
    // GPU mode: FFmpeg sends back a small preview for your own wall (newest frame wins)
    private final AtomicReference<Frame> preview = new AtomicReference<>();
    private final dev.windowscraft.capture.FramePool previewPool = new dev.windowscraft.capture.FramePool();
    private volatile long lastPreviewNanos;
    /** Preview of your own stream, for the wall, while capture runs on the GPU. */
    private final dev.windowscraft.capture.FrameSource previewSource = new dev.windowscraft.capture.FrameSource() {
        @Override public Frame takeFrame() { return preview.getAndSet(null); }
        @Override public void recycle(Frame f) { previewPool.release(f); }
    };

    // ---- who's watching (the relay tells, if it can) and your upload shared among them ---------------
    private static final java.util.Set<StreamPublisher> ALL = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** How many watch this stream right now (-1: not known — an older server). */
    private volatile int watchers = -1;
    private volatile long zeroSince;
    /** The relay connection kept open while nobody watches (no encoding meanwhile). */
    private volatile Socket keptRelay;
    private volatile boolean goingIdle;
    private static final long IDLE_AFTER_NANOS = 8_000_000_000L;

    /** Nobody has watched for a while: no encoding at all (it starts again the moment someone looks). */
    private boolean idle() { return watchers == 0 && zeroSince != 0 && System.nanoTime() - zeroSince > IDLE_AFTER_NANOS; }

    public int watchers() { return watchers; }

    /** This stream's part of your upload: shared evenly among your streams that someone watches. */
    private int budgetKb() {
        long n = ALL.stream().filter(p -> p != this && p.running && p.watchers != 0).count() + 1;
        return (int) Math.max(800, dev.windowscraft.config.ClientSettings.uploadKbps() / n);
    }

    private Socket connectRelay() throws IOException {
        Socket s;
        try {
            // PUB3: tell me who's watching, and pass me their input (PUB2: only who's watching)
            s = Proc.connectRelay(ClientNet.relayHost(), ClientNet.relayPort(), relayInput ? "PUB3" : "PUB2", ClientNet.token(), streamId);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains("bad role")) throw e;
            if (relayInput) { // (a server from before input over the relay)
                relayInput = false;
                return connectRelay();
            }
            watchers = -1; // (an older server: it can't say who's watching)
            return Proc.connectRelay(ClientNet.relayHost(), ClientNet.relayPort(), "PUB", ClientNet.token(), streamId);
        }
        Socket sock = s;
        daemon("viewer count", () -> {
            try {
                java.io.InputStream in = sock.getInputStream();
                while (running && !sock.isClosed()) {
                    String line = dev.windowscraft.relay.RelayProtocol.readLine(in, 256).trim();
                    if (line.startsWith("C ")) { // someone's input, over the relay
                        dev.windowscraft.client.ScreenControl.fromRelay(line);
                        continue;
                    }
                    if (!line.startsWith("VIEWERS ")) continue;
                    int n = Integer.parseInt(line.substring(8).trim());
                    if (n == 0 && zeroSince == 0) zeroSince = System.nanoTime();
                    if (n > 0) zeroSince = 0;
                    if (n != watchers) WindowsCraftClient.LOGGER.info("Stream '{}': {} watching", source.title(), n);
                    watchers = n;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        });
        return sock;
    }

    /** While idle: an empty packet now and then keeps the relay connection (and the stream) there. */
    private void idleKeepAlive() {
        Socket r = keptRelay;
        if (r == null || r.isClosed()) {
            keptRelay = null;
            watchers = -1; // (lost it: start over normally)
            zeroSince = 0;
            return;
        }
        byte[] nul = new byte[188];
        java.util.Arrays.fill(nul, (byte) 0xFF);
        nul[0] = 0x47; nul[1] = 0x1F; nul[2] = (byte) 0xFF; nul[3] = 0x10;
        try {
            OutputStream o = r.getOutputStream();
            synchronized (o) {
                o.write(nul);
                o.flush();
            }
        } catch (IOException e) {
            Proc.closeQuietly(r);
            keptRelay = null;
            watchers = -1;
            zeroSince = 0;
        }
    }

    private final AudioHub audioHub;
    /** True while your own wall gets its picture by direct GPU sharing (no preview needed from FFmpeg). */
    private volatile java.util.function.BooleanSupplier wallFedElsewhere = () -> false;

    public void setWallFedElsewhere(java.util.function.BooleanSupplier s) { wallFedElsewhere = s; }

    /** True while direct GPU sharing is starting up (its outcome decides whether FFmpeg sends a preview). */
    private volatile java.util.function.BooleanSupplier wallStarting = () -> false;

    public void setWallStarting(java.util.function.BooleanSupplier s) { wallStarting = s; }

    /** Rebuild the pipeline now (e.g. the wall needs FFmpeg's preview again). */
    public void restartPipeline() {
        Process p = ffmpeg;
        if (p != null) p.destroyForcibly();
    }

    public StreamPublisher(List<Target> targets, SourceInfo source, CaptureSession session, AudioHub audioHub, CaptureSettings settings) {
        this.targets = List.copyOf(targets);
        this.audioHub = audioHub;
        this.source = source;
        this.session = session;
        this.settings = settings;
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        this.streamId = HexFormat.of().formatHex(id);
        session.setTap(this::onFrame);
        ALL.add(this);
        supervisor = new Thread(this::supervise, "WindowsCraft stream " + source.title());
        supervisor.setDaemon(true);
        supervisor.start();
    }

    // capture thread: copy the newest frame for the encoder into a recycled buffer
    private void onFrame(Frame f) {
        // the window's own size, also while your wall gets a smaller copy of it (as sharp as it
        // looks): a smaller copy is no reason to rebuild the stream, and isn't for the encoder
        int fw = session.fullWidth(), fh = session.fullHeight();
        if (fh > 0 && f.height < fh) {
            frameW = fw;
            frameH = fh;
            return;
        }
        int size = f.byteSize();
        byte[] buf = spare.poll();
        if (buf == null || buf.length != size) buf = new byte[size];
        f.data.position(0).limit(size);
        f.data.get(buf);
        f.data.position(0);
        frameW = f.width;
        frameH = f.height;
        byte[] unused = latest.getAndSet(buf);
        if (unused != null && spare.size() < 3) spare.offer(unused);
    }

    public String status() { return status; }
    /** How far the stream is turned down (0 = full quality), and why. */
    public int level() { return level; }
    public String levelWhy() { return levelWhy; }
    /** True while the screen is captured, scaled and encoded on the graphics card (light on the CPU). */
    public boolean gpuActive() { return gpuActive; }
    /** Is anyone able to watch it (the server's relay takes this stream)? */
    public boolean streaming() { return running && anyRelayOpen(); }
    /** Nobody's watching: nothing is encoded or sent until someone does. */
    public boolean paused() { return running && idle(); }
    /** While GPU capture runs, your own wall shows this preview instead of a separate CPU capture. */
    public dev.windowscraft.capture.FrameSource previewSource() {
        return gpuActive && System.nanoTime() - lastPreviewNanos < 2_000_000_000L ? previewSource : null;
    }
    public double kbps() { return kbps; }
    public String audioStatus() { return audioStatus; }
    public String streamId() { return streamId; }
    public void updateSettings(CaptureSettings s) { settings = s; }

    private volatile dev.windowscraft.client.Box box = dev.windowscraft.client.Box.FULL;

    /** Where the app's client area is in the stream's picture; re-announced when it changes. */
    public dev.windowscraft.client.Box box() { return box; }

    public void setBox(dev.windowscraft.client.Box b) {
        if (b == null || b.near(box)) return;
        box = b;
        announceAll();
    }

    /** The stream's picture size (0 until it's known). */
    public int streamWidth() { return streamW; }
    public int streamHeight() { return streamH; }

    /** Changes which walls show this stream (re-announces them). */
    public void setTargets(List<Target> t) {
        targets = List.copyOf(t);
        announceAll();
    }

    /** Announces every wall of this stream (also the periodic heartbeat). No-op until the size is known. */
    public void announceAll() {
        if (streamW == 0) return;
        for (DisplayInfo i : infos()) ClientNet.announce(i);
    }

    private List<DisplayInfo> infos() {
        List<DisplayInfo> out = new ArrayList<>();
        for (Target t : targets) {
            out.add(new DisplayInfo(t.displayId(), t.dimension(), t.rect().faceString(), t.rect().min(), t.rect().max(),
                    source.title(), streamId, streamW, streamH, streamFps, t.fit(), t.controllers(),
                    (float) box.x0(), (float) box.y0(), (float) box.x1(), (float) box.y1()));
        }
        return out;
    }

    private boolean anyRelayOpen() {
        for (Target t : targets) {
            ClientNet.Ack a = ClientNet.ack(t.displayId());
            if (a != null && a.relayOpen()) return true;
        }
        return false;
    }

    private ClientNet.Ack anyAck() {
        for (Target t : targets) {
            ClientNet.Ack a = ClientNet.ack(t.displayId());
            if (a != null) return a;
        }
        return null;
    }

    public void stop() {
        running = false;
        ALL.remove(this);
        session.setTap(null);
        supervisor.interrupt();
        teardown();
        Socket k = keptRelay;
        keptRelay = null;
        if (k != null) Proc.closeQuietly(k);
    }

    private void teardown() {
        pipelineGen++;      // a preview frame still in flight must not switch the CPU capture off again
        gpuActive = false;
        Process p = ffmpeg;
        if (p != null) p.destroyForcibly();
        for (AutoCloseable c : live) Proc.closeQuietly(c);
        live.clear();
        session.setExternal(false); // the CPU capture takes over again until a pipeline is back
    }

    /** Waits for FFmpeg to connect, but gives up as soon as FFmpeg has exited. */
    private static Socket acceptWhileAlive(ServerSocket listen, Process ff) throws IOException {
        listen.setSoTimeout(500);
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            try {
                return listen.accept();
            } catch (java.net.SocketTimeoutException t) {
                if (!ff.isAlive()) throw new IOException("FFmpeg exited (" + ff.exitValue() + ") before connecting");
                if (System.currentTimeMillis() > deadline) throw new IOException("FFmpeg didn't connect");
            }
        }
    }

    private <T extends AutoCloseable> T track(T c) {
        live.add(c);
        if (!running) Proc.closeQuietly(c);
        return c;
    }

    private void supervise() {
        try {
            status = Ffmpeg.state() == Ffmpeg.State.READY ? "Starting…" : "Preparing FFmpeg…";
            Ffmpeg.ensure().join();
        } catch (Exception e) {
            status = "Not streamed: " + Ffmpeg.status();
            return;
        }
        while (running) {
            if (idle() && keptRelay != null) {
                // nobody's watching: nothing is encoded or sent (your own wall carries on as usual)
                status = "Paused · nobody's watching";
                idleKeepAlive();
                sleep(500);
                continue;
            }
            try {
                runPipeline();
            } catch (Exception e) {
                if (!running) return;
                if (pipelineGpu && pipelineBytes == 0 && !gpuFailed) {
                    // whatever went wrong, the GPU path never got going: try the other way, then the CPU path
                    if (gpuAttempt < 1) gpuAttempt++;
                    else gpuFailed = true;
                    WindowsCraftClient.LOGGER.warn("GPU capture didn't start for '{}' ({}); {}", source.title(), e.toString(),
                            gpuFailed ? "using CPU capture" : "trying the GPU's own converter");
                }
                WindowsCraftClient.LOGGER.warn("Stream pipeline restarting: {}", e.toString());
                RESTARTS.inc();
                status = "Reconnecting… (" + e.getMessage() + ")";
            } finally {
                teardown();
            }
            if (Thread.interrupted()) return;
            if (goingIdle) {
                goingIdle = false;
                WindowsCraftClient.LOGGER.info("Stream '{}': nobody's watching, pausing it", source.title());
                continue;
            }
            sleep(quickRestart ? 200 : 2000);
            quickRestart = false;
        }
    }

    private void runPipeline() throws Exception {
        // 1. wait for a picture so we know the size
        while (running && latest.get() == null) {
            status = "Waiting for the first frame…";
            sleep(100);
        }
        if (!running) return;
        int w = frameW, h = frameH;
        // even dimensions (H.264 4:2:0), and never above what hardware encoders accept
        double scale = Math.min(1.0, Math.min(Ffmpeg.MAX_STREAM_W / (double) w, Ffmpeg.MAX_STREAM_H / (double) h));
        // (your own wall may show an in-game monitor at its full size: the stream keeps the size you picked)
        if (settings.maxHeight() > 0 && h > settings.maxHeight()) scale = Math.min(scale, settings.maxHeight() / (double) h);
        // at most 1080 lines for the people watching: a 4K monitor's own wall stays 4K (it doesn't
        // use the stream), but 4K pictures were ~12 MB each for a viewer's PC to take in — three
        // screens took a laptop down to 15 fps
        if (h * scale > STREAM_MAX_H) scale = STREAM_MAX_H / (double) h;
        int ew = Math.max(2, (int) (w * scale) & ~1), eh = Math.max(2, (int) (h * scale) & ~1);
        if (gpuAttempt >= 1 && !gpuFailed) { // second GPU try: sizes the GPU's converter always accepts
            ew = Math.max(16, ew & ~15);
            eh = Math.max(16, eh & ~15);
        }
        CaptureSettings ps = settings;
        int fps = level >= 1 ? Math.min(ps.fps(), 30) : ps.fps();
        if (level >= 2 && eh > 720) { // 720p
            double k = 720.0 / eh;
            ew = Math.max(16, (int) (ew * k) & ~(gpuAttempt >= 1 && !gpuFailed ? 15 : 1));
            eh = 720;
        }

        // 2. tell the server (and through it, everyone) and wait for the relay to accept
        streamW = ew;
        streamH = eh;
        streamFps = fps;
        announceAll();
        long waitStart = System.currentTimeMillis();
        while (!anyRelayOpen()) {
            if (!running) return;
            ClientNet.Ack ack = anyAck();
            if (!ClientNet.serverSupported()) status = "Only you can see this — the server doesn't have WindowsCraft";
            else if (ack != null) status = "Not streamed: " + ack.message();
            else if (System.currentTimeMillis() - waitStart > 5000) status = "Waiting for the server…";
            sleep(500);
            if (System.currentTimeMillis() - waitStart > 15_000) {
                announceAll(); // the periodic heartbeat may also flip this to open later
                waitStart = System.currentTimeMillis();
            }
        }

        // 3. relay, FFmpeg, and the pumps (everything tracked so a failure or stop() closes it)
        status = "Connecting to the relay…";
        Socket kept = keptRelay;
        keptRelay = null;
        Socket relay = track(kept != null && !kept.isClosed() ? kept : connectRelay());
        ServerSocket videoListen = track(Proc.loopbackListener());
        ServerSocket audioListen = track(Proc.loopbackListener());
        PcmRing audio = ps.audio() ? audioHub.openTap() : null; // the hub's taps outlive capture restarts
        if (audio != null) track(() -> audioHub.closeTap(audio));
        audioStatus = audioHub.status();
        Ffmpeg.Encoder enc = softwareOnly ? Ffmpeg.software() : Ffmpeg.encoder();
        int ownKb = Math.max(1200, (int) (Ffmpeg.bitrateKbps(ew, eh, fps) * Math.pow(0.65, level)));
        // (several screens at once: your upload is shared among those someone's watching)
        int kb = Math.min(ownKb, budgetKb());
        boolean gpu = gpuEligible(enc);
        List<String> cmd = new ArrayList<>(List.of(Ffmpeg.executable().toString(), "-hide_banner", "-loglevel", "warning", "-nostats"));
        // Video and audio share one timeline: the wall clock. Pictures are stamped with it as they
        // come in (they may come unevenly). Sound is NOT stamped per packet — loopback reads hand
        // it over in uneven bursts, and stamping each one with the time it arrived made its
        // timestamps run backwards: the AAC encoder then threw most of it away (a few packets a
        // second; viewers heard nothing) and FFmpeg logged thousands of warnings a second. Instead
        // its clock is its own sample count, started at the wall clock when its first sound comes
        // in (it's fed at exactly 48 kHz in real time, so the two clocks stay together).
        // ...and back by how long it waits in the cushion before FFmpeg gets it (feedAudio holds
        // that steady), so its time is when it was heard on this PC, like the pictures': viewers
        // line picture and sound up by these times (lip sync).
        // Its clock starts at a moment fixed just before FFmpeg starts: feedAudio counts its
        // samples from then (silence for the time FFmpeg takes to start). It started at FFmpeg's
        // own RTCSTART before — when its sound filter was set up, after the encoder and the rest
        // had started: later than the first sample by that much, so all of the sound was stamped
        // ~150 ms late and viewers heard it that much after the picture.
        List<String> audioTime = List.of("-af", AUDIO_TIME_SLOT);
        ServerSocket previewListen = null;
        int[] pv = {ew, eh};
        if (gpu) { // give direct GPU sharing a moment to start, so we know if a preview is needed
            long until = System.currentTimeMillis() + 4000;
            while (running && wallStarting.getAsBoolean() && System.currentTimeMillis() < until) sleep(100);
        }
        boolean wantPreview = gpu && !wallFedElsewhere.getAsBoolean();
        if (gpu) {
            // Windows Graphics Capture → scale/convert on the GPU → hardware encoder. A copy of the
            // same frames comes back for your own wall (full size and rate) — no CPU capture, no
            // CPU colour conversion.
            if (wantPreview) previewListen = track(Proc.loopbackListener());
            // NVENC takes the captured BGRA pictures as they are (it converts them itself); the other
            // encoders get them converted to NV12 on the GPU first
            boolean direct = enc.name().equals("h264_nvenc") && gpuAttempt == 0;
            String toEncoder = direct ? "null"
                    : "scale_d3d11=width=" + ew + ":height=" + eh + ":format=nv12"
                    + (enc.name().equals("h264_qsv") ? ",hwmap=derive_device=qsv,format=qsv" : "");
            // The capture is a filter-graph source (it only makes D3D11 frames, which an -f lavfi
            // input can't carry) and delivers ew×eh BGRA, so the wall's copy is just a download.
            // Its frames are stamped with the capture clock: re-stamp them with the wall clock,
            // like the audio input, and keep absolute times (-copyts) so the two line up.
            // No fps filter: it holds the newest picture back until the next one arrives, so when
            // the screen goes still right after a change (a window dropping behind another), viewers
            // kept seeing the picture from before it. The capture already caps the rate itself.
            String graph = wantPreview
                    ? gfxSource(ps, fps, ew, eh) + ",setpts=RTCTIME/(TB*1000000),split=2[e][p];"
                      + "[e]" + toEncoder + "[ev];[p]hwdownload,format=bgra[pv]"
                    : gfxSource(ps, fps, ew, eh) + ",setpts=RTCTIME/(TB*1000000)," + toEncoder + "[ev]";
            cmd.addAll(List.of("-copyts",
                    "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "tcp://127.0.0.1:" + audioListen.getLocalPort(),
                    "-filter_complex", graph, "-map", "[ev]", "-map", "0:a"));
            cmd.addAll(audioTime);
            cmd.addAll(List.of(
                    // each picture goes out as it comes (none held back to even out the rate)
                    "-fps_mode:v", "passthrough",
                    // pictures only come when the screen changes: a fresh start point for viewers
                    // at least every second of them, counted in time rather than pictures
                    "-force_key_frames:v", "expr:if(isnan(prev_forced_t),1,gte(t-prev_forced_t," + keyEvery() + "))"));
        } else {
            String sizeFilter = (ew == (w & ~1) && eh == (h & ~1)) ? "crop=" + ew + ":" + eh + ":0:0" : "scale=" + ew + ":" + eh;
            cmd.addAll(List.of(
                    "-use_wallclock_as_timestamps", "1",
                    "-f", "rawvideo", "-pix_fmt", "bgra", "-video_size", w + "x" + h, "-framerate", Integer.toString(fps),
                    "-i", "tcp://127.0.0.1:" + videoListen.getLocalPort(),
                    "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "tcp://127.0.0.1:" + audioListen.getLocalPort(),
                    // absolute times kept (as on the GPU path), so the sound's wall-clock start lines up
                    "-copyts",
                    "-map", "0:v", "-map", "1:a"));
            cmd.addAll(audioTime);
            cmd.addAll(List.of(
                    "-vf", sizeFilter + ",format=" + (enc.name().equals("h264_qsv") ? "nv12" : "yuv420p")));
        }
        cmd.addAll(enc.args());
        cmd.addAll(List.of("-b:v", kb + "k", "-maxrate", kb + "k", "-bufsize", (kb / 2) + "k", "-g", Integer.toString(Math.max(1, (int) Math.round(fps * keyEvery()))), "-bf", "0",
                "-c:a", "aac", "-b:a", "192k",
                // FFmpeg reads its inputs in step by these times, holding back whichever gets 100 ms
                // ahead — and the sound, stamped when it was heard, is behind it by the cushion and
                // the AAC encoder's own delay (~130 ms): the pictures were held back for it (judder,
                // half of them dropped on the CPU path). So the sound goes in that much later and
                // comes back to its real times here, after FFmpeg's scheduling, on the way out.
                "-bsf:a", String.format(Locale.ROOT, "setts=ts=TS-%.3f/TB", AUDIO_SCHEDULE_MS / 1000.0),
                // never let the muxer hold pictures back waiting for sound (FFmpeg's default: up to
                // 10 s; 100 ms here before). With the sound's timing fixed, that wait was most of the
                // delay to the encoder's output: 17.6/55/61 ms → 3.3/6.6/12.5 ms (CPU path), and
                // lip sync on viewers didn't change (they play sound and picture separately)
                "-max_interleave_delta", System.getProperty("windowscraft.exp.interleave", "1"),
                "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1",
                // each picture's packet says how long it is (FFmpeg leaves video's out by default),
                // so a viewer's TS reader passes it on without waiting for the next picture to
                // start: one picture less of delay (pictures up to 64 KB; bigger ones wait as before)
                "-omit_video_pes_length", StreamSubscriber.promptDecode() ? "0" : "1",
                "pipe:1"));
        if (wantPreview) {
            // (one thread: FFmpeg's raw-picture writer on helper threads hands each picture out
            // only when the next one comes in — a picture late on your wall)
            cmd.addAll(List.of("-map", "[pv]", "-fps_mode", "passthrough", "-threads:v", "1", "-f", "rawvideo", "-pix_fmt", "bgra", "tcp://127.0.0.1:" + previewListen.getLocalPort()));
        }
        long audioStart = System.nanoTime();
        java.time.Instant wall = java.time.Instant.now();
        double audioStartS = wall.getEpochSecond() + wall.getNano() / 1e9 - (AUDIO_LEAD_MS - AUDIO_SCHEDULE_MS) / 1000.0;
        cmd.set(cmd.indexOf(AUDIO_TIME_SLOT), String.format(Locale.ROOT, "asetpts=N/SR/TB+%.6f/TB", audioStartS));
        Process ff = Proc.start(cmd, "ffmpeg-enc");
        ffmpeg = ff;
        pipelineGpu = gpu;
        pipelineBytes = 0;
        lastPreviewNanos = 0;
        if (gpu) {
            if (wantPreview) {
                ServerSocket pl = previewListen;
                int gen = pipelineGen;
                daemon("preview in", () -> readPreview(pl, pv[0], pv[1], gen));
            }
            Socket audioSock = track(acceptWhileAlive(audioListen, ff));
            daemon("audio in", () -> feedAudio(audioSock, audio, audioStart));
        } else {
            // FFmpeg opens its inputs in order and reads a little of each: video must already be
            // flowing before it will connect to the audio socket.
            Socket videoSock = track(acceptWhileAlive(videoListen, ff));
            videoSock.setSendBufferSize(4 << 20);
            daemon("video in", () -> feedVideo(videoSock, w, h, fps));
            Socket audioSock = track(acceptWhileAlive(audioListen, ff));
            daemon("audio in", () -> feedAudio(audioSock, audio, audioStart));
        }
        gpuActive = gpu;
        String how = enc.label() + (gpu ? " · GPU capture" : "") + (level > 0 ? " · " + ew + "×" + eh + "@" + fps + " " + kb + " kb/s, lowered: " + levelWhy : "");
        status = "Live · " + how;
        WindowsCraftClient.LOGGER.info("Streaming '{}' {}x{}@{} via {}{} at {} kb/s to {}:{}", source.title(), ew, eh, fps, enc.name(),
                gpu ? " (GPU capture)" : "", kb, ClientNet.relayHost(), ClientNet.relayPort());
        String measureKey = "stream " + streamId.substring(0, 8);
        String measureFact = ew + "×" + eh + "@" + fps + " " + enc.name() + (gpu ? ", GPU capture" + (wantPreview ? " + preview for your wall" : "") : ", CPU frames")
                + ", " + kb + " kb/s, step " + level + (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(source) ? ", in-game monitor" : "");
        dev.windowscraft.measure.Measure.note(measureKey, measureFact);
        dev.windowscraft.relay.PtsWatch ptsWatch = new dev.windowscraft.relay.PtsWatch();
        long[] lastPic = {0};
        java.util.function.LongConsumer onPts = p -> {
            long age = dev.windowscraft.relay.PtsWatch.ageNanos(p, System.currentTimeMillis());
            if (age != Long.MIN_VALUE) OUT_AGE.record(age);
            long t = System.nanoTime();
            if (lastPic[0] != 0) OUT_GAP.record(t - lastPic[0]);
            lastPic[0] = t;
            PICTURES.inc();
        };
        long pipelineStart = System.nanoTime();
        videoSkips = 0;
        int slowSeconds = 0, skipSeconds = 0;
        long blocked = 0;

        // FFmpeg output → relay (this thread)
        InputStream out = ff.getInputStream();
        OutputStream net = relay.getOutputStream();
        byte[] buf = new byte[64 * 1024];
        long windowStart = System.nanoTime(), windowBytes = 0, total = 0;
        // A still screen can leave FFmpeg with nothing to send for a while (the capture only makes
        // a picture when something changes); the server takes ten seconds of silence for a stream
        // that's gone, and everyone watching saw "Reconnecting…". So when nothing has come for a
        // couple of seconds, an empty "null" packet (which every player ignores) says we're here.
        long[] lastOut = {System.nanoTime()};
        long[] written = {0};
        Process thisFf = ff;
        daemon("keep-alive", () -> {
            byte[] nul = new byte[188];
            java.util.Arrays.fill(nul, (byte) 0xFF);
            nul[0] = 0x47; nul[1] = 0x1F; nul[2] = (byte) 0xFF; nul[3] = 0x10;
            while (running && thisFf.isAlive() && ffmpeg == thisFf) {
                sleep(1000);
                if (idle()) {
                    // nobody's watching any more: stop encoding, keep the connection
                    live.remove(relay);
                    keptRelay = relay;
                    goingIdle = true;
                    thisFf.destroyForcibly();
                    return;
                }
                synchronized (net) {
                    if (System.nanoTime() - lastOut[0] < 2_000_000_000L || written[0] % 188 != 0) continue;
                    try {
                        net.write(nul);
                        net.flush();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        // (dev only, for looking into a stream: windowscraft.exp.dumpTs=<file> keeps its first 4 MB, or windowscraft.exp.dumpTsMB)
        java.io.OutputStream dump = null;
        String dumpTo = System.getProperty("windowscraft.exp.dumpTs");
        if (dumpTo != null) try { dump = new java.io.FileOutputStream(dumpTo); } catch (IOException ignored) {}
        long dumped = 0;
        int n;
        while (running && (n = out.read(buf)) > 0) {
            if (dump != null) {
                try { dump.write(buf, 0, n); dumped += n; } catch (IOException ignored) {}
                if (dumped > ((long) Integer.getInteger("windowscraft.exp.dumpTsMB", 4) << 20)) { try { dump.close(); } catch (IOException ignored) {} dump = null; }
            }
            long w0 = System.nanoTime();
            synchronized (net) {
                net.write(buf, 0, n);
                written[0] += n;
                lastOut[0] = System.nanoTime();
            }
            long stuck = System.nanoTime() - w0;
            blocked += stuck;
            if (dev.windowscraft.measure.Measure.ON) {
                RELAY_WRITE.record(stuck);
                BYTES.add(n);
                ptsWatch.feed(buf, 0, n, onPts);
                dev.windowscraft.measure.Measure.note(measureKey, measureFact);
            }
            // one write held up for over a second: the connection is far too slow for this stream
            if (stuck > 1_000_000_000L) lower("the connection to the server is too slow");
            windowBytes += n;
            total += n;
            pipelineBytes = total;
            long now = System.nanoTime();
            if (now - windowStart > 1_000_000_000L) {
                kbps = windowBytes * 8 / ((now - windowStart) / 1e9) / 1000;
                // the server can't take the stream as fast as it's made: it would back up, and
                // everyone watching would fall behind — send less instead
                double stuckShare = blocked / (double) (now - windowStart);
                slowSeconds = stuckShare > 0.3 ? slowSeconds + 1 : 0;
                blocked = 0;
                windowBytes = 0;
                windowStart = now;
                if (slowSeconds >= 2) lower("the connection to the server is too slow");
                // the encoder not taking frames in time, again and again (not just while it starts up)
                skipSeconds = videoSkips > 0 && now - pipelineStart > 5_000_000_000L ? skipSeconds + 1 : 0;
                videoSkips = 0;
                if (skipSeconds >= 4) lower("this PC couldn't encode it fast enough");
                String bw = busyWhy;
                busyWhy = null;
                if (bw != null && now - pipelineStart > 5_000_000_000L) lower(bw);
                // someone watching can't keep up. Give each step a few seconds to show its effect
                // (a movie on a link that's too slow glitches until the stream fits: get there
                // quickly — 5 s for the first steps, then 15 s, so it doesn't overshoot)
                int vt = viewerTrouble;
                viewerTrouble = 0;
                if (vt == dev.windowscraft.net.Payloads.TROUBLE_NETWORK) lossyViewer = true;
                long gate = level < 2 ? 5_000_000_000L : 15_000_000_000L;
                if (vt != 0 && now - levelSince > gate && now - pipelineStart > 3_000_000_000L)
                    lower(vt == dev.windowscraft.net.Payloads.TROUBLE_DECODE ? "a viewer's PC can't play it fast enough" : "it doesn't reach a viewer fast enough");
                // a while without trouble: try one step better (once; if that fails it stays down)
                if (level > 0 && !triedUp && now - levelSince > 300_000_000_000L) {
                    level--;
                    levelSince = now;
                    triedUp = true;
                    WindowsCraftClient.LOGGER.info("Stream '{}': trying quality step {} again", source.title(), level);
                    quickRestart = true;
                    throw new IOException("trying better quality");
                }
                // your upload is shared differently now (a screen more or less being watched): re-fit —
                // once the new share has held for 30 s. Every re-fit restarts the stream (a moment
                // without sound for everyone watching), and the count blinks 1 → 2 → 1 whenever a
                // viewer reconnects: that restarted streams 9 times in 20 minutes, music skipping
                int want = Math.min(ownKb, budgetKb());
                if (Math.abs(want - kb) <= kb * 0.35) {
                    reshareSince = 0;
                } else if (reshareSince == 0 || Math.abs(want - reshareKb) > reshareKb * 0.35) {
                    reshareSince = now;
                    reshareKb = want;
                } else if (now - reshareSince > 30_000_000_000L && now - pipelineStart > 20_000_000_000L) {
                    reshareSince = 0;
                    WindowsCraftClient.LOGGER.info("Stream '{}': its part of your upload is now {} kb/s (was {})", source.title(), want, kb);
                    quickRestart = true;
                    throw new IOException("upload shared differently");
                }
                audioStatus = audioHub.status();
                status = String.format(Locale.ROOT, "Live · %s · %.1f Mbit/s", how, kbps / 1000)
                        + (watchers >= 0 ? " · " + watchers + " watching" : "");
                // no picture at all after 10 s = the GPU path doesn't work here (a still window just
                // sends nothing new, which is fine once the first frame came through)
                if (wantPreview && lastPreviewNanos == 0 && now - pipelineStart > 10_000_000_000L) gpuFail("no frames from the capture");
            }
            // rebuild at the new size — on the GPU path only for a real change (the capture fits the
            // window into the stream by itself; a window flickering by a couple of pixels mustn't
            // restart the stream every few seconds)
            if (gpu ? Math.abs(frameW - w) > Math.max(32, w / 12) || Math.abs(frameH - h) > Math.max(32, h / 12)
                    : frameW != w || frameH != h) throw new IOException("window resized");
            CaptureSettings cur = settings;
            if (!cur.sameStream(ps)) throw new IOException("settings changed");
        }
        if (goingIdle) return; // (stopped because nobody's watching: not a failure)
        if (running && gpu && total == 0) gpuFail("FFmpeg produced nothing (exit " + (ff.isAlive() ? "?" : ff.exitValue()) + ")");
        if (running && total == 0 && !enc.name().equals(Ffmpeg.software().name())) {
            // the hardware encoder refused to start (most often: the GPU's limit on simultaneous
            // encodes is used up by other walls/apps) — fall back to software for this stream
            softwareOnly = true;
            WindowsCraftClient.LOGGER.warn("{} didn't start for '{}'; using software encoding for this stream", enc.label(), source.title());
            throw new IOException(enc.label() + " busy, switching to software");
        }
        if (running) throw new IOException("FFmpeg stopped (exit " + (ff.isAlive() ? "?" : ff.exitValue()) + ")");
    }

    /** A viewer's connection loses pieces: fresh start points come twice as often then, so a lost piece costs half as long a pause. */
    private volatile boolean lossyViewer;

    /** Seconds between fresh start points (keyframes). */
    // (no more often for a lossy viewer: each start point is a big picture, and on a connection
    // that's already struggling more of them only makes it worse — a lost piece now costs a
    // short glitch that the next start point clears, not a frozen picture)
    private double keyEvery() { return 1.0; }

    /** Turns the stream down one step and rebuilds it right away (the backlog goes with the old one). */
    private void lower(String why) throws IOException {
        if (level >= MAX_LEVEL) return;
        // trouble long after the last change is something new: a later step up may be tried again
        // (trouble right after a step up means that step was too much: it stays down)
        if (System.nanoTime() - levelSince > 120_000_000_000L) triedUp = false;
        level++;
        levelWhy = why;
        levelSince = System.nanoTime();
        WindowsCraftClient.LOGGER.warn("Stream '{}': {}; lowering quality to step {}", source.title(), why, level);
        LOWERED.inc();
        quickRestart = true;
        throw new IOException("lowering quality: " + why);
    }

    private volatile boolean quickRestart;
    /** Someone watching reported they can't keep up (0 = nobody); handled by the pipeline thread. */
    private volatile int viewerTrouble;

    /** Through the server: someone watching can't keep up (a {@code Payloads.TROUBLE_*}). Any thread. */
    public void viewerTrouble(int kind) { viewerTrouble = kind; }

    /** BusyGuard: the PC or the server is struggling — one step lighter (true if there was a step left). */
    public boolean busy(String why) {
        if (level >= MAX_LEVEL) return false;
        busyWhy = why;
        return true;
    }

    private volatile String busyWhy;

    /** GPU capture needs Windows, a real window or monitor, and a hardware encoder that takes D3D11 frames. */
    private boolean gpuEligible(Ffmpeg.Encoder enc) {
        if (gpuFailed || !"true".equals(System.getProperty("windowscraft.gpuCapture", "true"))) return false;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return false;
        if (source.kind() == SourceInfo.Kind.TEST) return false;
        if (source.kind() == SourceInfo.Kind.WINDOW && source.handle() == 0) return false;
        return enc.name().equals("h264_nvenc") || enc.name().equals("h264_amf") || enc.name().equals("h264_qsv");
    }

    private String gfxSource(CaptureSettings ps, int fps, int ew, int eh) {
        String target = source.kind() == SourceInfo.Kind.WINDOW
                ? "hwnd=" + Long.toUnsignedString(source.handle())
                : "hmonitor=" + Long.toUnsignedString(dev.windowscraft.capture.win.WinHandles.monitorFor(source));
        // frames come out at the stream's size; if the window is resized, the picture is fitted into it.
        // premultiplied=1: take the pixels exactly as Windows shows them. By default gfxcapture
        // "un-premultiplies" (divides colour by alpha); browsers leave alpha a little below 100 % in
        // places — WebGL canvases, video edges — and dividing a dark pixel by a tiny alpha turns it
        // into a white speck. The window is opaque on screen, so alpha is ignored from here on.
        return "gfxcapture=" + target + ":max_framerate=" + fps + ":capture_cursor=" + (ps.showCursor() || dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(source) ? 1 : 0)
                + ":width=" + ew + ":height=" + eh + ":resize_mode=scale_aspect:display_border=0:premultiplied=1";
    }

    /** GPU capture didn't work here: go back to the CPU capture for the rest of this share. */
    private void gpuFail(String why) throws IOException {
        if (gpuAttempt < 1) {
            gpuAttempt++;
            WindowsCraftClient.LOGGER.warn("GPU capture didn't work for '{}' ({}); trying the GPU's own converter", source.title(), why);
            pipelineBytes = 1; // already handled here
            throw new IOException("GPU capture: trying another way");
        }
        gpuFailed = true;
        WindowsCraftClient.LOGGER.warn("GPU capture unavailable for '{}' ({}); using CPU capture", source.title(), why);
        throw new IOException("GPU capture unavailable, using CPU capture");
    }

    /** GPU mode: read the BGRA copy FFmpeg sends back, for your own wall. */
    private void readPreview(ServerSocket listen, int pw, int ph, int gen) {
        try {
            listen.setSoTimeout(15_000);
            Socket sock = track(listen.accept());
            InputStream in = sock.getInputStream();
            int size = pw * ph * 4;
            byte[] buf = new byte[size];
            while (running) {
                int got = 0;
                while (got < size) {
                    int n = in.read(buf, got, size - got);
                    if (n < 0) return;
                    got += n;
                }
                Frame f = previewPool.acquire(pw, ph);
                f.data.clear();
                f.data.put(buf, 0, size).flip();
                f.alphaValid = false;
                f.captureNanos = System.nanoTime();
                Frame old = preview.getAndSet(f);
                if (old != null) previewPool.release(old);
                if (gen != pipelineGen || !running) return;
                if (!session.isExternal()) session.setExternal(true); // the CPU capture can rest
                lastPreviewNanos = f.captureNanos;
            }
        } catch (IOException ignored) {
        }
    }

    /** Writes the newest frame at a steady frame rate, repeating the last one if capture is slower. */
    private void feedVideo(Socket sock, int w, int h, int fps) {
        byte[] current = null;
        try (OutputStream out = sock.getOutputStream()) {
            long start = System.nanoTime(), written = 0;
            long interval = 1_000_000_000L / fps;
            while (running) {
                long due = (System.nanoTime() - start) / interval;
                if (due - written > fps / 2) { // fell half a second behind: skip, don't burst
                    written = due - 1;
                    videoSkips++;
                    CPU_SKIPS.inc();
                }
                while (written < due) {
                    byte[] next = latest.getAndSet(null);
                    if (next != null) {
                        if (current != null && spare.size() < 3) spare.offer(current);
                        current = next;
                    }
                    if (current == null) break;
                    if (current.length != w * h * 4) return; // size changed; the pipeline restarts
                    long f0 = dev.windowscraft.measure.Measure.ON ? System.nanoTime() : 0;
                    out.write(current);
                    FEED.since(f0);
                    written++;
                }
                LockSupport.parkNanos(start + (written + 1) * interval - System.nanoTime());
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Writes audio on a real-time clock, counted from {@code start} (where the stream's sound
     * timestamps start). Captured sound arrives in slightly uneven ~10 ms bursts, so keep a cushion
     * (~80 ms) and only ever send whole chunks: never pad a half-filled chunk with silence (that
     * cuts tiny holes into the sound, heard as crackles). The cushion is part of the sound's
     * timestamps (lip sync), so it's held steady: when the sound waiting in it drifts older or
     * younger (the sound card's clock vs. ours, a hiccup), it plays a hair faster or slower until
     * it's back (a frame or two in 480: inaudible); far off, it jumps.
     */
    private void feedAudio(Socket sock, PcmRing audio, long start) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        final int chunkFrames = 480; // 10 ms
        final int chunkBytes = chunkFrames * 4;
        final int target = AudioCapture.BYTES_PER_SECOND * CUSHION_MS / 1000;
        final int max = AudioCapture.BYTES_PER_SECOND / 4;
        final long targetAge = CUSHION_MS * 1_000_000L;
        // One chunk is held back before sending, so that whenever sound has to stop (it arrived
        // late, or we jump ahead after a stall) the chunk before the gap can fade out and the one
        // after it fade in — a gap then is a soft dip instead of a pop.
        byte[] held = new byte[chunkBytes], next = new byte[chunkBytes], in = new byte[(chunkFrames + 2) * 4];
        PcmStretch stretch = new PcmStretch();
        boolean heldSound = false, primed = false;
        long age = 0; // how old the sound going out is, smoothed (ns): the cushion, when all is well
        long noteAt = System.nanoTime();
        int hurried = 0, slowed = 0, jumps = 0, dry = 0;
        try (OutputStream out = sock.getOutputStream()) {
            // FFmpeg took a moment to start: silence for it (the sound's clock runs from start),
            // and what came in meanwhile is old now
            long written = (System.nanoTime() - start) * AudioCapture.RATE / 1_000_000_000L / chunkFrames * chunkFrames;
            for (long f = 0; f < written; f += chunkFrames) out.write(held);
            if (audio != null) audio.trimTo(0);
            while (running) {
                long due = (System.nanoTime() - start) * AudioCapture.RATE / 1_000_000_000L;
                while (written + chunkFrames <= due) {
                    int avail = audio == null ? 0 : audio.available();
                    // the chunk read now goes out after the held one: the sound's clock says it's
                    // heard at `at` — and how old its first sound was then
                    long at = start + (written + chunkFrames) * 1_000_000_000L / AudioCapture.RATE;
                    long oldest = audio == null ? 0 : audio.oldestAgeNanos(AudioCapture.BYTES_PER_SECOND) - (System.nanoTime() - at);
                    boolean jumped = false;
                    if (audio != null && (avail > max || primed && oldest > targetAge + 50_000_000L)) {
                        // way behind (e.g. after a stall): jump
                        audio.trimTo(target);
                        avail = audio.available();
                        oldest = audio.oldestAgeNanos(AudioCapture.BYTES_PER_SECOND) - (System.nanoTime() - at);
                        jumped = true;
                        jumps++;
                    }
                    boolean sound;
                    if (audio == null || (!primed && avail < target) || (primed && avail < chunkBytes)) {
                        // nothing (or not enough yet): silence, then wait for the cushion again
                        if (primed) dry++;
                        primed = false;
                        sound = false;
                        java.util.Arrays.fill(next, (byte) 0);
                    } else {
                        sound = true;
                        if (!primed || jumped) {
                            age = oldest;
                            stretch.reset();
                        } else {
                            age += (oldest - age) / 32; // (~1/3 s)
                        }
                        long off = age - targetAge;
                        int faster = off > 12_000_000L ? 2 : off > 3_000_000L ? 1 : off < -12_000_000L ? -2 : off < -3_000_000L ? -1 : 0;
                        if (faster > 0) hurried++;
                        else if (faster < 0) slowed++;
                        int n = audio.read(in, 0, Math.min(chunkFrames + faster, avail / 4) * 4) / 4;
                        stretch.stretch(in, n, next, chunkFrames);
                        if (!primed || jumped) PcmFades.fadeIn(next, chunkBytes);
                        primed = true;
                    }
                    if (heldSound && (!sound || jumped)) PcmFades.fadeOut(held, chunkBytes);
                    out.write(held);
                    byte[] t = held; held = next; next = t;
                    heldSound = sound;
                    written += chunkFrames;
                }
                if (dev.windowscraft.measure.Measure.ON && System.nanoTime() - noteAt > 10_000_000_000L) {
                    noteAt = System.nanoTime();
                    WindowsCraftClient.LOGGER.info("[audio out] the sound going out is {} ms old (cushion {} ms) · faster {}, slower {} of 1000 chunks · jumped {}, ran dry {}",
                            age / 1_000_000, CUSHION_MS, hurried, slowed, jumps, dry);
                    hurried = slowed = jumps = dry = 0;
                }
                LockSupport.parkNanos(2_000_000L);
            }
        } catch (IOException ignored) {
        }
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, "WindowsCraft " + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
