package dev.windowscraft.audio;

/**
 * Passes on only whole stereo frames (4 bytes) from a byte stream that arrives in pieces of any
 * size, keeping the 1–3 leftover bytes for the next piece.
 *
 * <p>A pipe hands over whatever bytes are there — not whole frames. Simply dropping a leftover
 * byte or two (as the ring buffer must for its own safety) shifts the rest of that piece by one
 * byte, turning ~10 ms of sound into full-volume noise: a loud, sharp pop.
 */
public final class FrameAligner {
    private final byte[] carry = new byte[4];
    private int carried;
    private byte[] work = new byte[0];

    public interface Out { void write(byte[] b, int off, int len); }

    public void feed(byte[] b, int off, int len, Out out) {
        if (carried == 0 && len % 4 == 0) { // the common case: nothing to stitch
            if (len > 0) out.write(b, off, len);
            return;
        }
        int total = carried + len;
        if (work.length < total) work = new byte[Math.max(total, 8192)];
        System.arraycopy(carry, 0, work, 0, carried);
        System.arraycopy(b, off, work, carried, len);
        int whole = total - total % 4;
        carried = total - whole;
        System.arraycopy(work, whole, carry, 0, carried);
        if (whole > 0) out.write(work, 0, whole);
    }
}
