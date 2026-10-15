package dev.windowscraft.relay;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Dev only: a bad internet line between the relay and its viewers, to see how watching and using
 * a screen hold up far away ({@code -Dwindowscraft.exp.netsim=rtt=100,jitter=40,loss=1,spike=250,every=12,len=2}
 * on the server). Each direction is a queue that lets packets out in order after a delay that
 * wanders (like a line that's busy now and then), with spikes (someone else's download filling
 * it) and packets lost on the way. The sharer's own connection is untouched (it's local: the
 * owner and his server are one PC). Off unless that property is set.
 *
 * <ul>
 *   <li>rtt: the round trip without trouble, ms (half each way)</li>
 *   <li>jitter: how far it wanders, ms of round trip (smooth, not packet to packet)</li>
 *   <li>loss: % of packets lost each way (doubled during a spike)</li>
 *   <li>spike, every, len: a spike adds that much round trip (ms), about every that many s, for that long (s)</li>
 * </ul>
 */
public final class NetSim {
    /** relay → viewer, and viewer → relay; null when off. */
    public static final NetSim DOWN, UP;
    static {
        String spec = System.getProperty("windowscraft.exp.netsim");
        double[] v = parse(spec);
        DOWN = v == null ? null : new NetSim("down", v, 1);
        UP = v == null ? null : new NetSim("up", v, 2);
        if (v != null) org.slf4j.LoggerFactory.getLogger("WindowsCraft relay").warn("Simulated internet line to viewers: {}", spec);
    }

    private final double halfRttMs, jitterMs, loss, spikeMs, everyS, lenS;
    private final double phase;
    private final Random random;
    private final ArrayDeque<long[]> times = new ArrayDeque<>();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private long lastRelease;
    private final long start = System.nanoTime();

    NetSim(String name, double[] v, long seed) {
        halfRttMs = v[0] / 2;
        jitterMs = v[1] / 2;
        loss = v[2] / 100;
        spikeMs = v[3] / 2;
        everyS = v[4];
        lenS = v[5];
        random = new Random(seed);
        phase = seed * 1.7;
        Thread t = new Thread(this::run, "WindowsCraft netsim " + name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** rtt, jitter, loss, spike, every, len — or null (off). */
    static double[] parse(String spec) {
        if (spec == null || spec.isBlank()) return null;
        double[] v = {100, 0, 0, 0, 15, 2};
        String[] keys = {"rtt", "jitter", "loss", "spike", "every", "len"};
        for (String part : spec.toLowerCase(Locale.ROOT).split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(kv[0].trim())) v[i] = Double.parseDouble(kv[1].trim());
        }
        return v;
    }

    /** The one-way delay now, ms: the line's own, its wandering, and a spike if one is on. */
    double delayMs(double t) {
        double wander = jitterMs * (0.5 * Math.sin(2 * Math.PI * t / 3.1 + phase) + 0.3 * Math.sin(2 * Math.PI * t / 7.3 + 1 + phase)
                + 0.2 * Math.sin(2 * Math.PI * t / 1.7 + 2 + phase));
        return Math.max(0, halfRttMs + wander + spikeMs * spike(t));
    }

    /** 0..1: how much of a spike is on at t (s); spikes come every ~{@code everyS} (±30 %), ramping up and down. */
    double spike(double t) {
        if (spikeMs <= 0 || everyS <= 0 || lenS <= 0) return 0;
        long k = (long) Math.floor(t / everyS);
        double startAt = k * everyS + everyS * 0.3 * new Random(k * 7919 + 13).nextDouble();
        double x = (t - startAt) / lenS;
        return x < 0 || x > 1 ? 0 : 0.5 - 0.5 * Math.cos(2 * Math.PI * x);
    }

    /** Lets {@code send} happen after the line's delay (in order), or never (lost: a UDP packet). Any thread. */
    public void later(Runnable send) { later(send, false); }

    /**
     * The same for something sent over TCP: it's never lost, but a piece lost on the way is sent
     * again after a timeout (at least 200 ms, or a round trip) — and everything after it waits
     * behind it (TCP keeps order).
     */
    public void laterReliable(Runnable send) { later(send, true); }

    private void later(Runnable send, boolean reliable) {
        long now = System.nanoTime();
        double t = (now - start) / 1e9;
        synchronized (this) {
            boolean lost = random.nextDouble() < loss * (1 + spike(t));
            if (lost && !reliable) return; // (lost on the way)
            double ms = delayMs(t) + (lost ? Math.max(200, 2 * delayMs(t)) : 0);
            long release = Math.max(lastRelease, now + (long) (ms * 1e6));
            lastRelease = release;
            times.addLast(new long[]{release});
            tasks.addLast(send);
            notifyAll();
        }
    }

    private void run() {
        while (true) {
            long release;
            Runnable task;
            synchronized (this) {
                while (times.isEmpty()) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                release = times.peekFirst()[0];
            }
            // (Windows' timer can be 15.6 ms coarse: sleep until close, then wait the last bit out)
            long left;
            while ((left = release - System.nanoTime()) > 0) {
                if (left > 2_000_000L) LockSupport.parkNanos(left - 1_500_000L);
                else Thread.onSpinWait();
            }
            synchronized (this) {
                times.pollFirst();
                task = tasks.pollFirst();
            }
            try {
                task.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
