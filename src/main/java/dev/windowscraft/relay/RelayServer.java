package dev.windowscraft.relay;

import dev.windowscraft.measure.Measure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fan-out relay that runs inside the Minecraft server. Each live screen is encoded once by its
 * owner and pushed here; every viewer pulls the same bytes. No decoding or re-encoding happens on
 * the server, so its cost is just network copies.
 *
 * <p>Only players currently on the server can connect (per-player tokens handed out over the
 * Minecraft connection), and only the owner of a live screen can publish its stream. Viewers that
 * fall a whole keyframe interval behind are skipped forward instead of drifting into the past.
 */
public final class RelayServer implements AutoCloseable {
    private static final int PUBLISHER_SILENCE_MS = 30_000;
    private static final Logger LOG = LoggerFactory.getLogger("WindowsCraft relay");
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_CONNECTIONS = 64, MAX_PER_ADDRESS = 16;
    private static final long SUBSCRIBER_BYTE_BUDGET = 12L << 20; // never hold more than 12 MB per viewer
    private static final byte[] POISON = new byte[0];

    /**
     * A run of whole 188-byte TS packets; {@code keyframe} if a viewer can start decoding here;
     * {@code madeNanos}: when it was cut (only while measuring, else 0).
     */
    public record Chunk(byte[] data, boolean keyframe, long madeNanos) {
        public Chunk(byte[] data, boolean keyframe) { this(data, keyframe, Measure.ON ? System.nanoTime() : 0); }
    }

    // measurements (off by default)
    private static final Measure.Metric IN_AGE = Measure.ms("relay.in_age",
            "a picture reaching the relay: how long since it was captured (GPU-captured streams; exact when the server runs on the sharer's PC)");
    private static final Measure.Metric QUEUE_WAIT = Measure.ms("relay.queue_wait",
            "a piece of a stream waiting in the relay for one viewer (TCP or UDP) before it's sent");
    private static final Measure.Metric QUEUE_KB = Measure.value("relay.queue_kb", "KB", 1,
            "what was waiting for a viewer when a piece was sent");
    private static final Measure.Counter SKIPS = Measure.counter("relay.viewer_skips", "a viewer skipped ahead to the next keyframe (fell a whole interval behind)");
    private static final Measure.Counter DROPS = Measure.counter("relay.viewer_drops", "a viewer's backlog thrown away (over 12 MB)");
    private static final Measure.Counter BYTES_IN = Measure.counter("relay.bytes_in", "bytes received from sharers");
    private static final Measure.Counter BYTES_OUT = Measure.counter("relay.bytes_out", "bytes handed to viewers");

    private static final Chunk POISON_CHUNK = new Chunk(POISON, false);

    private final ServerSocket socket;
    private final Function<String, UUID> tokenOwner;
    private final BiPredicate<UUID, String> mayPublish;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private final Map<String, Set<Socket>> byToken = new ConcurrentHashMap<>();
    private final Map<InetAddress, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean open = true;
    private volatile java.util.function.Consumer<String> viewerBehind = id -> {};

    /** Called (on a relay thread) with the stream id whenever a viewer had to be skipped ahead. */
    public void onViewerBehind(java.util.function.Consumer<String> c) { viewerBehind = c; }

    /**
     * Someone's input for a screen, carried by the relay instead of the game server: the server
     * handles it on its main thread, between ticks (a busy modded server: up to a whole tick
     * later); the relay passes it on at once. The game server says who may use what.
     */
    public interface ControlRoute {
        /** May this player use that screen, and is it the screen of this stream? (any thread) */
        boolean allowed(UUID player, UUID displayId, String streamId);
        /** The owner's game can't take input from the relay (older): the game server's way instead. */
        void viaServer(UUID player, UUID displayId, int op, float x, float y, int a, float f);
    }

    private volatile ControlRoute control;

    /** Turns input over the relay on (viewers are told when they connect). */
    public void onControl(ControlRoute r) { control = r; }

    private static final Measure.Counter CONTROL_RELAYED = Measure.counter("input.relay_passed",
            "input carried by the relay straight to the screen's owner (not through the server's main thread)");
    private static final Measure.Counter CONTROL_VIA_SERVER = Measure.counter("input.relay_via_server",
            "input that came over the relay but went on through the server (the owner's game is older)");
    private final Map<UUID, long[]> controlRate = new ConcurrentHashMap<>();

