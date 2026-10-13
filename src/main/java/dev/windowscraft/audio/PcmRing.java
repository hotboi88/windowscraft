package dev.windowscraft.audio;

/**
 * Small thread-safe ring of S16LE stereo PCM. Writers push whatever arrives; the reader pulls on
 * its own clock. If the reader falls behind, the oldest audio is discarded so delay never grows.
 */
public final class PcmRing {
    private final byte[] buf;
    private int read, size;
    private boolean closed;

    public PcmRing(int capacityBytes) {
        buf = new byte[capacityBytes - capacityBytes % 4];
    }

    public synchronized void write(byte[] src, int off, int len) {
        len -= len % 4;
        if (len >= buf.length) { // keep only the newest
            off += len - buf.length;
            len = buf.length;
        }
        int overflow = size + len - buf.length;
        if (overflow > 0) {
            read = (read + overflow) % buf.length;
            size -= overflow;
        }
        int w = (read + size) % buf.length;
        int first = Math.min(len, buf.length - w);
        System.arraycopy(src, off, buf, w, first);
        System.arraycopy(src, off + first, buf, 0, len - first);
        size += len;
        notifyAll();
    }

    /** Waits until there's data (or the ring is closed: returns -1). */
    public synchronized int readBlocking(byte[] dst, int off, int len) throws InterruptedException {
        while (size < 4 && !closed) wait(250);
        if (size < 4) return -1;
        return read(dst, off, len);
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    /** A blocking stream view, for players that read at their own pace. */
    public java.io.InputStream asInputStream() {
        return new java.io.InputStream() {
            @Override public int read() { throw new UnsupportedOperationException(); }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                try {
                    return readBlocking(b, off, len);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException();
                }
            }
        };
    }

    /** Reads up to len bytes (whole frames); returns how many were copied. */
    public synchronized int read(byte[] dst, int off, int len) {
        len = Math.min(len - len % 4, size);
        int first = Math.min(len, buf.length - read);
        System.arraycopy(buf, read, dst, off, first);
        System.arraycopy(buf, 0, dst, off + first, len - first);
        read = (read + len) % buf.length;
        size -= len;
        return len;
    }

    public synchronized int available() { return size; }

    /** Drops audio beyond {@code maxBytes} (oldest first). */
    public synchronized void trimTo(int maxBytes) {
        maxBytes -= maxBytes % 4;
        if (size > maxBytes) {
            read = (read + size - maxBytes) % buf.length;
            size = maxBytes;
        }
    }
}
