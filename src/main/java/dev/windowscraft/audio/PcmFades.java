package dev.windowscraft.audio;

/** Short fades on S16LE stereo chunks, so starting or stopping sound never clicks. */
public final class PcmFades {
    private static final int FADE_FRAMES = 144; // 3 ms

    /** Fades the first 3 ms of the chunk in from silence. */
    public static void fadeIn(byte[] b, int len) {
        int frames = Math.min(FADE_FRAMES, len / 4);
        for (int f = 0; f < frames; f++) scale(b, f, (f + 1) / (double) (frames + 1));
    }

    /** Fades the last 3 ms of the chunk out to silence. */
    public static void fadeOut(byte[] b, int len) {
        int total = len / 4, frames = Math.min(FADE_FRAMES, total);
        for (int f = 0; f < frames; f++) scale(b, total - frames + f, 1 - (f + 1) / (double) frames);
    }

    private static void scale(byte[] b, int frame, double g) {
        for (int c = 0; c < 2; c++) {
            int o = frame * 4 + c * 2;
            int v = (int) Math.round(((short) ((b[o + 1] << 8) | (b[o] & 0xFF))) * g);
            b[o] = (byte) v;
            b[o + 1] = (byte) (v >> 8);
        }
    }

    private PcmFades() {}
}
