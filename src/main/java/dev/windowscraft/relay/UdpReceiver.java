package dev.windowscraft.relay;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The viewer's end of {@link UdpLink}: puts the numbered packets back in order, asks again
 * for missing ones straight away (and again every round trip), and when one still hasn't come
 * once it's too late to be useful, moves on without it. Delivers the stream as an ordinary
 * {@link InputStream} of MPEG-TS bytes. Anything in order goes out immediately — only a gap
 * waits, and never longer than {@link #latencyMs()}.
 */
public final class UdpReceiver implements AutoCloseable {
    private static final byte[] EOF = new byte[0];

    private final DatagramSocket socket;
    private final long session;
    private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
    private volatile boolean open = true;
    private volatile long firstDataNanos, lastHeardNanos = System.nanoTime();

    // ordering state (receive thread only)
    private int next = -1, highest = -1;
    private final TreeMap<Integer, byte[]> pending = new TreeMap<>();
    /** missing seq → {first noticed, last asked} (nanos) */
    private final TreeMap<Integer, long[]> missing = new TreeMap<>();
    private volatile double rttMs = -1;
    private long lastPing, lastAck, lastHello;

    public volatile long received, recovered, skipped;
    /** Tests: this share of incoming picture packets is "lost on the way" (0..1). */
    public static volatile double testLoss;
    /**
     * Tests: an imitation home internet link in front of this viewer — a bottleneck of
     * {@code kbps} with a router queue of {@code queueMs} (fuller → packets dropped), a delay
     * with jitter, and a little random loss. Null: none.
     */
    public record TestLink(int kbps, int queueMs, int delayMs, int jitterMs, double loss) {}
    public static volatile TestLink testLink;
    private final java.util.ArrayDeque<Object[]> inFlight = new java.util.ArrayDeque<>();
    private long linkFree, lastDeliver;
    private boolean linkTimer;
    private final java.util.Random linkDice = new java.util.Random(11);
    private final java.util.Random lossDice = new java.util.Random(7);

    public UdpReceiver(InetSocketAddress relay, long session) throws IOException {
        this.session = session;
        socket = new DatagramSocket();
        socket.connect(relay);
        try {
            socket.setReceiveBufferSize(4 << 20);
        } catch (IOException ignored) {
        }
        socket.setSoTimeout(5);
        Thread t = new Thread(this::loop, "WindowsCraft viewer UDP");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** Has any picture data arrived yet? */
    public boolean flowing() { return firstDataNanos != 0; }

    /** Nanos since the relay was last heard from (data, heartbeat or pong). */
    public long silentNanos() { return System.nanoTime() - lastHeardNanos; }

    public double rttMs() { return rttMs; }

    // ---- measurements: how far this PC's clock is from the relay's (off by default) ----
    private static final dev.windowscraft.measure.Measure.Metric NET_DELAY = dev.windowscraft.measure.Measure.ms("view.net_delay",
            "relay → this viewer, one way, per UDP packet (clocks lined up from the round trip)");
    private static final dev.windowscraft.measure.Measure.Metric RTT = dev.windowscraft.measure.Measure.ms("view.round_trip",
            "round trip to the relay (UDP ping)");
    private volatile long minDeltaMs = Long.MAX_VALUE;

    /**
     * This PC's clock minus the relay's, in ms (Long.MIN_VALUE until known): the smallest
     * "received − sent" seen, less half the round trip.
     */
    public long clockOffsetMs() {
        long m = minDeltaMs;
        double r = rttMs;
        return m == Long.MAX_VALUE || r < 0 ? Long.MIN_VALUE : m - Math.round(r / 2);
    }

    private void clock(int sentAt) {
        long now = System.currentTimeMillis() & 0x7FFFFFFF;
        long d = now - sentAt;
        if (d > 0x3FFFFFFF) d -= 0x80000000L;
        else if (d < -0x3FFFFFFF) d += 0x80000000L;
        if (d < minDeltaMs) minDeltaMs = d;
        long off = clockOffsetMs();
        if (off != Long.MIN_VALUE) NET_DELAY.record(Math.max(0, d - off) * 1_000_000L);
        if (rttMs >= 0 && (received & 63) == 0) RTT.record(Math.round(rttMs * 1e6));
    }

    private static final boolean OLD_GIVE_UP = Boolean.getBoolean("windowscraft.exp.oldGiveUp");

    /** How long a gap may hold the stream up before it's skipped. */
    public long latencyMs() {
        // time for a lost piece to be asked for and sent again — twice, on a line whose delay
        // swings — before giving up on it (a piece given up on costs a held picture until the next
        // keyframe: worth a little waiting; over a simulated 100 ms line that swings, 2.5 round
        // trips + 70 ms gave up on pieces that were on their way)
        double r = rttMs < 0 ? 150 : rttMs;
        if (OLD_GIVE_UP) return (long) Math.max(140, Math.min(1000, r * 2.5 + 70)); // (dev: as up to 0.15.10, to compare)
        return (long) Math.max(160, Math.min(1200, r * 3 + 120));
    }

    /** The stream, in order. Ends when this receiver is closed. */
    public InputStream stream() {
        return new InputStream() {
            private byte[] cur;
            private int pos;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                while (cur == null || pos >= cur.length) {
                    try {
                        cur = out.take();
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                    pos = 0;
                    if (cur == EOF) {
                        out.offer(EOF);
                        return -1;
                    }
                }
                int n = Math.min(len, cur.length - pos);
                System.arraycopy(cur, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    private void loop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            long now = System.nanoTime();
            try {
                if (firstDataNanos == 0 && now - lastHello > 200_000_000L) {
                    lastHello = now;
                    send(ByteBuffer.allocate(9).put(UdpLink.HELLO).putLong(session));
                }
                if (now - lastPing > 500_000_000L) {
                    lastPing = now;
                    send(ByteBuffer.allocate(17).put(UdpLink.PING).putLong(session).putLong(now));
                }
                if (now - lastAck > 100_000_000L) {
                    lastAck = now;
                    send(ByteBuffer.allocate(13).put(UdpLink.ACK).putLong(session).putInt(next));
                }
                p.setLength(buf.length);
                TestLink link = testLink;
                if ((link != null) != linkTimer) {
                    linkTimer = link != null;
                    socket.setSoTimeout(linkTimer ? 1 : 5);
                }
                try {
                    socket.receive(p);
                    if (link == null) handle(buf, p.getLength());
                    else through(link, buf, p.getLength());
                } catch (SocketTimeoutException ignored) {
                }
                if (link != null || !inFlight.isEmpty()) arrived();
                advance();
                askAgain();
            } catch (IOException e) {
                if (!open) break;
            }
        }
        out.offer(EOF);
    }

    /** (tests) a packet on its way over the imitation link */
    private void through(TestLink link, byte[] buf, int len) {
        long now = System.nanoTime();
        if (linkDice.nextDouble() < link.loss()) return;
        long tx = (long) (len + 28) * 8 * 1_000_000_000L / (link.kbps() * 1000L);
        long start = Math.max(now, linkFree);
        if (start - now > link.queueMs() * 1_000_000L) return; // the router's queue is full: dropped
        linkFree = start + tx;
        long at = linkFree + link.delayMs() * 1_000_000L + (long) (linkDice.nextDouble() * link.jitterMs() * 1_000_000L);
        at = Math.max(at, lastDeliver); // (one path: packets stay in order)
        lastDeliver = at;
        inFlight.add(new Object[]{at, java.util.Arrays.copyOf(buf, len)});
    }

    private void arrived() {
        long now = System.nanoTime();
        while (!inFlight.isEmpty() && (long) inFlight.peek()[0] <= now) {
            byte[] d = (byte[]) inFlight.poll()[1];
            handle(d, d.length);
        }
    }

    private void handle(byte[] buf, int len) {
        if (len < 5) return;
        lastHeardNanos = System.nanoTime();
        ByteBuffer b = ByteBuffer.wrap(buf, 0, len);
        byte type = b.get();
        switch (type) {
            case UdpLink.DATA -> {
                if (len < UdpLink.HEADER) return;
                if (testLoss > 0 && lossDice.nextDouble() < testLoss) return;
                int seq = b.getInt();
                int sentAt = b.getInt(); // the relay's clock (ms) when it sent this
                received++;
                if (dev.windowscraft.measure.Measure.ON) clock(sentAt);
                if (firstDataNanos == 0) firstDataNanos = System.nanoTime();
                if (next < 0) next = seq;
                if (seq < next || pending.containsKey(seq)) return; // late or twice
                byte[] payload = new byte[len - UdpLink.HEADER];
                System.arraycopy(buf, UdpLink.HEADER, payload, 0, payload.length);
                pending.put(seq, payload);
                if (missing.remove(seq) != null) recovered++;
                noteUpTo(seq);
            }
            case UdpLink.HB -> {
                if (next >= 0) noteUpTo(b.getInt());
            }
            case UdpLink.PONG -> {
                if (len >= 9) {
                    double r = (System.nanoTime() - b.getLong()) / 1e6;
                    rttMs = rttMs < 0 ? r : rttMs * 0.8 + r * 0.2;
                }
            }
            default -> {}
        }
    }

    /** Everything up to {@code seq} exists: whatever of it isn't here is missing. */
    private void noteUpTo(int seq) {
        if (seq <= highest) return;
        long now = System.nanoTime();
        int from = Math.max(highest + 1, next);
        if (seq - from > 4000) {
            // a huge jump (the relay skipped us ahead, or we were away): start over from here
            pending.headMap(seq).clear();
            missing.clear();
            next = seq;
            from = seq;
        }
        for (int s = from; s < seq; s++) if (!pending.containsKey(s)) missing.put(s, new long[]{now, 0});
        highest = seq;
    }

    /** Hands on everything that's in order; skips a gap that has waited too long. */
    private void advance() {
        if (next < 0) return;
        long now = System.nanoTime(), late = latencyMs() * 1_000_000L;
        while (true) {
            byte[] d = pending.remove(next);
            if (d != null) {
                out.offer(d);
                next++;
                continue;
            }
            long[] m = missing.get(next);
            if (m != null && now - m[0] > late) {
                missing.remove(next); // too late to be of use: on without it
                skipped++;
                next++;
                continue;
            }
            break;
        }
        // (gaps behind the delivery point can't matter any more)
        if (!missing.isEmpty() && missing.firstKey() < next) missing.headMap(next).clear();
    }

    /** Asks for missing packets: at once, then again every round trip until they come or it's too late. */
    private void askAgain() throws IOException {
        if (missing.isEmpty()) return;
        long now = System.nanoTime();
        long every = (long) (Math.max(20, rttMs < 0 ? 100 : rttMs * 1.1) * 1_000_000L);
        List<int[]> ranges = new ArrayList<>();
        int[] cur = null;
        for (Map.Entry<Integer, long[]> e : missing.entrySet()) {
            long[] m = e.getValue();
            if (now - m[1] < every) continue;
            m[1] = now;
            int s = e.getKey();
            if (cur != null && cur[0] + cur[1] == s && cur[1] < 0xFFFF) cur[1]++;
            else {
                if (ranges.size() >= 150) break;
                cur = new int[]{s, 1};
                ranges.add(cur);
            }
        }
        if (ranges.isEmpty()) return;
        ByteBuffer b = ByteBuffer.allocate(11 + ranges.size() * 6).put(UdpLink.NAK).putLong(session).putShort((short) ranges.size());
        for (int[] r : ranges) b.putInt(r[0]).putShort((short) r[1]);
        send(b);
    }

    private void send(ByteBuffer b) throws IOException {
        socket.send(new DatagramPacket(b.array(), b.position()));
    }

    @Override
    public void close() {
        open = false;
        socket.close();
        out.offer(EOF);
    }
}
