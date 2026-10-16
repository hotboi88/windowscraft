package dev.windowscraft.audio;

/**
 * Plays sound a hair faster or slower (a frame or two in 480) so a delay that crept away comes
 * back smoothly, without a skipped or repeated sample: 0.2 % is a pitch change of 3.5 cents,
 * which nobody hears. S16LE stereo, as a stream: each call takes the next {@code in} frames and
 * gives {@code out}, joined to the call before (4-point cubic interpolation; it runs 2 frames
 * behind, for the points it looks ahead to).
 */
public final class PcmStretch {
    public static final int MAX_FRAMES = 1024;
    // per channel, interleaved: the last 3 frames of the call before, then this call's
    private final float[] w = new float[(3 + MAX_FRAMES) * 2];

    /** After a gap: start again from silence. */
    public void reset() {
        java.util.Arrays.fill(w, 0, 6, 0f);
    }

    /** Takes {@code in} frames from {@code src} and writes {@code out} frames into {@code dst}. */
    public void stretch(byte[] src, int in, byte[] dst, int out) {
        for (int k = 0; k < in * 2; k++) w[6 + k] = (short) ((src[2 * k] & 0xFF) | (src[2 * k + 1] << 8));
        double step = (double) in / out;
        for (int j = 0; j < out; j++) {
            double p = 1 + j * step; // (always between frame 1 and frame in + 1: room for its 4 points)
            int i = (int) p;
            float t = (float) (p - i);
            for (int c = 0; c < 2; c++) {
                float p0 = w[(i - 1) * 2 + c], p1 = w[i * 2 + c], p2 = w[(i + 1) * 2 + c], p3 = w[(i + 2) * 2 + c];
                float y = p1 + 0.5f * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
                int v = Math.max(-32768, Math.min(32767, Math.round(y)));
                int o = (j * 2 + c) * 2;
                dst[o] = (byte) v;
                dst[o + 1] = (byte) (v >> 8);
            }
        }
        System.arraycopy(w, in * 2, w, 0, 6); // this call's last 3 frames: the next one's start
    }
}
