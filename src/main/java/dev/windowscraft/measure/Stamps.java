package dev.windowscraft.measure;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Measurements only: when a packet arrived from the network (stamped where it's decoded, on the
 * network thread), so its handler on the game thread can tell how long it waited there. Holds a
 * bounded number of stamps; nothing at all while measuring is off.
 */
public final class Stamps {
    private static final int MAX = 4096;
    private static final Map<Object, Long> AT = new IdentityHashMap<>();

    /** Returns {@code packet} (so it can wrap a decoder's result). */
    public static <T> T arrived(T packet) {
        if (!Measure.ON) return packet;
        long now = System.nanoTime();
        synchronized (AT) {
            if (AT.size() >= MAX) AT.clear(); // (packets that never reached a handler)
            AT.put(packet, now);
        }
        return packet;
    }

    /** When it arrived (a {@link System#nanoTime()}), or 0 if it wasn't stamped (measuring off, or it never crossed a network). */
    public static long take(Object packet) {
        if (!Measure.ON) return 0;
        synchronized (AT) {
            Long t = AT.remove(packet);
            return t == null ? 0 : t;
        }
    }

    private Stamps() {}
}
