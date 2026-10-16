package dev.windowscraft.audio;

import dev.windowscraft.capture.SourceInfo;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sound of one share. Owns the capture (restarting it when the options change) and hands
 * every captured block to each open tap: the network stream has one, and the in-game speaker
 * has another while "hear it in the game" is on. Taps outlive capture restarts.
 */
public final class AudioHub implements AudioCapture.Sink {
    private final SourceInfo source;
    private final List<PcmRing> taps = new CopyOnWriteArrayList<>();
    private AudioCapture capture;
    private boolean on, hearInGame, starting;
    private int generation;
    private volatile long lastSoundNanos;
    private volatile double peak;
    private final ClickDetector clicks = new ClickDetector("captured");
    private final LostSound lost = new LostSound();

    public AudioHub(SourceInfo source) { this.source = source; }

    /** Starts, restarts or stops the capture to match the options. Client thread. */
    public synchronized void configure(boolean audio, boolean wantInGame) {
        boolean inGameCapable = source.kind() == SourceInfo.Kind.WINDOW || source.kind() == SourceInfo.Kind.TEST
                || dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(source);
        boolean inGame = wantInGame && inGameCapable;
        if (audio == on && inGame == hearInGame && (capture != null || !audio || starting)) return;
        java.util.concurrent.CompletableFuture<?> previous = stopCapture();
        on = audio;
        hearInGame = inGame;
        int gen = ++generation;
        if (!audio) return;
        // The old helper must finish giving the app its volume back before a new one turns it
        // down again — otherwise the new one would take the turned-down level for the original.
        starting = true;
        previous.whenComplete((r, e) -> {
            synchronized (AudioHub.this) {
                if (gen != generation || !on) return;
                capture = AudioCapture.forSource(source, hearInGame, AudioHub.this);
                starting = false;
            }
        });
    }

    public synchronized boolean enabled() { return on && capture != null; }
    public synchronized boolean available() { return capture != null; }

    /** True while the app is turned down and its sound should play from the wall for the sharer. */
    public synchronized boolean playsInGame() { return capture != null && capture.inGame(); }

    public synchronized String status() {
        if (!on) return "audio off";
        if (capture == null) return starting ? "starting audio…" : "no audio on this system";
        return capture.status();
    }

    /** Is sound actually coming in right now (anything above silence in the last second)? */
    public boolean hearingSound() { return System.nanoTime() - lastSoundNanos < 1_000_000_000L; }
    /** Recent peak level, 0..1 (decays). */
    public double level() { return peak; }

    public PcmRing openTap() {
        PcmRing r = new PcmRing(AudioCapture.BYTES_PER_SECOND);
        taps.add(r);
        return r;
    }

    public void closeTap(PcmRing r) {
        if (r == null) return;
        taps.remove(r);
        r.close();
    }

    @Override
    public void write(byte[] pcm, int off, int len) {
        int max = 0;
        for (int i = off; i + 1 < off + len; i += 2) {
            int v = Math.abs((short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF)));
            if (v > max) max = v;
        }
        double p = max / 32768.0;
        peak = Math.max(p, peak * 0.85);
        if (max > 40) lastSoundNanos = System.nanoTime(); // above -58 dBFS: real sound, not dither
        LostSound.Hole hole = lost.arrived(System.nanoTime(), len);
        if (hole != null) {
            for (PcmRing r : taps) r.insertSilence(hole.back(), hole.bytes());
            holes++;
            holeBytes += hole.bytes();
        }
        for (PcmRing r : taps) r.write(pcm, off, len);
        clicks.feed(pcm, off, len);
        if (dev.windowscraft.measure.Measure.ON) note(len);
    }

    // (measuring) how the sound comes in: its bursts and the gaps between them
    private long noteAt, lastWriteNanos, bytes, maxGap, holeBytes;
    private int writes, gaps30, holes;

    private synchronized void note(int len) {
        long now = System.nanoTime();
        if (noteAt == 0) noteAt = now;
        if (lastWriteNanos != 0) {
            long gap = now - lastWriteNanos;
            maxGap = Math.max(maxGap, gap);
            if (gap > 30_000_000L) gaps30++;
        }
        lastWriteNanos = now;
        bytes += len;
        writes++;
        if (now - noteAt > 10_000_000_000L) {
            dev.windowscraft.WindowsCraftClient.LOGGER.info("[audio in] {} ms of sound per s in {} bursts/s · gaps over 30 ms: {} · longest {} ms · lost and put back as silence: {} ms in {} holes",
                    bytes * 1000 / AudioCapture.BYTES_PER_SECOND * 1_000_000_000L / (now - noteAt), writes * 1_000_000_000L / (now - noteAt), gaps30, maxGap / 1_000_000, holeBytes * 1000 / AudioCapture.BYTES_PER_SECOND, holes);
            noteAt = now;
            bytes = writes = gaps30 = holes = 0;
            holeBytes = 0;
            maxGap = 0;
        }
    }

    private java.util.concurrent.CompletableFuture<?> stopCapture() {
        starting = false;
        if (capture == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        AudioCapture c = capture;
        capture = null;
        c.close();
        return c.stopped();
    }

    public synchronized void close() {
        stopCapture();
        on = false;
        generation++;
        for (PcmRing r : taps) r.close();
        taps.clear();
    }
}
