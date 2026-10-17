package dev.windowscraft.capture;

/**
 * How long to wait before trying again something that stopped working: {@code first} seconds,
 * twice that after each further failure, never more than {@code most}. Times are
 * {@link System#nanoTime()}.
 */
public final class Backoff {
    private final long firstNanos, mostNanos;
    private int fails;
    private long dueAt;

    public Backoff(int firstSeconds, int mostSeconds) {
        firstNanos = firstSeconds * 1_000_000_000L;
        mostNanos = mostSeconds * 1_000_000_000L;
    }

    /** It failed (again) at {@code now}: the seconds until the next try. */
    public synchronized long failed(long now) {
        long wait = Math.min(mostNanos, firstNanos << Math.min(20, fails));
        fails++;
        dueAt = now + wait;
        return wait / 1_000_000_000L;
    }

    /** Has it failed, and is the next try due? */
    public synchronized boolean due(long now) { return fails > 0 && now - dueAt >= 0; }

    /** It worked a good while: a later failure waits the shortest time again. */
    public synchronized void reset() {
        fails = 0;
        dueAt = 0;
    }

    public synchronized int fails() { return fails; }
}
