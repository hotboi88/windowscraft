package dev.windowscraft.display;

/**
 * "As sharp as it looks": how many times your wall's copy of a screen is halved (on the graphics
 * card, before it's read back: wc-capture "shrink"). A wall 500 pixels tall on your screen shows
 * no more of a 4K picture than of a 1080p one — the graphics card would draw it from a smaller
 * copy anyway (mipmaps) — but every 4K copy costs four times as much to move through the CPU.
 *
 * <p>Halved only while the smaller copy still has twice the pixels the wall takes on your screen;
 * back to sharper as soon as it has less than 1.4 times (walking up to it), smaller again only
 * after it's been small enough for a moment (so it doesn't flip back and forth).
 */
final class CopySize {
    static final double KEEP = 2.0, BACK = 1.4;
    static final int MAX = 3;
    static final long SETTLE_NANOS = 2_000_000_000L;

    private int level;
    private long smallerSince = -1;

    /**
     * @param fullH the picture's own height (0: not known yet)
     * @param px    how tall the wall looks on your screen
     * @param full  it has to be full size (you sit at it, the setting is off, the stream needs it)
     * @return the halvings wanted now
     */
    int update(int fullH, double px, boolean full, long now) {
        if (full || fullH <= 0 || !(px > 0)) {
            level = 0;
            smallerSince = -1;
            return 0;
        }
        int target = 0;
        while (target < MAX && (fullH >> (target + 1)) >= KEEP * px) target++;
        if ((fullH >> level) < BACK * px) { // too small for how big it looks now: sharper straight away
            level = target;
            smallerSince = -1;
        } else if (target > level) { // it could be smaller: once it has been for a moment
            if (smallerSince < 0) smallerSince = now;
            else if (now - smallerSince >= SETTLE_NANOS) {
                level = target;
                smallerSince = -1;
            }
        } else smallerSince = -1;
        return level;
    }

    int level() { return level; }
}
