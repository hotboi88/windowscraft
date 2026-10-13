package dev.windowscraft.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live video to viewers over UDP, the way broadcast links do it (the idea behind SRT): pictures
 * go out as numbered packets at a steady pace; a viewer that misses one asks for it again
 * right away and gets it again while it can still be used; one that arrives too late is skipped
 * instead of holding everything behind it up. Over a long internet path that loses a little now
 * and then (a Tailscale link across the country), a TCP connection slows to a crawl after every
 * loss and the video backs up; this keeps full quality and a small, steady delay.
 *
 * <p>The viewer first authenticates over TCP as always (role {@code SUBU}) and gets a random
 * session id, which every packet it sends carries. Packets (big-endian):
 * <pre>
 *   relay → viewer:  DATA  1 seq:int ts:int payload (up to 6 TS packets — fits Tailscale's 1280-byte MTU)
 *                    HB    2 lastSeq:int            (nothing new to send: lets a viewer notice a lost tail)
 *                    PONG  3 time:long              (echo of a PING)
 *   viewer → relay:  HELLO 10 session:long
 *                    NAK   11 session:long n:short (from:int count:short)×n
 *                    ACK   12 session:long next:int (also the keep-alive)
 *                    PING  13 session:long time:long
 * </pre>
 */
public final class UdpLink implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("WindowsCraft relay");
    static final byte DATA = 1, HB = 2, PONG = 3, HELLO = 10, NAK = 11, ACK = 12, PING = 13;
    static final int TS_PER_PACKET = 6, PAYLOAD = 188 * TS_PER_PACKET, HEADER = 9;
    private static final int HISTORY = 8192; // ~6 s at 10 Mbit/s
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatagramSocket socket;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean open = true;

    public UdpLink(InetAddress bind, int port) throws SocketException {
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bind, port));
        try {
            socket.setSendBufferSize(4 << 20);
            socket.setReceiveBufferSize(1 << 20);
        } catch (SocketException ignored) {
        }
        Thread t = new Thread(this::receiveLoop, "WindowsCraft relay UDP");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY); // (resend requests answered at once, however busy the server)
        t.start();
    }

    public int port() { return socket.getLocalPort(); }

    /** A new viewer session; {@code behind} is told when this viewer keeps losing too much (its path can't carry the stream). */
    public Session open(Runnable behind) {
        long id;
        do id = RANDOM.nextLong(); while (id == 0 || sessions.containsKey(id));
        Session s = new Session(id, behind);
        sessions.put(id, s);
        return s;
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
                if (p.getLength() < 9) continue;
                ByteBuffer b = ByteBuffer.wrap(buf, 0, p.getLength());
                byte type = b.get();
                Session s = sessions.get(b.getLong());
                if (s == null || s.closed) continue;
                s.heard(p.getSocketAddress());
                switch (type) {
                    case HELLO -> {}
                    case ACK -> { if (b.remaining() >= 4) s.acked = b.getInt(); }
                    case PING -> {
                        if (b.remaining() >= 8) {
                            ByteBuffer r = ByteBuffer.allocate(9).put(PONG).putLong(b.getLong());
                            s.sendRaw(r.array(), 9);
                        }
                    }
                    case NAK -> {
                        if (b.remaining() < 2) continue;
                        int n = b.getShort() & 0xFFFF;
                        for (int i = 0; i < n && b.remaining() >= 6; i++) {
                            int from = b.getInt(), count = b.getShort() & 0xFFFF;
                            for (int k = 0; k < count && s.retx.size() < 4000; k++) s.retx.add(from + k);
                        }
                    }
                    default -> {}
                }
            } catch (IOException e) {
                if (!open) return;
            }
        }
    }

    @Override
    public void close() {
        open = false;
        socket.close();
        sessions.clear();
    }

    /** One viewer: numbered packets out at a steady pace, lost ones again on request. */
    public final class Session {
        public final long id;
        private final Runnable behind;
        private volatile SocketAddress peer;
        private volatile long lastHeard = System.nanoTime();
        volatile boolean closed;
        volatile int acked;
        final ConcurrentLinkedQueue<Integer> retx = new ConcurrentLinkedQueue<>();
        private final byte[][] history = new byte[HISTORY][];
        private final int[] historyLen = new int[HISTORY];
        private final long[] lastSent = new long[HISTORY];
        private int nextSeq;
        private final byte[] acc = new byte[PAYLOAD];
        private int accLen;
        // pacing: a token bucket at a little over the stream's own rate, so a keyframe doesn't
        // leave as one burst that a home router drops half of
        private double tokens = 64 * 1024, inRate = 1_250_000;
        private long refilled = System.nanoTime(), lastSendNanos = System.nanoTime(), inWindowStart = System.nanoTime(), statStart = System.nanoTime();
        private long inBytes, sentPackets, retxPackets;
        private int badSeconds;
        public long totalSent, totalRetx;

        private Session(long id, Runnable behind) {
            this.id = id;
            this.behind = behind;
        }

        void heard(SocketAddress from) {
            peer = from; // (a viewer's address can change behind a NAT: it follows)
            lastHeard = System.nanoTime();
        }

        /** Has the viewer said hello yet (its address known), and is it still there? */
        public boolean ready() { return peer != null; }
        public boolean alive() { return !closed && System.nanoTime() - lastHeard < 8_000_000_000L; }

        public void close() {
            closed = true;
            sessions.remove(id);
        }

        /** Stream bytes (whole TS packets) for this viewer. Sender thread. */
        public void write(byte[] data) throws IOException {
            inBytes += data.length;
            for (int o = 0; o < data.length; ) {
                int n = Math.min(PAYLOAD - accLen, data.length - o);
                System.arraycopy(data, o, acc, accLen, n);
                accLen += n;
                o += n;
                if (accLen == PAYLOAD) flush();
            }
        }

        /** Sends whatever is collected (the queue is empty: don't make the last bit wait). */
        public void flush() throws IOException {
            if (accLen == 0) return;
            int seq = nextSeq++;
            byte[] d = new byte[HEADER + accLen];
            ByteBuffer.wrap(d).put(DATA).putInt(seq).putInt((int) (System.currentTimeMillis() & 0x7FFFFFFF));
            System.arraycopy(acc, 0, d, HEADER, accLen);
            accLen = 0;
            int slot = Math.floorMod(seq, HISTORY);
            history[slot] = d;
            historyLen[slot] = d.length;
            lastSent[slot] = System.nanoTime();
            pace(d.length);
            sendRaw(d, d.length);
            sentPackets++;
            totalSent++;
        }

        /** Resends what the viewer asked for (if it's still kept). Sender thread. */
        public void resend() throws IOException {
            long now = System.nanoTime();
            for (Integer seq; (seq = retx.poll()) != null; ) {
                if (seq < 0 || seq >= nextSeq || nextSeq - seq > HISTORY) continue;
                int slot = Math.floorMod(seq, HISTORY);
                byte[] d = history[slot];
                if (d == null || ByteBuffer.wrap(d, 1, 4).getInt() != seq) continue;
                if (now - lastSent[slot] < 15_000_000L) continue; // just went out: an asking twice
                lastSent[slot] = now;
                pace(historyLen[slot]);
                sendRaw(d, historyLen[slot]);
                retxPackets++;
                totalRetx++;
            }
        }

        /** Nothing new for a while: tell the viewer the last number, so a lost tail is noticed. */
        public void heartbeat() throws IOException {
            if (System.nanoTime() - lastSendNanos < 100_000_000L) return;
            byte[] d = ByteBuffer.allocate(5).put(HB).putInt(nextSeq - 1).array();
            sendRaw(d, 5);
        }

        /** Once a second: input rate (for pacing) and whether this viewer keeps losing too much. */
        public void stats() {
            long now = System.nanoTime();
            if (now - inWindowStart >= 1_000_000_000L) {
                double r = inBytes / ((now - inWindowStart) / 1e9);
                inRate = inRate * 0.6 + r * 0.4;
                inBytes = 0;
                inWindowStart = now;
            }
            if (now - statStart >= 1_000_000_000L) {
                boolean bad = sentPackets > 50 && retxPackets > sentPackets * 0.2;
                badSeconds = bad ? badSeconds + 1 : 0;
                if (badSeconds >= 3) {
                    badSeconds = 0;
                    behind.run();
                }
                sentPackets = retxPackets = 0;
                statStart = now;
            }
        }

        private void pace(int len) {
            // bytes/s: a little over the stream's own rate (at least 2 Mbit/s). A home connection's
            // router keeps only a short queue: a big start-point picture sent at full speed would
            // overflow it (pieces lost, sent again, late) — spread out a little, it fits
            double rate = Math.max(256_000, inRate * 1.4);
            long now = System.nanoTime();
            tokens = Math.min(64 * 1024, tokens + (now - refilled) / 1e9 * rate);
            refilled = now;
            tokens -= len;
            if (tokens < 0) {
                long wait = (long) (-tokens / rate * 1e9);
                if (wait > 200_000) java.util.concurrent.locks.LockSupport.parkNanos(wait);
            }
        }

        void sendRaw(byte[] d, int len) {
            SocketAddress to = peer;
            if (to == null || closed) return;
            try {
                socket.send(new DatagramPacket(d, len, to));
                lastSendNanos = System.nanoTime();
            } catch (IOException e) {
                LOG.debug("UDP send failed: {}", e.toString());
            }
        }
    }
}
