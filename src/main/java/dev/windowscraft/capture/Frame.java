package dev.windowscraft.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One captured image: tightly packed, top-down, 8-bit BGRA (the native layout of a Windows DIB).
 * {@code alphaValid} is false when the alpha byte is garbage (GDI leaves it at 0), in which case
 * the texture forces alpha to 1.
 */
public final class Frame {
    public final int width;
    public final int height;
    public final ByteBuffer data;
    public boolean alphaValid;
    public long captureNanos;

    Frame(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static Frame allocate(int width, int height) {
        return new Frame(width, height);
    }

    public int byteSize() {
        return width * height * 4;
    }
}
