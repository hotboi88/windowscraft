package dev.windowscraft.audio;

import dev.windowscraft.WindowsCraftClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays one remote screen's sound from where the screen is: PCM from the decoder goes through the
 * Dream Displays acoustics chain (binaural direction, distance, occlusion, reverb) and out through
 * its own audio line. Keeps the line nearly empty so sound stays in step with the picture.
 */
public final class SpatialAudioPlayer {
    private static final AudioFormat FORMAT = new AudioFormat(AudioCapture.RATE, 16, 2, true, false);
    private static final int CHUNK = 960 * 4;              // 20 ms

    private final UUID id;
    private final AudioRenderChain chain;
    private volatile double volume = 1.0;
    private volatile boolean direct;
    private volatile boolean deviceMissing;
    // stats (also used by the automated A/V sync check)
    private volatile long bytesPlayed;
    private volatile double lastRms;
    private volatile long lastOnsetNanos;
    private boolean wasLoud;

    private final ClickDetector clicks;

    public SpatialAudioPlayer(UUID id) { this(id, "played"); }

    /** {@code stage} names this player in the click diagnostics (e.g. "played-yours"). */
    public SpatialAudioPlayer(UUID id, String stage) {
        this.id = id;
        this.clicks = new ClickDetector(stage);
        this.chain = AcousticsEngine.get().register(id);
        chain.reset();
    }

    public AudioRenderChain chain() { return chain; }
    public void setVolume(double v) { volume = v; }
    /** Direct: the stream's own stereo, untouched except for volume — no distance, direction or room effects. */
    public void setDirect(boolean d) {
        if (d != direct) {
            direct = d;
            if (!d) chain.reset();
            else { directLimiter.reset(); lastDirectGain = -1; }
        }
    }
    public long bytesPlayed() { return bytesPlayed; }
    public double lastRms() { return lastRms; }
    /** When the most recent beep/onset will actually be heard (System.nanoTime). */
    public long lastOnsetNanos() { return lastOnsetNanos; }
    public boolean deviceMissing() { return deviceMissing; }

    // Playback cushion. Sound arrives in slightly uneven bursts (from the app or the network) and
    // the game itself hitches now and then (garbage collection in a big modpack can pause every
    // thread for 50+ ms). Keep ~120 ms queued so neither ever empties the speakers. Samples pass
    // through untouched; only if the queue drifts far off (two sound cards' clocks never match
    // exactly) is 10 ms removed or repeated — blended over a few ms, so it can't click.
    private static final int FRAME = 4;
    private static final int START_TARGET = ms(Integer.getInteger("windowscraft.exp.audioReserve", 120)), // (Step 0 experiment, dev only)
            MAX_TARGET = ms(320), STEP = ms(50), LOW = ms(25), DRIFT = ms(70);
    /** Grows (up to 320 ms) each time sound still arrives too late — e.g. a heavy modpack's long pauses. */
    private int target = START_TARGET;
    private static final int ADJUST_FRAMES = 480, FADE_FRAMES = 192; // 10 ms, 4 ms crossfade
    private volatile long underruns, adjustments;
    private double queuedAvg = -1;
    private long lastReportNanos = System.nanoTime(), lastReportedUnderruns, lastReportedAdjustments;

    private static int ms(int m) { return AudioCapture.BYTES_PER_SECOND * m / 1000 / FRAME * FRAME; }

    /** How often playback had to be refilled because sound arrived too late (diagnostics). */
    public long underruns() { return underruns; }

