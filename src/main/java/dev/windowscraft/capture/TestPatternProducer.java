package dev.windowscraft.capture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Animated 16:9 test card. Works on every OS, so the in-world rendering, picker and quality
 * settings can be tested without real capture. Drawn with Java2D into an INT_ARGB image whose
 * little-endian memory layout is exactly BGRA.
 */
public final class TestPatternProducer implements FrameProducer {
    private static final int W = 1280, H = 720;
    public static final int TIMING_BITS = 40, TIMING_X = 40, TIMING_Y = 560, TIMING_W = 30, TIMING_H = 16;

    /** Reads the timing strip back from a decoded BGRA frame of any size; -1 if it isn't there. */
    public static long readTimestamp(byte[] bgra, int width, int height) {
        return readStrip(width, height, (x, y) -> {
            int o = (y * width + x) * 4;
            return ((bgra[o] & 0xFF) + (bgra[o + 1] & 0xFF) + (bgra[o + 2] & 0xFF)) / 3;
        });
    }

    /** The same from an NV12 frame (its brightness plane comes first). */
    public static long readTimestampLuma(byte[] nv12, int width, int height) {
        return readStrip(width, height, (x, y) -> lumaToLum(nv12[y * width + x]));
    }

    /** A video brightness byte (16–235) as 0–255. */
    public static int lumaToLum(byte y) {
        return Math.max(0, Math.min(255, ((y & 0xFF) - 16) * 255 / 219));
    }

    private static long readStrip(int width, int height, java.util.function.IntBinaryOperator lumAt) {
        long v = 0;
        for (int i = 0; i < TIMING_BITS; i++) {
            int x = (int) ((TIMING_X + i * TIMING_W + TIMING_W / 2.0) * width / W);
            int y = (int) ((TIMING_Y + TIMING_H / 2.0) * height / H);
            int lum = lumAt.applyAsInt(x, y);
            if (lum > 90 && lum < 165) return -1; // grey: not our strip
            v = (v << 1) | (lum >= 128 ? 1 : 0);
        }
        long now = System.currentTimeMillis();
        long full = (now & ~((1L << TIMING_BITS) - 1)) | v;
        return Math.abs(now - full) < 60_000 ? full : -1;
    }
    private static final Color[] BARS = {
            new Color(0xC0C0C0), new Color(0xC0C000), new Color(0x00C0C0), new Color(0x00C000),
            new Color(0xC000C0), new Color(0xC00000), new Color(0x0000C0)};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SS");

    private final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    private final long start = System.nanoTime();
    private long frameCount;
    private BufferedImage scaled;

