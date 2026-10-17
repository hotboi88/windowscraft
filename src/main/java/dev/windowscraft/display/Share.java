package dev.windowscraft.display;

import dev.windowscraft.audio.AcousticModel;
import dev.windowscraft.audio.AudioHub;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.render.DisplayTexture;
import dev.windowscraft.stream.StreamPublisher;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Identifier;

/**
 * One thing being shared — one capture, one texture, one network stream — shown on one or more
 * walls. Putting the same window on a second wall mirrors it instead of capturing it twice.
 */
public final class Share {
    volatile SourceInfo source;
    final CaptureSession session;
    StreamPublisher publisher;
    final List<Display> displays = new ArrayList<>();
    final AudioHub audio;
    LocalSound localSound;                     // while the sharer hears it from the wall
    AcousticModel.Environment environment = AcousticModel.Environment.OPEN_AIR;
    Display audibleWall;
    /** How loud you hear it from the wall yourself (Game audio); 1 = normal, up to 5. */
    public volatile double localVolume = dev.windowscraft.config.ClientSettings.ownVolume();
    /** Direct GPU sharing for this share's walls (Windows), or null. */
    dev.windowscraft.capture.GpuShare gpu;
    /** It stopped working; tried again when {@link #gpuRetry} says (see DisplayManager.tickGpuSharing). */
    boolean gpuFailed;
    final dev.windowscraft.capture.Backoff gpuRetry = new dev.windowscraft.capture.Backoff(5, 300);
    private long gpuFeedingSince;
    int gpuFps;
    /** What the GPU helper was last told (the performance settings lower it). */
    int gpuSentFps;
    private volatile boolean gpuFeeding;
    /** "As sharp as it looks": how many times your wall's copy is halved. */
    final CopySize copySize = new CopySize();
    private final DisplayTexture texture;
    private long lastPollFrame = -1;
    private Identifier lastPolled;

    Share(SourceInfo source, CaptureSession session, String textureKey) {
        this.source = source;
        this.session = session;
        this.audio = new AudioHub(source);
        this.texture = new DisplayTexture(textureKey);
    }

    public AudioHub audio() { return audio; }
    DisplayTexture texture() { return texture; }

    /** Tests: the wall's OpenGL texture right now. */
    public int glTextureId() { return texture.glId(); }
    /** For tests/diagnostics: how much in-game sound the sharer has heard from the wall. */
    public long localSoundBytesPlayed() { return localSound == null ? -1 : localSound.player().bytesPlayed(); }
    public SourceInfo source() { return source; }

    public CaptureSession session() { return session; }
    public StreamPublisher publisher() { return publisher; }
    public List<Display> displays() { return displays; }

    /** Render thread: newest frame uploaded once per rendered frame, however many walls show it. */
    /** Render thread: the texture to draw; {@code upload} false = keep showing the last picture (no work this frame). */
    Identifier pollTexture(long renderFrame, boolean upload) {
        if (!upload) return lastPolled;
        return pollTexture(renderFrame);
    }

    // ---- is it being looked at, and how big does it look (for the performance settings) ----
    private volatile long lastSeenNanos = System.nanoTime(), viewPxAt;
    private volatile double viewPx = 1e9;

    /** Render thread: one of its walls was in view this frame (or not), this many pixels tall on screen. */
    void noteView(boolean seen, double px) {
        if (!seen) return;
        long now = System.nanoTime();
        lastSeenNanos = now;
        if (now - viewPxAt > 500_000_000L || px > viewPx) {
            viewPx = px;
            viewPxAt = now;
        }
    }

    /** Was any of its walls on your screen in the last half second? */
    public boolean seenRecently() { return System.nanoTime() - lastSeenNanos < 500_000_000L; }

    /** How tall its biggest wall looks on your screen right now, in pixels. */
    public double viewPx() { return viewPx; }

    Identifier pollTexture(long renderFrame) {
        if (renderFrame != lastPollFrame) {
            lastPollFrame = renderFrame;
            lastPolled = null;
            // 1. direct GPU sharing: graphics memory straight into the wall
            if (gpu != null && !gpuFailed) {
                if (gpu.failure() != null) {
                    gpuFailed();
                } else if (gpu.delivering()) {
                    try {
                        lastPolled = texture.pollGpu(gpu, session::looksBlack);
                    } catch (Throwable t) {
                        gpu.fail(String.valueOf(t.getMessage()));
                        gpuFailed();
                    }
                }
            }
            gpuFeeding = lastPolled != null;
            if (!gpuFeeding) gpuFeedingSince = 0;
            else if (gpuFeedingSince == 0) gpuFeedingSince = System.nanoTime();
            else if (gpuRetry.fails() > 0 && System.nanoTime() - gpuFeedingSince > 60_000_000_000L) gpuRetry.reset(); // (a good run)
            if (lastPolled == null) {
                // 2. GPU capture: the preview FFmpeg sends back; 3. otherwise our own CPU capture
                dev.windowscraft.capture.FrameSource src = publisher != null ? publisher.previewSource() : null;
                lastPolled = texture.poll(src != null ? src : session);
                fromPreview = src != null;
            }
        }
        return lastPolled;
    }

