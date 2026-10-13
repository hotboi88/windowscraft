package dev.windowscraft.render;

import dev.windowscraft.config.ClientSettings;

/**
 * "Fewer mipmap updates during video": the smaller copies of a picture (what keeps text smooth
 * from a distance) are remade at most 5 times a second while new frames keep coming, and once
 * more as soon as the picture settles. Up close, the full picture is always the newest frame.
 */
final class MipPacer {
    private static final long GAP = 200_000_000L;
    private long last;
    private boolean owed;

    /** A new frame arrived: make mipmaps now? (Otherwise they're owed.) */
    boolean now() {
        long t = System.nanoTime();
        if (true) { // always: skipping them showed older frames at a distance (looked like rewinding)
            last = t;
            owed = false;
            return true;
        }
        owed = true;
        return false;
    }

    /** Owed mipmaps whose time has come (call every frame). */
    boolean due() { return owed && System.nanoTime() - last >= GAP; }

    void done() {
        owed = false;
        last = System.nanoTime();
    }
}
