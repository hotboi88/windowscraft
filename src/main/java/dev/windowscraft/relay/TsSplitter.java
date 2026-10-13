package dev.windowscraft.relay;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts an MPEG-TS byte stream into runs of whole 188-byte packets, starting a new run at every
 * video keyframe (packets whose adaptation field has the random-access indicator, which FFmpeg's
 * muxer sets on keyframes). That's what lets the relay skip a slow viewer to a clean picture.
 */
public final class TsSplitter {
    static final int PACKET = 188;
    private static final int MAX_RUN = 64 * 1024;
    private final byte[] carry = new byte[PACKET];
    private int carryLen;
    private ByteArrayOutputStream run = new ByteArrayOutputStream(MAX_RUN);
    private boolean runIsKey;
    private int pmtPid = -1, videoPid = -1;
    private boolean runOnlyTables;

    public List<RelayServer.Chunk> feed(byte[] buf, int len) {
        List<RelayServer.Chunk> out = new ArrayList<>();
        int i = 0;
        // complete a packet split across reads
        if (carryLen > 0) {
            int need = PACKET - carryLen;
            int take = Math.min(need, len);
            System.arraycopy(buf, 0, carry, carryLen, take);
            carryLen += take;
            i = take;
            if (carryLen < PACKET) return out;
            packet(carry, 0, out);
            carryLen = 0;
        }
        while (i + PACKET <= len) {
            if (buf[i] != 0x47) { // lost sync: find the next sync byte
                i++;
                continue;
            }
            packet(buf, i, out);
            i += PACKET;
        }
        if (i < len) {
            carryLen = len - i;
            System.arraycopy(buf, i, carry, 0, carryLen);
        }
        // never hold video/audio back (latency matters more than chunk size); only a run of bare
        // PAT/PMT tables waits, so it stays glued to the keyframe that follows it
        if (!runOnlyTables) flush(out);
        return out;
    }

    private void packet(byte[] b, int o, List<RelayServer.Chunk> out) {
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        if (pid == 0) {
            pmtPid = parsePat(b, o, pmtPid);
            // FFmpeg writes PAT/PMT right before each keyframe; start a new run at the PAT so a
            // viewer that begins on that keyframe also gets the tables it needs to decode it
            flush(out);
            runOnlyTables = true;
        } else if (pid == pmtPid) {
            videoPid = parsePmt(b, o, videoPid);
        } else if (pid == videoPid && isKeyframeStart(b, o)) {
            if (!runOnlyTables) flush(out);
            runIsKey = true;
            runOnlyTables = false;
        } else {
            runOnlyTables = false;
        }
        run.write(b, o, PACKET);
        if (run.size() >= MAX_RUN) flush(out);
    }

    private void flush(List<RelayServer.Chunk> out) {
        if (run.size() == 0) return;
        out.add(new RelayServer.Chunk(run.toByteArray(), runIsKey));
        run.reset();
        runIsKey = false;
    }

    /** Offset of the section start (after the pointer field) for a PSI packet, or -1. */
    private static int sectionStart(byte[] b, int o) {
        if ((b[o + 1] & 0x40) == 0) return -1; // only parse tables that start in this packet
        int afc = (b[o + 3] >> 4) & 0x3, p = o + 4;
        if ((afc & 0x2) != 0) p += 1 + (b[p] & 0xFF);
        if ((afc & 0x1) == 0 || p >= o + PACKET) return -1;
        p += 1 + (b[p] & 0xFF); // pointer field
        return p < o + PACKET - 12 ? p : -1;
    }

    static int parsePat(byte[] b, int o, int fallback) {
        int p = sectionStart(b, o);
        if (p < 0 || b[p] != 0x00) return fallback;
        int sectionLen = ((b[p + 1] & 0x0F) << 8) | (b[p + 2] & 0xFF);
        int end = Math.min(p + 3 + sectionLen - 4, o + PACKET);
        for (int q = p + 8; q + 4 <= end; q += 4) {
            int program = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
            if (program != 0) return ((b[q + 2] & 0x1F) << 8) | (b[q + 3] & 0xFF);
        }
        return fallback;
    }

    static int parsePmt(byte[] b, int o, int fallback) {
        int p = sectionStart(b, o);
        if (p < 0 || b[p] != 0x02) return fallback;
        int sectionLen = ((b[p + 1] & 0x0F) << 8) | (b[p + 2] & 0xFF);
        int end = Math.min(p + 3 + sectionLen - 4, o + PACKET);
        int progInfoLen = ((b[p + 10] & 0x0F) << 8) | (b[p + 11] & 0xFF);
        for (int q = p + 12 + progInfoLen; q + 5 <= end; ) {
            int type = b[q] & 0xFF;
            int pid = ((b[q + 1] & 0x1F) << 8) | (b[q + 2] & 0xFF);
            int esInfo = ((b[q + 3] & 0x0F) << 8) | (b[q + 4] & 0xFF);
            if (type == 0x1B || type == 0x24) return pid; // H.264 / HEVC
            q += 5 + esInfo;
        }
        return fallback;
    }

    static boolean isKeyframeStart(byte[] b, int o) {
        boolean payloadStart = (b[o + 1] & 0x40) != 0;
        int afc = (b[o + 3] >> 4) & 0x3;
        if (!payloadStart || (afc & 0x2) == 0) return false; // needs an adaptation field
        int afLen = b[o + 4] & 0xFF;
        return afLen > 0 && (b[o + 5] & 0x40) != 0; // random_access_indicator
    }
}
