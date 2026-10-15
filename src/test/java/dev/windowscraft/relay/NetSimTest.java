package dev.windowscraft.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NetSimTest {
    @Test
    void offUnlessAsked() {
        assertNull(NetSim.parse(null));
        assertNull(NetSim.parse(" "));
        double[] v = NetSim.parse("rtt=120, loss=2");
        assertEquals(120, v[0]);
        assertEquals(2, v[2]);
        assertEquals(0, v[1]); // (no jitter unless asked)
    }

    @Test
    void packetsComeOutInOrderAfterTheDelayAndSomeAreLost() throws Exception {
        NetSim sim = new NetSim("test", NetSim.parse("rtt=40,jitter=20,loss=10"), 5);
        int n = 400;
        List<Integer> got = Collections.synchronizedList(new ArrayList<>());
        List<Long> late = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch quiet = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            int k = i;
            long sent = System.nanoTime();
            sim.later(() -> { got.add(k); late.add(System.nanoTime() - sent); });
            Thread.sleep(0, 200_000);
        }
        quiet.await(400, TimeUnit.MILLISECONDS);
        List<Integer> sorted = new ArrayList<>(got);
        Collections.sort(sorted);
        assertEquals(sorted, got, "in order, like a real line's queue");
        assertTrue(got.size() > n * 0.8 && got.size() < n * 0.97, "about a tenth lost: " + got.size());
        double avg = late.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        assertTrue(avg > 12 && avg < 40, "about half the round trip each way (±jitter): " + avg + " ms");
    }

    @Test
    void overTcpNothingIsLostOnlyLate() throws Exception {
        NetSim sim = new NetSim("tcp", NetSim.parse("rtt=20,loss=30"), 3);
        int n = 60;
        List<Integer> got = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            int k = i;
            sim.laterReliable(() -> got.add(k));
        }
        long end = System.nanoTime() + 3_000_000_000L;
        while (got.size() < n && System.nanoTime() < end) Thread.sleep(20);
        assertEquals(n, got.size(), "every line arrives");
        List<Integer> sorted = new ArrayList<>(got);
        Collections.sort(sorted);
        assertEquals(sorted, got, "in order");
    }

    @Test
    void spikesComeAndGo() {
        NetSim sim = new NetSim("spikes", NetSim.parse("rtt=100,spike=300,every=10,len=2"), 9);
        int on = 0;
        for (double t = 0; t < 100; t += 0.05) if (sim.spike(t) > 0.5) on++;
        double share = on * 0.05 / 100;
        assertTrue(share > 0.05 && share < 0.2, "a spike about a tenth of the time, at its worst half of that: " + share);
        double worst = 0;
        for (double t = 0; t < 100; t += 0.01) worst = Math.max(worst, sim.delayMs(t));
        assertEquals(50 + 150, worst, 2);
    }
}
