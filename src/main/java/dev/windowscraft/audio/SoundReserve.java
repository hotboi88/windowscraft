package dev.windowscraft.audio;

/**
 * How much of a screen's sound is kept queued ahead of the speakers (Automatic): it grows a step
 * each time sound still arrives too late (the speakers ran dry), and after a calm spell it gives a
 * step back — never below where it started. A step back that's soon followed by sound arriving
 * late again was too eager: the next calm spell has to be twice as long. With lip sync the picture
 * waits for the sound, so a reserve that only ever grew kept every later picture that much behind.
 */
final class SoundReserve {
    static final int MAX_MS = 320, STEP_MS = 50;
    static final long FIRST_CALM_NANOS = 120_000_000_000L, LONGEST_CALM_NANOS = 1_800_000_000_000L, TOO_SOON_NANOS = 300_000_000_000L;

    private final int startMs;
    private int ms;
    private long calmSince, calmNeeded, lastStepBack = Long.MIN_VALUE;

    SoundReserve(int startMs, long now) {
        this(startMs, now, FIRST_CALM_NANOS);
    }

    SoundReserve(int startMs, long now, long calmNanos) {
        this.startMs = startMs;
        this.ms = startMs;
        this.calmSince = now;
        this.calmNeeded = calmNanos;
    }

    /** What's kept queued now (ms). */
    int ms() { return ms; }

    /** Sound arrived too late: a step more (up to {@link #MAX_MS}). True if it grew. */
    boolean late(long now) {
        if (lastStepBack != Long.MIN_VALUE && now - lastStepBack < TOO_SOON_NANOS) calmNeeded = Math.min(LONGEST_CALM_NANOS, calmNeeded * 2);
        calmSince = now;
        if (ms >= MAX_MS) return false;
        ms = Math.min(MAX_MS, ms + STEP_MS);
        return true;
    }

    /** After a calm spell, a step back (never below the start). Returns how many ms it gave back (usually 0). */
    int stepBackIfCalm(long now) {
        if (ms <= startMs || now - calmSince < calmNeeded) return 0;
        int back = Math.min(STEP_MS, ms - startMs);
        ms -= back;
        calmSince = now;
        lastStepBack = now;
        return back;
    }
}