    @Override
    public Frame grab(FramePool pool, CaptureSettings settings) {
        double t = (System.nanoTime() - start) / 1e9;
        paint(image, t, ++frameCount, settings.fps());
        int[] size = settings.outputSize(W, H);
        BufferedImage src = image;
        if (size[0] != W || size[1] != H) {
            if (scaled == null || scaled.getWidth() != size[0] || scaled.getHeight() != size[1]) {
                scaled = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            }
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, size[0], size[1], null);
            g.dispose();
            src = scaled;
        }
        return toFrame(src, pool.acquire(src.getWidth(), src.getHeight()));
    }

    public static Frame still(int maxW, int maxH) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        paint(img, 1.25, 0, 0);
        double s = Math.min(maxW / (double) W, maxH / (double) H);
        int w = Math.max(1, (int) (W * s)), h = Math.max(1, (int) (H * s));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return toFrame(small, Frame.allocate(w, h));
    }

    static Frame toFrame(BufferedImage img, Frame frame) {
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        frame.data.clear();
        frame.data.asIntBuffer().put(px, 0, frame.width * frame.height);
        frame.data.limit(frame.byteSize());
        frame.alphaValid = true;
        return frame;
    }

    /** Tests: "like a movie" — a detailed picture that pans, with film grain and a scene cut every 5 s (so the stream uses its full data rate). */
    public static volatile boolean MOVIE = Boolean.getBoolean("windowscraft.test.movie");
    private static BufferedImage scene;
    private static int sceneNo = -1;

    private static long grainSeed = 88172645463325252L;

    private static void paintMovie(Graphics2D g, BufferedImage img, double t, long frame) {
        int no = (int) (t / 5);
        if (no != sceneNo || scene == null) {
            sceneNo = no;
            java.util.Random r = new java.util.Random(no * 7919L + 13);
            BufferedImage small = new BufferedImage(200, 56, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 56; y++) for (int x = 0; x < 200; x++) small.setRGB(x, y, r.nextInt(0xFFFFFF));
            scene = new BufferedImage(W * 2, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = scene.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(small, 0, 0, W * 2, H, null);
            sg.dispose();
        }
        int off = (int) ((t % 5) * 120); // panning, 120 px a second
        g.drawImage(scene, -off, 0, null);
        // film grain: fresh on every picture, so the encoder works as hard as on a real film
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        long x = grainSeed ^ frame * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 160_000; i++) {
            x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            int at = (int) ((x >>> 33) % px.length);
            px[at] ^= (int) (x & 0x1F1F1F);
        }
    }

    private static void paint(BufferedImage img, double t, long frame, int fps) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1E1F22), 0, H, new Color(0x111214)));
        g.fillRect(0, 0, W, H);
        if (MOVIE && fps > 0) paintMovie(g, img, t, frame);

        int barW = W / BARS.length;
        for (int i = 0; i < BARS.length; i++) {
            g.setColor(BARS[i]);
            g.fillRect(i * barW, 0, barW + 1, 120);
        }
        // grey ramp, useful for judging gamma/brightness on the wall
        for (int i = 0; i < 16; i++) {
            int v = i * 17;
            g.setColor(new Color(v, v, v));
            g.fillRect(i * W / 16, H - 60, W / 16 + 1, 60);
        }
        // moving ball shows motion smoothness / frame pacing
        double x = (Math.sin(t * 1.3) * 0.5 + 0.5) * (W - 160) + 80;
        double y = (Math.cos(t * 0.9) * 0.5 + 0.5) * (H - 360) + 200;
        g.setColor(new Color(0x6E74F7));
        g.fillOval((int) x - 50, (int) y - 50, 100, 100);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4));
        g.drawOval((int) x - 50, (int) y - 50, 100, 100);
        // sweeping line
        int sx = (int) ((t * 240) % W);
        g.setColor(new Color(255, 255, 255, 90));
        g.fillRect(sx, 120, 3, H - 180);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        drawCentered(g, "WindowsCraft", W / 2, 240);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
        g.setColor(new Color(0xB5BAC1));
        drawCentered(g, "Test pattern", W / 2, 290);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 44));
        g.setColor(Color.WHITE);
        drawCentered(g, LocalTime.now().format(CLOCK), W / 2, 420);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 26));
        g.setColor(new Color(0xB5BAC1));
        drawCentered(g, fps > 0 ? "frame " + frame + "  ·  target " + fps + " fps" : "preview", W / 2, 470);
        // sync flash: white square top-right during the first 100 ms of every second, exactly when
        // the test tone beeps, so audio/video offset can be measured (or eyeballed) on a viewer
        if (fps > 0 && Math.floorMod(System.currentTimeMillis(), 1000) < 100) {
            g.setColor(Color.WHITE);
            g.fillRect(W - 140, 140, 120, 120);
        }
        // timing strip: the capture time (low 40 bits of epoch ms) as black/white blocks, so a
        // viewer on the same clock can measure glass-to-glass delay exactly
        if (fps > 0) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < TIMING_BITS; i++) {
                g.setColor(((now >> (TIMING_BITS - 1 - i)) & 1) == 1 ? Color.WHITE : Color.BLACK);
                g.fillRect(TIMING_X + i * TIMING_W, TIMING_Y, TIMING_W, TIMING_H);
            }
        }
        // corner markers make orientation/mirroring mistakes obvious
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.setColor(Color.WHITE);
        g.drawString("TOP-LEFT", 20, 160);
        String br = "BOTTOM-RIGHT";
        g.drawString(br, W - 20 - g.getFontMetrics().stringWidth(br), H - 80);
        g.dispose();
    }

    private static void drawCentered(Graphics2D g, String s, int cx, int baseline) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, baseline);
    }

    @Override
    public void close() {}
}