    private static final class Stream {
        final CopyOnWriteArrayList<Subscriber> subs = new CopyOnWriteArrayList<>();
        volatile Socket publisher;
        volatile long bytesIn;
        /** When its publisher went (0: it's there); it may come back within a few seconds. */
        volatile long goneSince;
        /** Ended for good (the screen was turned off): no waiting for it to come back. */
        volatile boolean ended;
        /** A publisher that wants to know how many are watching (PUB2): where to tell it. */
        volatile OutputStream tellViewers;
        /** A publisher that takes its viewers' input from here (PUB3): where to send it. */
        volatile OutputStream takesControl;
        volatile int toldViewers = -1;
        volatile long toldAt;

        /** Gone, and not coming back (or not within the grace time)? */
        boolean over() {
            if (publisher != null) return false;
            return ended || goneSince == 0 || System.nanoTime() - goneSince > GRACE_NANOS;
        }
    }

    /**
     * A publisher that reconnects (its stream turned down a step, its encoder restarted) within
     * this long finds its viewers still there: they see the picture hold for a moment instead of
     * "Reconnecting…".
     */
    private static final long GRACE_NANOS = 8_000_000_000L;

    private static final class Subscriber {
        final Socket socket;
        final BlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(4096);
        final AtomicLong queuedBytes = new AtomicLong();
        final AtomicInteger keyframesQueued = new AtomicInteger(); // > 0: at least one GOP behind
        volatile boolean started;                                  // has received its first keyframe
        long skips;
        final long connectedNanos = System.nanoTime();
        long lastSkipNanos;
        volatile boolean udp;
        volatile long lastTakenNanos = System.nanoTime();
        Subscriber(Socket socket) { this.socket = socket; }

        /**
         * Skipped ahead: is that real trouble? Not while its player is still starting (the first
         * seconds), and not a one-off — only when it happens again within half a minute.
         */
        boolean skippedForReal() {
            long now = System.nanoTime();
            boolean again = lastSkipNanos != 0 && now - lastSkipNanos < 30_000_000_000L;
            lastSkipNanos = now;
            return again && now - connectedNanos > 10_000_000_000L;
        }

        void clear() {
            queue.clear();
            queuedBytes.set(0);
            keyframesQueued.set(0);
        }
    }

    /**
     * @param tokenOwner maps a relay token to the player it belongs to (null = not a player here)
     * @param mayPublish whether that player owns a live screen with that stream id
     */
    public RelayServer(InetAddress bind, int port, Function<String, UUID> tokenOwner, BiPredicate<UUID, String> mayPublish) throws IOException {
        this.tokenOwner = tokenOwner;
        this.mayPublish = mayPublish;
        this.socket = new ServerSocket();
        this.socket.setReuseAddress(true);
        this.socket.bind(new InetSocketAddress(bind, port));
        // the same port over UDP: live video the way broadcast links send it (see UdpLink)
        UdpLink u = null;
        try {
            u = new UdpLink(bind, socket.getLocalPort());
        } catch (IOException e) {
            LOG.warn("Relay UDP port {} unavailable ({}); viewers use TCP", socket.getLocalPort(), e.toString());
        }
        udp = u;
        threads.submit(this::acceptLoop);
        threads.submit(this::viewerCounts);
        LOG.info("Relay listening on {}:{}", bind.getHostAddress(), port);
    }

    public int port() { return socket.getLocalPort(); }

    /**
     * Tells each publisher that asked (PUB2) how many are watching its stream — when it changes,
     * and every few seconds — so a screen nobody watches costs its owner's PC and upload nothing,
     * and the owner's upload is shared among the screens people do watch.
     */
    private void viewerCounts() {
        while (!socket.isClosed()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            for (Stream st : streams.values()) {
                OutputStream o = st.tellViewers;
                if (o == null) continue;
                int n = st.subs.size();
                if (n == st.toldViewers && now - st.toldAt < 3_000_000_000L) continue;
                st.toldViewers = n;
                st.toldAt = now;
                try {
                    synchronized (o) { RelayProtocol.writeLine(o, "VIEWERS " + n); }
                } catch (IOException e) {
                    st.tellViewers = null;
                }
            }
        }
    }

