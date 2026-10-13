package dev.windowscraft.capture.win;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.PointerByReference;
import dev.windowscraft.capture.Frame;
import java.nio.ByteBuffer;

/**
 * A memory DC with a 32-bit top-down DIB selected into it. The DIB's pixels live in memory we can
 * read directly (no GetDIBits round trip), so copying a frame out is a single memcpy.
 */
final class DibSurface implements AutoCloseable {
    final int width, height;
    final HDC dc;
    private final HBITMAP bitmap;
    private final HANDLE previous;
    private final Pointer bits;

    DibSurface(HDC compatibleWith, int width, int height) {
        this.width = width;
        this.height = height;
        this.dc = Win32.Gdi32.I.CreateCompatibleDC(compatibleWith);
        if (dc == null) throw new IllegalStateException("CreateCompatibleDC failed");
        WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height; // negative = top-down rows
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = Win32.BI_RGB;
        PointerByReference ppv = new PointerByReference();
        this.bitmap = Win32.Gdi32.I.CreateDIBSection(dc, bmi, Win32.DIB_RGB_COLORS, ppv, null, 0);
        if (bitmap == null || ppv.getValue() == null) {
            Win32.Gdi32.I.DeleteDC(dc);
            throw new IllegalStateException("CreateDIBSection failed for " + width + "x" + height);
        }
        this.bits = ppv.getValue();
        this.previous = Win32.Gdi32.I.SelectObject(dc, bitmap);
    }

    ByteBuffer pixels() {
        return bits.getByteBuffer(0, (long) width * height * 4);
    }

    /** Zero the pixels (needed before drawing icons so untouched areas stay transparent). */
    void clear() {
        bits.setMemory(0, (long) width * height * 4, (byte) 0);
    }

    /**
     * True if a sparse grid of pixels in the given region is entirely black. Used to detect apps
     * that silently ignore a capture method (they leave the bitmap untouched).
     */
    boolean looksBlank(int x, int y, int w, int h) {
        Win32.Gdi32.I.GdiFlush();
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                int px = x + (int) ((gx + 0.5) * w / 8.0), py = y + (int) ((gy + 0.5) * h / 8.0);
                if (px < 0 || py < 0 || px >= width || py >= height) continue;
                if ((bits.getInt(((long) py * width + px) * 4) & 0x00FFFFFF) != 0) return false;
            }
        }
        return true;
    }

    void copyTo(Frame frame) {
        Win32.Gdi32.I.GdiFlush();
        frame.data.clear();
        frame.data.put(pixels());
        frame.data.flip();
        frame.alphaValid = false;
    }

    @Override
    public void close() {
        Win32.Gdi32.I.SelectObject(dc, previous);
        Win32.Gdi32.I.DeleteObject(bitmap);
        Win32.Gdi32.I.DeleteDC(dc);
    }
}
