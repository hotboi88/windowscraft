package dev.windowscraft.relay;

import java.io.ByteArrayOutputStream;

/**
 * Hands a viewer's decoder each big picture the moment it's complete.
 *
 * <p>A video packet in MPEG-TS can only say how long it is up to 64 KB; a bigger picture (every
 * keyframe; a page that loads, a window that opens; every picture from a sender older than
 * 0.15.1) says nothing, and FFmpeg's TS reader then only knows it's complete when the next
 * picture starts. Pictures only come when the screen changes, so that can be a long wait: the
 * last change before the screen goes still showed up only with the next one.
 *
 * <p>Where such a picture ends (its last packet is padded out, as FFmpeg's muxer does), a tiny
 * packet is put right after it: the reader sees a new packet start, hands the picture on, and the
 * decoder drops the tiny one without a word (its data begins with "Q264", which FFmpeg's H.264
 * decoder skips silently, then an empty filler unit). The video packets after it are renumbered
 * so the stream stays continuous. Decoded pictures are unchanged (bit for bit).
 */
public final class PictureEnds {
    private static final int PACKET = TsSplitter.PACKET;
    private int shift;              // continuity counters of the video packets moved on by this much
    private int lastCc = -1;        // (after renumbering)
    private boolean openUnsized;    // a video packet without a length has started and not been closed
    private boolean lastEnds;       // the last video packet seen was padded out: the end of its packet
    private long lastPts = -1;

    /** The run of packets as the decoder should get it (the same array if nothing changed). */
    public byte[] process(byte[] data, int videoPid) {
        if (videoPid < 0) return data;
        boolean seenVideo = false;
        for (int o = 0; o + PACKET <= data.length; o += PACKET) {
            if ((data[o] & 0xFF) != 0x47) continue;
            int pid = ((data[o + 1] & 0x1F) << 8) | (data[o + 2] & 0xFF);
            if (pid != videoPid) continue;
            int afc = (data[o + 3] >> 4) & 0x3;
            if ((afc & 0x1) != 0) { // (a packet with payload counts: renumber it)
                int cc = ((data[o + 3] & 0x0F) + shift) & 0x0F;
                data[o + 3] = (byte) ((data[o + 3] & 0xF0) | cc);
                lastCc = cc;
            }
            seenVideo = true;
            int payload = o + 4;
            if ((afc & 0x2) != 0) payload += 1 + (data[o + 4] & 0xFF);
            if ((data[o + 1] & 0x40) != 0 && (afc & 0x1) != 0 && payload + 14 <= o + PACKET) { // a new video packet starts here
                int p = payload;
                if (data[p] == 0 && data[p + 1] == 0 && data[p + 2] == 1) {
                    int len = ((data[p + 4] & 0xFF) << 8) | (data[p + 5] & 0xFF);
                    openUnsized = len == 0;
                    if ((data[p + 7] & 0x80) != 0) lastPts = pts(data, p + 9);
                }
            }
            lastEnds = padded(data, o);
        }
        if (!seenVideo || !openUnsized || !lastEnds || lastCc < 0) return data;
        // the unsized picture just ended: the tiny packet after it
        openUnsized = false;
        int cc = (lastCc + 1) & 0x0F;
        shift = (shift + 1) & 0x0F;
        lastCc = cc;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + PACKET);
        out.write(data, 0, data.length);
        out.write(nudge(videoPid, cc, lastPts < 0 ? 0 : lastPts + 1), 0, PACKET);
        return out.toByteArray();
    }

    /** Does this packet's adaptation field pad it out (stuffing bytes: the payload ends before the packet does)? */
    static boolean padded(byte[] b, int o) {
        int afc = (b[o + 3] >> 4) & 0x3;
        if ((afc & 0x2) == 0) return false;
        int afLen = b[o + 4] & 0xFF;
        if (afLen == 0) return true; // (a one-byte field: it's only there to shorten the payload)
        int flags = b[o + 5] & 0xFF;
        int used = 1;
        if ((flags & 0x10) != 0) used += 6; // PCR
        if ((flags & 0x08) != 0) used += 6; // OPCR
        if ((flags & 0x04) != 0) used += 1; // splice countdown
        if ((flags & 0x02) != 0 && 5 + used < PACKET) used += 1 + (b[o + 5 + used] & 0xFF); // private data
        if ((flags & 0x01) != 0 && 5 + used < PACKET) used += 1 + (b[o + 5 + used] & 0xFF); // extension
        return afLen > used;
    }

    private static long pts(byte[] b, int p) {
        return ((long) ((b[p] >> 1) & 0x07) << 30) | ((long) (b[p + 1] & 0xFF) << 22) | ((long) ((b[p + 2] & 0xFF) >> 1) << 15)
                | ((long) (b[p + 3] & 0xFF) << 7) | ((b[p + 4] & 0xFF) >> 1);
    }

    /** One TS packet: a sized video packet holding "Q264" and an empty filler unit, padded out. */
    static byte[] nudge(int pid, int cc, long pts) {
        pts &= (1L << 33) - 1;
        byte[] es = {'Q', '2', '6', '4', 0, 0, 0, 1, 0x0C, (byte) 0xFF, (byte) 0x80};
        byte[] pes = new byte[14 + es.length];
        pes[2] = 1;
        pes[3] = (byte) 0xE0;
        int len = 3 + 5 + es.length;
        pes[4] = (byte) (len >> 8);
        pes[5] = (byte) len;
        pes[6] = (byte) 0x80;
        pes[7] = (byte) 0x80; // PTS only
        pes[8] = 5;
        pes[9] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        pes[10] = (byte) (pts >> 22);
        pes[11] = (byte) (0x01 | ((pts >> 14) & 0xFE));
        pes[12] = (byte) (pts >> 7);
        pes[13] = (byte) (0x01 | ((pts << 1) & 0xFE));
        System.arraycopy(es, 0, pes, 14, es.length);
        byte[] p = new byte[PACKET];
        p[0] = 0x47;
        p[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));
        p[2] = (byte) pid;
        p[3] = (byte) (0x30 | cc);
        int af = PACKET - 4 - 1 - pes.length; // adaptation field length: pads the packet out
        p[4] = (byte) af;
        p[5] = 0;
        for (int i = 6; i < 5 + af; i++) p[i] = (byte) 0xFF;
        System.arraycopy(pes, 0, p, 5 + af, pes.length);
        return p;
    }
}
