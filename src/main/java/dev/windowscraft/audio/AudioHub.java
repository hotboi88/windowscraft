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
        for (PcmRing r : taps) r.write(pcm, off, len);
        clicks.feed(pcm, off, len);
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
