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

    /** How long a gap may hold the stream up before it's skipped. */
    public long latencyMs() {
        // time for a lost piece to be asked for and sent again — twice — before giving up on it
        // (a piece given up on costs a frozen picture until the next keyframe: worth a little delay)
        double r = rttMs < 0 ? 150 : rttMs;
        return (long) Math.max(140, Math.min(1000, r * 2.5 + 70));
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
                try {
                    socket.receive(p);
                    handle(buf, p.getLength());
                } catch (SocketTimeoutException ignored) {
                }
                advance();
                askAgain();
            } catch (IOException e) {
                if (!open) break;
            }
        }
        out.offer(EOF);
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
                b.getInt(); // (sender time: unused)
                received++;
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