    private final UdpLink udp;

    /** The UDP port viewers can use (0: none, TCP only). */
    public int udpPort() { return udp == null ? 0 : udp.port(); }

    /** Drops every connection a player made (they left or were kicked). */
    public void closeToken(String token) {
        Set<Socket> set = byToken.remove(token);
        if (set != null) for (Socket s : set) closeQuietly(s);
    }

    /** Ends a stream for everyone (its screen stopped or its owner left). */
    public void closeStream(String streamId) {
        Stream st = streams.get(streamId);
        if (st == null) return;
        st.ended = true;
        if (st.publisher != null) closeQuietly(st.publisher);
        else endNow(st, streamId);
    }

    private void endNow(Stream st, String streamId) {
        for (Subscriber sub : st.subs) { sub.clear(); sub.queue.offer(POISON_CHUNK); }
        st.subs.clear();
        streams.remove(streamId, st);
    }

    private void acceptLoop() {
        while (open) {
            try {
                Socket s = socket.accept();
                InetAddress from = s.getInetAddress();
                AtomicInteger n = perAddress.computeIfAbsent(from, k -> new AtomicInteger());
                if (connections.get() >= MAX_CONNECTIONS || n.get() >= MAX_PER_ADDRESS) {
                    closeQuietly(s);
                    continue;
                }
                connections.incrementAndGet();
                n.incrementAndGet();
                threads.submit(() -> {
                    try {
                        handle(s);
                    } finally {
                        connections.decrementAndGet();
                        n.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (!open) return;
                LOG.warn("Relay accept failed: {}", e.toString());
                try { Thread.sleep(250); } catch (InterruptedException ie) { return; } // e.g. out of file handles
            }
        }
    }

    private void handle(Socket s) {
        String token = null;
        try (s) {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(5_000); // handshake must be quick
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String[] parts = RelayProtocol.readLine(in, 200).trim().split(" ");
            if (parts.length != 4 || !parts[0].equals(RelayProtocol.MAGIC)) {
                RelayProtocol.writeLine(out, "ERR bad handshake");
                return;
            }
            token = parts[2];
            UUID player = token.isBlank() ? null : tokenOwner.apply(token);
            if (player == null) {
                RelayProtocol.writeLine(out, "ERR not on this server");
                return;
            }
            String streamId = parts[3];
            if (!streamId.matches("[0-9a-f]{32}")) {
                RelayProtocol.writeLine(out, "ERR bad stream id");
                return;
            }
            byToken.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(s);
            switch (parts[1]) {
                case "PUB", "PUB2", "PUB3" -> {
                    if (!mayPublish.test(player, streamId)) {
                        RelayProtocol.writeLine(out, "ERR not your screen");
                        return;
                    }
                    boolean tell = !parts[1].equals("PUB"), input = parts[1].equals("PUB3");
                    onOwnThread("relay in " + streamId.substring(0, 8), () -> publish(s, in, out, streamId, tell, input));
                }
                case "SUB" -> subscribe(s, out, streamId);
                case "SUBU" -> {
                    if (udp == null) RelayProtocol.writeLine(out, "ERR no UDP here");
                    else {
                        UUID viewer = player;
                        onOwnThread("relay out " + streamId.substring(0, 8), () -> subscribeUdp(s, in, out, streamId, viewer));
                    }
                }
                default -> RelayProtocol.writeLine(out, "ERR bad role");
            }
        } catch (IOException e) {
            // normal when either side disconnects
        } finally {
            if (token != null) {
                Set<Socket> set = byToken.get(token);
                if (set != null) set.remove(s);
            }
        }
    }

    private void publish(Socket s, InputStream in, OutputStream out, String streamId, boolean tell, boolean input) throws IOException {
        Stream st = streams.computeIfAbsent(streamId, k -> new Stream());
        Socket previous = st.publisher;
        st.publisher = s;
        st.goneSince = 0;
        st.ended = false;
        // viewers still here from before (a restart): each picks up again at the next clean start point
        for (Subscriber sub : st.subs) { sub.clear(); sub.started = false; }
        if (previous != null) closeQuietly(previous); // the owner reconnecting replaces the old connection
        RelayProtocol.writeLine(out, "OK");
        st.toldViewers = -1;
        st.tellViewers = tell ? out : null;
        st.takesControl = input ? out : null;
        // the owner's game keeps it alive (sound, pictures, or an empty packet every second); this
        // long without anything at all and it's gone
        s.setSoTimeout(PUBLISHER_SILENCE_MS);
        LOG.info("Stream {} publishing from {}", streamId.substring(0, 8), s.getRemoteSocketAddress());
        TsSplitter splitter = new TsSplitter();
        PtsWatch pts = new PtsWatch();
        java.util.function.LongConsumer onPts = p -> {
            long age = PtsWatch.ageNanos(p, System.currentTimeMillis());
            if (age != Long.MIN_VALUE) IN_AGE.record(age);
        };
        byte[] buf = new byte[CHUNK];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                st.bytesIn += n;
                if (Measure.ON) {
                    BYTES_IN.add(n);
                    pts.feed(buf, 0, n, onPts);
                }
                for (Chunk c : splitter.feed(buf, n)) deliver(st, c, streamId);
            }
        } catch (java.net.SocketTimeoutException e) {
            LOG.info("Stream {}: nothing from its owner for {} s, letting it go", streamId.substring(0, 8), PUBLISHER_SILENCE_MS / 1000);
        } catch (RuntimeException e) {
            LOG.warn("Stream {}: relaying failed ({})", streamId.substring(0, 8), e.toString());
        } finally {
            if (st.publisher == s) {
                st.publisher = null;
                st.tellViewers = null;
                st.takesControl = null;
                if (st.ended) endNow(st, streamId);
                else {
                    // it may be back in a moment (a restart): viewers wait a few seconds for it
                    st.goneSince = System.nanoTime();
                    threads.submit(() -> {
                        try { Thread.sleep(GRACE_NANOS / 1_000_000 + 200); } catch (InterruptedException e) { return; }
                        if (st.publisher == null && st.over()) endNow(st, streamId);
                    });
                }
            }
            LOG.info("Stream {} ended ({} MB relayed in)", streamId.substring(0, 8), st.bytesIn / 1_000_000);
        }
    }

    /**
     * Hands a chunk to every viewer. A viewer that is already a whole keyframe interval behind
     * when the next keyframe arrives is skipped forward to it: it misses a moment of video
     * instead of drifting further and further into the past.
     */
    private void deliver(Stream st, Chunk c, String streamId) {
        for (Subscriber sub : st.subs) {
            if (!sub.started) {
                if (!c.keyframe()) continue; // start everyone on a clean keyframe
                sub.started = true;
            }
            // (over UDP the viewer has its own way to catch up: only skip it ahead when it's two
            // whole start points behind)
            if (c.keyframe() && sub.keyframesQueued.get() > (sub.udp ? 1 : 0)) {
                long held = sub.queuedBytes.get();
                sub.clear();
                SKIPS.inc();
                if (++sub.skips % 10 == 1) LOG.info("Viewer {} can't keep up with {}, skipping ahead (x{}; {} KB waiting, last sent {} ms ago)", sub.socket.getRemoteSocketAddress(),
                        streamId.substring(0, 8), sub.skips, held / 1024, (System.nanoTime() - sub.lastTakenNanos) / 1_000_000L);
                if (sub.skippedForReal()) viewerBehind.accept(streamId);
            }
            if (sub.queuedBytes.get() + c.data().length > SUBSCRIBER_BYTE_BUDGET || !sub.queue.offer(c)) {
                sub.clear(); // pathological backlog: drop everything, restart at the next keyframe
                sub.started = false;
                DROPS.inc();
                if (sub.skippedForReal()) viewerBehind.accept(streamId);
                continue;
            }
            sub.queuedBytes.addAndGet(c.data().length);
            if (c.keyframe()) sub.keyframesQueued.incrementAndGet();
        }
    }

    private void subscribe(Socket s, OutputStream out, String streamId) throws IOException {
        // the publisher may connect a moment after viewers learn about the stream
        Stream st = null;
        for (int i = 0; i < 100 && open; i++) {
            st = streams.get(streamId);
            if (st != null && st.publisher != null) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        if (st == null || st.publisher == null) {
            RelayProtocol.writeLine(out, "ERR stream not live");
            return;
        }
        // Keep what waits in the network layer small (Windows and Linux would otherwise let several
        // seconds of video queue up there, out of sight): a slow viewer then shows up in our own
        // queue, gets skipped ahead, and stays live.
        try { s.setSendBufferSize(512 * 1024); } catch (IOException ignored) {}
        Subscriber sub = new Subscriber(s);
        st.subs.add(sub);
        RelayProtocol.writeLine(out, "OK");
        s.setSoTimeout(0);
        long idleSince = System.nanoTime();
        try {
            while (open) {
                Chunk chunk = sub.queue.poll(2, TimeUnit.SECONDS);
                if (chunk == null) {
                    // nothing for 20 s: the stream is gone even if nobody told us
                    if (st.over() || System.nanoTime() - idleSince > 20_000_000_000L) break;
                    continue;
                }
                if (chunk == POISON_CHUNK) break;
                idleSince = System.nanoTime();
                sub.lastTakenNanos = idleSince;
                sub.queuedBytes.addAndGet(-chunk.data().length);
                if (chunk.keyframe()) sub.keyframesQueued.updateAndGet(k -> Math.max(0, k - 1));
                if (Measure.ON) taken(chunk, sub);
                out.write(chunk.data());
            }
        } catch (InterruptedException ignored) {
        } finally {
            st.subs.remove(sub);
        }
    }

    /**
     * A viewer over UDP: the TCP connection stays open as the control line (it ends when either
     * side goes); the pictures go as numbered UDP packets, lost ones again on request.
     */
    private void subscribeUdp(Socket s, InputStream in, OutputStream out, String streamId, UUID viewer) throws IOException {
        Stream st = null;
        for (int i = 0; i < 100 && open; i++) {
            st = streams.get(streamId);
            if (st != null && st.publisher != null) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        if (st == null || st.publisher == null) {
            RelayProtocol.writeLine(out, "ERR stream not live");
            return;
        }
        Subscriber sub = new Subscriber(s);
        sub.udp = true;
        UdpLink.Session ses = udp.open(() -> {
            LOG.info("Viewer {} keeps losing much of {} over UDP", s.getRemoteSocketAddress(), streamId.substring(0, 8));
            viewerBehind.accept(streamId);
        });
        st.subs.add(sub);
        // "C": this relay takes the viewer's input on the control line (older viewers ignore it)
        RelayProtocol.writeLine(out, "OK " + Long.toHexString(ses.id) + " " + udp.port() + (control != null ? " C" : ""));
        s.setSoTimeout(0);
        // the control line: the viewer's input comes up it; when the viewer closes it, it's gone
        Stream stream = st;
        Thread control = Thread.ofVirtual().start(() -> {
            try {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    if (c != '\n') {
                        if (line.length() < 256) line.append((char) c);
                        continue;
                    }
                    controlLine(viewer, streamId, stream, line.toString());
                    line.setLength(0);
                }
            } catch (IOException ignored) {
            }
            ses.close();
        });
        long idleSince = System.nanoTime(), waitHello = System.nanoTime();
        try {
            while (open && !ses.closed) {
                if (!ses.ready()) {
                    if (System.nanoTime() - waitHello > 10_000_000_000L) break; // its UDP never got through
                    Thread.sleep(20);
                    continue;
                }
                if (!ses.alive()) break;
                Chunk chunk = sub.queue.poll(5, TimeUnit.MILLISECONDS);
                ses.resend();
                if (chunk == null) {
                    ses.flush();
                    ses.heartbeat();
                    ses.stats();
                    if (st.over() || System.nanoTime() - idleSince > 20_000_000_000L) break;
                    continue;
                }
                if (chunk == POISON_CHUNK) break;
                idleSince = System.nanoTime();
                sub.lastTakenNanos = idleSince;
                sub.queuedBytes.addAndGet(-chunk.data().length);
                if (chunk.keyframe()) sub.keyframesQueued.updateAndGet(k -> Math.max(0, k - 1));
                if (Measure.ON) taken(chunk, sub);
                ses.write(chunk.data());
                if (sub.queue.isEmpty()) ses.flush();
                ses.stats();
            }
        } catch (InterruptedException ignored) {
        } finally {
            st.subs.remove(sub);
            ses.close();
            control.interrupt();
            LOG.info("UDP viewer {} of {} left: {} packets, {} sent again", s.getRemoteSocketAddress(), streamId.substring(0, 8), ses.totalSent, ses.totalRetx);
        }
    }

    /**
     * One line of a viewer's input: {@code C <displayId> <op> <x> <y> <a> <f>}. Passed to the
     * screen's owner straight away if their game takes it here, else through the game server.
     * At most 300 a second per viewer, except letting go of a button or key (never dropped: it
     * would stay held on the screen).
     */
    private void controlLine(UUID viewer, String streamId, Stream st, String line) {
        NetSim sim = NetSim.UP;
        if (sim != null) { // (dev: their input over a bad line)
            sim.laterReliable(() -> controlLineNow(viewer, streamId, st, line)); // (TCP: late, never lost)
            return;
        }
        controlLineNow(viewer, streamId, st, line);
    }

    private void controlLineNow(UUID viewer, String streamId, Stream st, String line) {
        ControlRoute route = control;
        String[] p = line.trim().split(" ");
        if (route == null || p.length != 7 || !p[0].equals("C")) return;
        try {
            UUID display = UUID.fromString(p[1]);
            int op = Integer.parseInt(p[2]), a = Integer.parseInt(p[5]);
            float x = Float.parseFloat(p[3]), y = Float.parseFloat(p[4]), f = Float.parseFloat(p[6]);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(f)) return;
            boolean release = op == dev.windowscraft.net.Payloads.OP_BUTTON_UP || op == dev.windowscraft.net.Payloads.OP_KEY_UP;
            long[] r = controlRate.computeIfAbsent(viewer, k -> new long[2]);
            synchronized (r) {
                long sec = System.nanoTime() / 1_000_000_000L;
                if (r[0] != sec) { r[0] = sec; r[1] = 0; }
                if (++r[1] > 300 && !release) return;
            }
            if (!route.allowed(viewer, display, streamId)) return;
            OutputStream o = st.takesControl;
            if (o != null) {
                try {
                    synchronized (o) { RelayProtocol.writeLine(o, "C " + viewer + " " + display + " " + op + " " + x + " " + y + " " + a + " " + f); }
                    CONTROL_RELAYED.inc();
                    return;
                } catch (IOException e) {
                    st.takesControl = null; // (its connection is going: the game server's way)
                }
            }
            CONTROL_VIA_SERVER.inc();
            route.viaServer(viewer, display, op, x, y, a, f);
        } catch (RuntimeException ignored) {
            // (a malformed line: dropped)
        }
    }

    private static void taken(Chunk chunk, Subscriber sub) {
        QUEUE_WAIT.since(chunk.madeNanos());
        QUEUE_KB.record(sub.queuedBytes.get() >> 10);
        BYTES_OUT.add(chunk.data().length);
    }

    interface IoTask { void run() throws IOException; }

    /**
     * The live picture's own path through the relay runs on a thread of its own, first in line
     * for the processor: the relay lives in the server, and a busy server (lots of players,
     * mods, world generation) must never hold the picture up — a hold-up of a second means the
     * viewer skips ahead, and that's a frozen moment on their screen.
     */
    private static void onOwnThread(String name, IoTask task) throws IOException {
        IOException[] failed = {null};
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (IOException e) {
                failed[0] = e;
            }
        }, "WindowsCraft " + name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            t.interrupt();
        }
        if (failed[0] != null) throw failed[0];
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        open = false;
        if (udp != null) udp.close();
        closeQuietly0(socket);
        for (Stream st : streams.values()) {
            if (st.publisher != null) closeQuietly(st.publisher);
            for (Subscriber sub : st.subs) closeQuietly(sub.socket);
        }
        streams.clear();
        threads.shutdownNow();
    }

    private static void closeQuietly0(ServerSocket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
