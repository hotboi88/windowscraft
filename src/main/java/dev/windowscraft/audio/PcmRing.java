package dev.windowscraft.audio;

/**
 * Small thread-safe ring of S16LE stereo PCM. Writers push whatever arrives; the reader pulls on
 * its own clock. If the reader falls behind, the oldest audio is discarded so delay never grows.
 */
public final class PcmRing {
    private final byte[] buf;
    private int read, size;
    private boolean closed;
    private long lastWriteNanos;

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
        lastWriteNanos = System.nanoTime();
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

    /**
     * How long ago, ns, the oldest sound waiting here came in: what's queued plus the time since
     * the newest arrived (it keeps ageing while nothing comes). It doesn't jump with the uneven
     * bursts sound arrives in, as the amount queued does. 0 when empty.
     */
    public synchronized long oldestAgeNanos(int bytesPerSecond) {
        if (size == 0) return 0;
        return size * 1_000_000_000L / bytesPerSecond + (System.nanoTime() - lastWriteNanos);
    }

    /**
     * Puts {@code len} bytes of silence {@code back} bytes before the end (at the front, if that
     * part was read already), fading the sound either side of it in and out (no clicks).
     */
    public synchronized void insertSilence(int back, int len) {
        len -= len % 4;
        if (len <= 0) return;
        back = Math.max(0, Math.min(back - back % 4, size));
        byte[] all = new byte[size + len];
        int at = size - back;
        int first = Math.min(size, buf.length - read);
        System.arraycopy(buf, read, all, 0, first);
        System.arraycopy(buf, 0, all, first, size - first);
        System.arraycopy(all, at, all, at + len, back);
        java.util.Arrays.fill(all, at, at + len, (byte) 0);
        int fade = Math.min(at, 576) & ~3;
        byte[] edge = java.util.Arrays.copyOfRange(all, at - fade, at);
        PcmFades.fadeOut(edge, fade);
        System.arraycopy(edge, 0, all, at - fade, fade);
        fade = Math.min(back, 576) & ~3;
        edge = java.util.Arrays.copyOfRange(all, at + len, at + len + fade);
        PcmFades.fadeIn(edge, fade);
        System.arraycopy(edge, 0, all, at + len, fade);
        int keep = Math.min(all.length, buf.length); // (over the top: the newest stays)
        System.arraycopy(all, all.length - keep, buf, 0, keep);
        read = 0;
        size = keep;
    }

    /** Drops audio beyond {@code maxBytes} (oldest first). */
    public synchronized void trimTo(int maxBytes) {
        maxBytes -= maxBytes % 4;
        if (size > maxBytes) {
            read = (read + size - maxBytes) % buf.length;
            size = maxBytes;
        }
    }
}
