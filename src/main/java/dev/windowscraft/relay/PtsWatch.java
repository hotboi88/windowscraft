package dev.windowscraft.relay;

import java.util.function.LongConsumer;

/**
 * Measurements only: finds each video picture's timestamp (PTS) in an MPEG-TS byte stream. The
 * GPU path stamps every picture with the wall clock when it was captured (setpts=RTCTIME with
 * -copyts), so "now − PTS" anywhere along the way is how old that picture is there.
 */
public final class PtsWatch {
    private static final int PACKET = TsSplitter.PACKET;
    private static final long WRAP = 1L << 33;
    private final byte[] carry = new byte[PACKET];
    private int carryLen;
    private int pmtPid = -1, videoPid = -1;

    /** Feeds {@code len} bytes; {@code onPts} gets the PTS (90 kHz) of each video picture that starts in them. */
    public void feed(byte[] buf, int off, int len, LongConsumer onPts) {
        int i = off, end = off + len;
        if (carryLen > 0) {
            int take = Math.min(PACKET - carryLen, len);
            System.arraycopy(buf, off, carry, carryLen, take);
            carryLen += take;
            i += take;
            if (carryLen < PACKET) return;
            packet(carry, 0, onPts);
            carryLen = 0;
        }
        while (i + PACKET <= end) {
            if (buf[i] != 0x47) {
                i++;
                continue;
            }
            packet(buf, i, onPts);
            i += PACKET;
        }
        if (i < end) {
            carryLen = end - i;
            System.arraycopy(buf, i, carry, 0, carryLen);
        }
    }

    private void packet(byte[] b, int o, LongConsumer onPts) {
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        if (pid == 0) pmtPid = TsSplitter.parsePat(b, o, pmtPid);
        else if (pid == pmtPid) videoPid = TsSplitter.parsePmt(b, o, videoPid);
        else if (pid == videoPid && (b[o + 1] & 0x40) != 0) {
            long pts = pesPts(b, o);
            if (pts >= 0) onPts.accept(pts);
        }
    }

    /** The PTS of a PES that starts in this packet, or -1. */
    static long pesPts(byte[] b, int o) {
        int afc = (b[o + 3] >> 4) & 0x3, p = o + 4;
        if ((afc & 0x2) != 0) p += 1 + (b[p] & 0xFF);
        if ((afc & 0x1) == 0 || p + 14 > o + PACKET) return -1;
        if (b[p] != 0 || b[p + 1] != 0 || b[p + 2] != 1) return -1;
        if (((b[p + 7] >> 6) & 0x2) == 0) return -1; // no PTS
        int q = p + 9;
        return ((long) ((b[q] >> 1) & 0x7) << 30) | ((long) (b[q + 1] & 0xFF) << 22) | ((long) ((b[q + 2] & 0xFF) >> 1) << 15)
                | ((long) (b[q + 3] & 0xFF) << 7) | ((b[q + 4] & 0xFF) >> 1);
    }

    /**
     * How old a picture stamped with the wall clock is at {@code nowMillis}, in nanoseconds; or
     * {@code Long.MIN_VALUE} if its stamp isn't the wall clock (more than two minutes off either way).
     */
    public static long ageNanos(long pts90k, long nowMillis) {
        long now90k = Math.floorMod(nowMillis * 90, WRAP);
        long d = Math.floorMod(now90k - pts90k, WRAP);
        if (d > WRAP / 2) d -= WRAP;
        if (Math.abs(d) > 120L * 90_000) return Long.MIN_VALUE;
        return d * 1_000_000L / 90; // 90 kHz ticks → ns
    }
}