    /** Blocks, playing everything from {@code in} until it ends. */
    public void play(InputStream in) throws IOException {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, AudioCapture.BYTES_PER_SECOND); // 1 s of room; we keep far less queued
            line.start();
        } catch (Exception | LinkageError e) {
            deviceMissing = true;
            WindowsCraftClient.LOGGER.warn("No audio output device; screen audio will be decoded but not played ({})", e.toString());
            line = null;
        }
        byte[] buf = new byte[CHUNK];
        boolean primed = false;
        try {
            while (true) {
                int got = 0;
                while (got < CHUNK) {
                    int n = in.read(buf, got, CHUNK - got);
                    if (n < 0) return;
                    got += n;
                }
                if (direct) directGain(buf, CHUNK, volume);
                else chain.process(buf, CHUNK, volume);
                measure(buf, line);
                clicks.feed(buf, 0, CHUNK);
                if (line != null) {
                    int queued = line.getBufferSize() - line.available();
                    if (!primed || queued < LOW) {
                        // (re)start with a cushion of silence; fade the sound in so there's no click
                        if (primed) {
                            underruns++;
                            if (target < MAX_TARGET) {
                                target = Math.min(MAX_TARGET, target + STEP);
                                WindowsCraftClient.LOGGER.info("Screen audio arrived late; keeping {} ms queued from now on", target / FRAME * 1000 / AudioCapture.RATE);
                            }
                        }
                        writeSilence(line, Math.max(0, target - queued));
                        fadeIn(buf);
                        primed = true;
                        queuedAvg = target;
                    } else if (queued > target + ms(280)) {
                        // far too much queued (e.g. the decoder caught up after a stall): drop this chunk
                        adjustments++;
                        continue;
                    }
                    queuedAvg = queuedAvg * 0.97 + queued * 0.03; // ~0.7 s average: ignores burstiness
                    int start = 0, len = CHUNK;
                    if (queuedAvg > target + DRIFT) {
                        // steadily too much: skip 10 ms. What would have followed the last thing
                        // played is buf[0..]; fade from that into buf[10 ms..] so the seam is smooth.
                        crossfade(java.util.Arrays.copyOf(buf, FADE_FRAMES * FRAME), buf, ADJUST_FRAMES);
                        start = ADJUST_FRAMES * FRAME;
                        len = CHUNK - start;
                        queuedAvg -= ADJUST_FRAMES * FRAME;
                        adjustments++;
                    } else if (queuedAvg < target - DRIFT) {
                        // steadily too little: play the first 10 ms twice, crossfaded at the seam
                        // After those 10 ms the natural continuation is buf[10 ms..]; fade from that
                        // into buf[0..], which is what plays next.
                        byte[] extra = java.util.Arrays.copyOf(buf, ADJUST_FRAMES * FRAME);
                        byte[] continuation = java.util.Arrays.copyOfRange(buf, ADJUST_FRAMES * FRAME, (ADJUST_FRAMES + FADE_FRAMES) * FRAME);
                        line.write(extra, 0, extra.length);
                        crossfade(continuation, buf, 0);
                        queuedAvg += ADJUST_FRAMES * FRAME;
                        adjustments++;
                    }
                    line.write(buf, start, len);
                }
                // No pacing here, ever: the decoder already delivers in real time, and reading
                // slower than that would back up FFmpeg and freeze the picture too.
                bytesPlayed += CHUNK;
                report();
            }
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /** Every 10 s, if anything happened: how often sound arrived late (helps find crackle causes). */
    private void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < 10_000_000_000L) return;
        lastReportNanos = now;
        if (underruns != lastReportedUnderruns || adjustments != lastReportedAdjustments) {
            WindowsCraftClient.LOGGER.info("Screen audio: {} refill(s) (sound arrived late), {} timing adjustment(s) in the last 10 s; queued ~{} ms",
                    underruns - lastReportedUnderruns, adjustments - lastReportedAdjustments, Math.round(queuedAvg / FRAME * 1000.0 / AudioCapture.RATE));
            lastReportedUnderruns = underruns;
            lastReportedAdjustments = adjustments;
        }
    }

    /**
     * Blends {@code from} (FADE_FRAMES of the sound that would naturally have come next) into
     * {@code to} starting at frame {@code at}, over FADE_FRAMES frames: no discontinuity, no click.
     */
    static void crossfade(byte[] from, byte[] to, int at) {
        for (int f = 0; f < FADE_FRAMES; f++) {
            double g = (f + 0.5) / FADE_FRAMES;
            for (int c = 0; c < 2; c++) {
                int oa = f * FRAME + c * 2, ob = (at + f) * FRAME + c * 2;
                int a = (short) ((from[oa + 1] << 8) | (from[oa] & 0xFF));
                int b = (short) ((to[ob + 1] << 8) | (to[ob] & 0xFF));
                int v = (int) Math.round(a * (1 - g) + b * g);
                to[ob] = (byte) v;
                to[ob + 1] = (byte) (v >> 8);
            }
        }
    }

    private static void writeSilence(SourceDataLine line, int bytes) {
        bytes -= bytes % FRAME;
        if (bytes > 0) line.write(new byte[bytes], 0, bytes);
    }

    /** ~3 ms fade-in at the start of a chunk (after a refill), so resuming doesn't click. */
    private static void fadeIn(byte[] b) {
        int frames = Math.min(144, b.length / FRAME);
        for (int f = 0; f < frames; f++) {
            double g = f / (double) frames;
            for (int c = 0; c < 2; c++) {
                int o = f * FRAME + c * 2;
                int v = (int) (((short) ((b[o + 1] << 8) | (b[o] & 0xFF))) * g);
                b[o] = (byte) v;
                b[o + 1] = (byte) (v >> 8);
            }
        }
    }

    // 0 dBFS ceiling: at or below 100 % nothing reaches it, so the sound passes through untouched
    // (just 2 ms later); above 100 % peaks are smoothly turned down instead of chopped off.
    private final dev.windowscraft.audio.dsp.Limiter directLimiter = new dev.windowscraft.audio.dsp.Limiter(AudioCapture.RATE, 0.9999f);
    private double lastDirectGain = -1;

    /** Direct sound: volume (gliding across the chunk when it changes) and a limiter for boosts. */
    private void directGain(byte[] buf, int len, double gain) {
        double from = lastDirectGain < 0 ? gain : lastDirectGain;
        lastDirectGain = gain;
        int frames = len / 4;
        for (int f = 0; f < frames; f++) {
            int i = f * 4;
            float g = (float) (from + (gain - from) * (f + 1) / frames);
            float l = ((short) ((buf[i + 1] << 8) | (buf[i] & 0xFF))) / 32768f * g;
            float r = ((short) ((buf[i + 3] << 8) | (buf[i + 2] & 0xFF))) / 32768f * g;
            directLimiter.process(l, r);
            int vl = Math.max(-32768, Math.min(32767, Math.round(directLimiter.lastL * 32768f)));
            int vr = Math.max(-32768, Math.min(32767, Math.round(directLimiter.lastR * 32768f)));
            buf[i] = (byte) vl; buf[i + 1] = (byte) (vl >> 8);
            buf[i + 2] = (byte) vr; buf[i + 3] = (byte) (vr >> 8);
        }
    }

    private void measure(byte[] buf, SourceDataLine line) {
        double sum = 0;
        for (int i = 0; i < CHUNK; i += 2) {
            double s = ((buf[i + 1] << 8) | (buf[i] & 0xFF)) / 32768.0;
            sum += s * s;
        }
        lastRms = Math.sqrt(sum / (CHUNK / 2.0));
        boolean loud = lastRms > 0.02;
        if (loud && !wasLoud) {
            long queuedNanos = line == null ? 0 : (long) ((line.getBufferSize() - line.available()) / 4.0 / AudioCapture.RATE * 1e9);
            lastOnsetNanos = System.nanoTime() + queuedNanos;
        }
        wasLoud = loud;
    }

    public void close() {
        AcousticsEngine.get().unregister(id);
    }
}