    /**
     * Direct GPU sharing stopped: the wall takes the CPU capture meanwhile, and GPU sharing is tried
     * again a few seconds later (then less often). It used to be given up for good: a wall whose
     * monitor came on while Windows rearranged its screens stayed on the slow capture for hours.
     */
    private void gpuFailed() {
        gpuFailed = true;
        gpuFeeding = false;
        gpuFeedingSince = 0;
        dev.windowscraft.WindowsCraftClient.LOGGER.info("Direct GPU sharing of '{}' stopped ({}); trying it again in {} s", source.title(),
                gpu == null ? "?" : gpu.failure(), gpuRetry.failed(System.nanoTime()));
        texture.dropGpu();
        if (publisher != null && publisher.gpuActive()) publisher.restartPipeline(); // bring FFmpeg's preview back
    }

    /**
     * The same source somewhere else now (an in-game monitor Windows moved or resized): the share
     * goes on — its sound, the stream to others, every wall of it — and only the pictures are taken
     * from where it is now. (Stopping and starting it put its apps' sound on your speakers for a
     * moment and ended the stream to everyone watching.)
     */
    void retarget(SourceInfo fresh) {
        source = fresh;
        session.retarget(fresh);
        if (gpu != null) { // (started again where it is now, next tick)
            gpu.stop();
            gpu = null;
        }
        texture.dropGpu();
        gpuFeeding = false;
        gpuFeedingSince = 0;
        gpuFailed = false; // (a new place: a fresh try)
        gpuRetry.reset();
        if (publisher != null) publisher.retarget(fresh);
    }

    /** The wall shows FFmpeg's preview (the whole window, fitted into the stream's shape) rather than just the app's content. */
    private volatile boolean fromPreview;

    /** Where the app's client area is in your wall's picture. */
    public dev.windowscraft.client.Box wallBox() {
        if (!fromPreview || gpuFeeding || source.kind() != dev.windowscraft.capture.SourceInfo.Kind.WINDOW) return dev.windowscraft.client.Box.FULL;
        return dev.windowscraft.client.Box.frameFit(texture.width(), texture.height(), dev.windowscraft.capture.win.WinGeom.geometry(source.handle()));
    }

    /** For the report: how this share gets to your wall and to the stream, and how fast. */
    public String describe() {
        String wall = gpuFeeding ? "direct GPU sharing" : fromPreview ? "stream preview (through the CPU)" : "CPU capture (" + session.producerName()
                + (session.outputHeight() > 0 && session.outputHeight() < session.fullHeight() ? ", " + session.outputWidth() + " × " + session.outputHeight() + " of " + session.fullWidth() + " × " + session.fullHeight() : "") + ")";
        String stream = publisher == null ? "not streamed" : publisher.status() + (publisher.gpuActive() ? " [GPU capture+encode]" : " [CPU frames]");
        return String.format(java.util.Locale.ROOT, "'%s' (%s): wall via %s; capture %.0f pictures/s, %.1f ms each; helper fps %d; "
                        + "on screen %s, %.0f px tall; stream: %s",
                source.title(), source.kind(), wall, session.measuredFps(), session.avgCaptureMs(), gpu == null ? 0 : gpuSentFps,
                seenRecently() ? "yes" : "no", viewPx, stream);
    }

    /** Your wall's copy is this size, of the picture's full size (null: full size). */
    public int[] smallerCopy() {
        // (what the wall has now: a still screen gets its smaller copy with its next change)
        if (gpuFeeding || fromPreview || session.outputHeight() <= 0 || session.outputHeight() >= session.fullHeight()) return null;
        return new int[]{session.outputWidth(), session.outputHeight(), session.fullWidth(), session.fullHeight()};
    }

    /** Is your wall being fed by direct GPU sharing right now? */
    public boolean gpuFeeding() { return gpuFeeding; }

    int textureWidth() { return texture.width(); }
    int textureHeight() { return texture.height(); }
    void releaseTexture() { texture.release(); }
}
