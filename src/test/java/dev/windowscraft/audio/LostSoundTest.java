package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LostSoundTest {
    private static final int PACKET = 1920; // 10 ms
    private static final long MS = 1_000_000L;

    /** Sound captured in 10 ms packets, handed over as the helper's loop gets to it (in 4 KB reads, like the pipe). */
    private static List<LostSound.Hole> run(long[] handedAtMs, int[] packetsEach) {
        LostSound l = new LostSound();
        List<LostSound.Hole> holes = new ArrayList<>();
        long t0 = 1_000 * MS;
        for (int b = 0; b < handedAtMs.length; b++) {
            int left = packetsEach[b] * PACKET;
            long t = t0 + handedAtMs[b] * MS;
            while (left > 0) {
                int n = Math.min(4096, left);
                LostSound.Hole h = l.arrived(t, n);
                if (h != null) holes.add(h);
                left -= n;
                t += 50_000; // (back to back)
            }
        }
        return holes;
    }

    @Test
    void lateButCompleteIsNotLost() {
        // every 250 ms a batch comes 12 ms late — with the packets it waited for — plus jitter
        Random r = new Random(1);
        int n = 600;
        long[] at = new long[n];
        int[] packets = new int[n];
        long t = 0;
        for (int b = 0; b < n; b++) {
            boolean late = b % 25 == 0 && b > 0;
            at[b] = t + 10 + (late ? 12 : r.nextInt(3));
            packets[b] = 1;
            t += 10;
        }
        // a late batch brings the one it held up along (two packets), the next has none: merge them
        List<long[]> merged = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            if (b % 25 == 1 && b > 1) merged.get(merged.size() - 1)[1]++;
            else merged.add(new long[]{at[b], 1});
        }
        long[] mAt = merged.stream().mapToLong(x -> x[0]).toArray();
        int[] mPk = merged.stream().mapToInt(x -> (int) x[1]).toArray();
        assertEquals(List.of(), run(mAt, mPk));
    }

    @Test
    void soundThatNeverCameIsPutBackWhereItWent() {
        // a 30 ms stall at 3 s, and two of its packets (20 ms) never come
        List<long[]> batches = new ArrayList<>();
        for (int b = 0; b < 300; b++) batches.add(new long[]{10 + b * 10L, 1});
        batches.add(new long[]{3_040, 2}); // (after the stall: 2 of its 4 packets)
        for (int b = 0; b < 200; b++) batches.add(new long[]{3_050 + b * 10L, 1});
        int bytesBefore = 300 * PACKET;
        List<LostSound.Hole> holes = run(batches.stream().mapToLong(x -> x[0]).toArray(), batches.stream().mapToInt(x -> (int) x[1]).toArray());
        assertEquals(1, holes.size(), holes.toString());
        LostSound.Hole h = holes.get(0);
        assertEquals(20, h.bytes() * 1000 / AudioCapture.BYTES_PER_SECOND, 2, "20 ms of silence");
        // put where it went missing: right after the sound from before the stall (±10 ms)
        int sentSoFar = bytesBefore + 2 * PACKET + 4 * PACKET; // when it's found, 5 batches on
        int at = sentSoFar - h.back();
        assertTrue(Math.abs(at - bytesBefore) <= PACKET + 4096, "at " + at + ", went missing at " + bytesBefore);
    }

    @Test
    void anAppGoingQuietIsNotLostSound() {
        List<long[]> batches = new ArrayList<>();
        for (int b = 0; b < 200; b++) batches.add(new long[]{10 + b * 10L, 1});
        for (int b = 0; b < 200; b++) batches.add(new long[]{5_000 + b * 10L, 1}); // nothing for 3 s
        assertEquals(List.of(), run(batches.stream().mapToLong(x -> x[0]).toArray(), batches.stream().mapToInt(x -> (int) x[1]).toArray()));
    }

    @Test
    void ringTakesTheSilenceInTheMiddle() {
        PcmRing r = new PcmRing(AudioCapture.BYTES_PER_SECOND);
        byte[] loud = new byte[PACKET];
        java.util.Arrays.fill(loud, (byte) 0x40);
        r.write(loud, 0, PACKET);
        r.write(loud, 0, PACKET);
        r.insertSilence(PACKET, 960);
        assertEquals(2 * PACKET + 960, r.available());
        byte[] out = new byte[2 * PACKET + 960];
        r.read(out, 0, out.length);
        assertEquals(0x40, out[0]);
        for (int i = PACKET; i < PACKET + 960; i++) assertEquals(0, out[i], "silence at " + i);
        assertEquals(0x40, out[out.length - 1]);
        short full = (short) 0x4040;
        short beforeHole = (short) ((out[PACKET - 3] << 8) | (out[PACKET - 4] & 0xFF));
        short afterHole = (short) ((out[PACKET + 961] << 8) | (out[PACKET + 960] & 0xFF));
        assertTrue(Math.abs(beforeHole) < full / 50 && Math.abs(afterHole) < full / 50, "faded either side: " + beforeHole + ", " + afterHole);
    }
}
