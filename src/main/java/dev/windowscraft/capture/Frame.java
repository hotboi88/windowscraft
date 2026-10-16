package dev.windowscraft.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One captured image: tightly packed, top-down, 8-bit BGRA (the native layout of a Windows DIB).
 * {@code alphaValid} is false when the alpha byte is garbage (GDI leaves it at 0), in which case
 * the texture forces alpha to 1.
 *
 * <p>Or, for a decoded stream, NV12 ({@code nv12}): a byte of brightness per pixel, then a pair
 * of colour bytes (U, V) per 2×2 pixels — what the video decoder makes, 1.5 bytes a pixel. The
 * wall's texture turns it into colours on the graphics card.
 */
public final class Frame {
    public final int width;
    public final int height;
    public final boolean nv12;
    public final ByteBuffer data;
    public boolean alphaValid;
    public long captureNanos;
    /** Someone else's screen: not before this (lip sync after the decoder: it waits for its sound). */
    public long showAtNanos;
    /** (measuring) it carries the test pattern's once-a-second flash. */
    public boolean flash;

    Frame(int width, int height, boolean nv12) {
        this.width = width;
        this.height = height;
        this.nv12 = nv12;
        this.data = ByteBuffer.allocateDirect(bytes(width, height, nv12)).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static Frame allocate(int width, int height) {
        return new Frame(width, height, false);
    }

    public static Frame allocate(int width, int height, boolean nv12) {
        return new Frame(width, height, nv12);
    }

    /** Bytes of one picture: 4 a pixel (BGRA) or 1.5 (NV12, even sizes). */
    public static int bytes(int width, int height, boolean nv12) {
        return nv12 ? width * height + (width / 2) * (height / 2) * 2 : width * height * 4;
    }

    public int byteSize() {
        return bytes(width, height, nv12);
    }
}
