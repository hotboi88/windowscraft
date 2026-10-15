import java.awt.BufferCapabilities;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferStrategy;
import java.util.concurrent.locks.LockSupport;

/**
 * Step 0 benchmark window (dev only): an ordinary desktop window, 1280×720 without a frame, that
 * changes every frame like a video would, with the test pattern's timing strip (the time it was
 * drawn, as 40 black/white cells at the test pattern's spot) so a viewer can tell exactly how old
 * each picture is after GPU capture, encoding, the relay and decoding.
 *
 * <p>Run: {@code java tools/bench/BenchWindow.java [fps] [x y] [width height [resizeEvery]]} (title
 * "WindowsCraft bench window"; 1280×720 unless given — the strip scales with it, as the test
 * pattern's does). {@code resizeEvery} seconds: the window turns ¾ as big and back, again and again
 * (a shared window being resized: new shared pictures each time).
 * Every mouse press it gets is printed ("pressed <System.nanoTime()>"): the input benchmark
 * matches them with the clicks the viewer sent (tools/bench/clicks.py).
 */
public final class BenchWindow {
    // the test pattern's timing strip (TestPatternProducer.TIMING_*), on a 1280×720 picture
    static final int REF_W = 1280, REF_H = 720, BITS = 40, TX = 40, TY = 560, TW = 30, TH = 16;
    static int W = REF_W, H = REF_H;

    public static void main(String[] args) throws Exception {
        int fps = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        int x = args.length > 2 ? Integer.parseInt(args[1]) : 40, y = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        if (args.length > 4) {
            W = Integer.parseInt(args[3]);
            H = Integer.parseInt(args[4]);
        }
        double resizeEvery = args.length > 5 ? Double.parseDouble(args[5]) : 0;
        double sx = W / (double) REF_W, sy = H / (double) REF_H;
        System.setProperty("sun.java2d.uiScale", "1"); // pixel for pixel, whatever Windows' scale
        // plain GDI drawing: Java's Direct3D pipeline stops presenting while the window is fully
        // covered, and then Windows Graphics Capture has nothing new to capture
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.noddraw", "true");
        Frame f = new Frame("WindowsCraft bench window");
        f.setUndecorated(true);
        Canvas c = new Canvas();
        c.setPreferredSize(new java.awt.Dimension(W, H));
        c.setIgnoreRepaint(true);
        f.add(c);
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                System.out.println("pressed " + System.nanoTime());
                System.out.flush();
            }
        });
        f.pack();
        f.setLocation(x, y);
        f.setVisible(true);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { System.exit(0); }
        });
        c.createBufferStrategy(2);
        BufferStrategy bs = c.getBufferStrategy();
        Font big = new Font(Font.MONOSPACED, Font.BOLD, (int) (64 * sy)), small = new Font(Font.SANS_SERIF, Font.PLAIN, (int) (22 * sy));
        long start = System.nanoTime(), interval = 1_000_000_000L / fps, frame = 0;
        boolean shrunk = false;
        while (true) {
            long due = start + frame * interval;
            long wait = due - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait);
            frame++;
            double t = (System.nanoTime() - start) / 1e9;
            if (resizeEvery > 0 && (int) (t / resizeEvery) % 2 == 1 != shrunk) {
                shrunk = !shrunk;
                c.setPreferredSize(new java.awt.Dimension(shrunk ? W * 3 / 4 : W, shrunk ? H * 3 / 4 : H));
                f.pack();
                System.out.println("resized " + (shrunk ? W * 3 / 4 + "x" + H * 3 / 4 : W + "x" + H));
                System.out.flush();
            }
            Graphics2D g = (Graphics2D) bs.getDrawGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // moving colour bands (the whole picture changes, like video)
            for (int i = 0; i < 16; i++) {
                float hue = (float) ((i / 16.0 + t * 0.1) % 1.0);
                g.setColor(Color.getHSBColor(hue, 0.6f, 0.55f));
                g.fillRect(i * W / 16, 0, W / 16 + 1, (int) (520 * sy));
            }
            // a bouncing box and scrolling text (detail the encoder has to keep sharp)
            int box = (int) (200 * sy);
            int bx = (int) ((Math.sin(t * 1.7) * 0.5 + 0.5) * (W - box)), by = (int) ((Math.cos(t * 1.3) * 0.5 + 0.5) * 320 * sy);
            g.setColor(Color.WHITE);
            g.fillRect(bx, by, box, box);
            g.setColor(Color.BLACK);
            g.setFont(small);
            g.drawString("frame " + frame, bx + box / 10, by + box / 2);
            g.setColor(new Color(20, 20, 20));
            g.fillRect(0, (int) (520 * sy), W, H - (int) (520 * sy));
            g.setColor(new Color(230, 230, 230));
            g.setFont(small);
            int scroll = (int) (t * 120 * sx) % (int) (1400 * sx);
            g.drawString("The quick brown fox jumps over the lazy dog · 0123456789 · WindowsCraft Step 0 benchmark", W - scroll, (int) (540 * sy));
            g.setFont(big);
            g.drawString(String.format("%.2f s", t), (int) (900 * sx), (int) (700 * sy));
            // the timing strip: when this picture was drawn (ms, low 40 bits), black = 0, white = 1
            long now = System.currentTimeMillis();
            for (int i = 0; i < BITS; i++) {
                boolean one = ((now >> (BITS - 1 - i)) & 1) != 0;
                g.setColor(one ? Color.WHITE : Color.BLACK);
                int x0 = (int) Math.round((TX + i * TW) * sx), x1 = (int) Math.round((TX + (i + 1) * TW) * sx);
                g.fillRect(x0, (int) Math.round(TY * sy), x1 - x0, (int) Math.round(TH * sy));
            }
            g.dispose();
            bs.show();
            java.awt.Toolkit.getDefaultToolkit().sync();
        }
    }
}
