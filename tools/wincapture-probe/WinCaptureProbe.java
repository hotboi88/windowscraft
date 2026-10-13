import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FramePool;
import dev.windowscraft.capture.FrameProducer;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.capture.win.WinGdiBackend;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Standalone check of the Windows capture code, outside Minecraft. Lists windows and screens,
 * captures each screen and the first few windows to PNG files, and times sustained capture.
 * Usage: java -cp windowscraft.jar;jna.jar;jna-platform.jar WinCaptureProbe.java [outDir]
 */
public class WinCaptureProbe {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "probe-out");
        out.mkdirs();
        WinGdiBackend b = new WinGdiBackend();
        List<SourceInfo> screens = b.listScreens();
        List<SourceInfo> windows = b.listWindows();
        System.out.println("screens: " + screens.size());
        for (SourceInfo s : screens) System.out.println("  " + s);
        System.out.println("windows: " + windows.size());
        for (SourceInfo s : windows) System.out.println("  " + s.title() + " | " + s.subtitle() + " | " + s.width() + "x" + s.height());

        CaptureSettings full = new CaptureSettings(0, 30, true), p720 = new CaptureSettings(720, 30, true);
        int n = 0;
        for (SourceInfo s : screens) {
            save(grab(b, s, full), new File(out, "screen" + (n) + ".png"));
            save(grab(b, s, p720), new File(out, "screen" + (n++) + "_720.png"));
            time(b, s, full);
        }
        n = 0;
        for (SourceInfo w : windows) {
            if (n >= 4) break;
            save(grab(b, w, full), new File(out, "window" + n + ".png"));
            save(b.thumbnail(w, 384, 216), new File(out, "window" + n + "_thumb.png"));
            save(b.icon(w), new File(out, "window" + n + "_icon.png"));
            time(b, w, full);
            n++;
        }
        System.out.println("PROBE DONE");
    }

    static Frame grab(WinGdiBackend b, SourceInfo s, CaptureSettings cs) throws Exception {
        try (FrameProducer p = b.open(s)) {
            Frame f = p.grab(new FramePool(), cs);
            if (f == null) System.out.println("  (no frame: " + p.idleReason() + ") for " + s.title());
            return f;
        }
    }

    static void time(WinGdiBackend b, SourceInfo s, CaptureSettings cs) throws Exception {
        FramePool pool = new FramePool();
        try (FrameProducer p = b.open(s)) {
            int frames = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 30; i++) {
                Frame f = p.grab(pool, cs);
                if (f != null) { frames++; pool.release(f); }
            }
            double ms = (System.nanoTime() - t0) / 1e6 / 30;
            System.out.printf("  timing %-30s %d/30 frames, %.2f ms per grab%n", s.title().length() > 30 ? s.title().substring(0, 30) : s.title(), frames, ms);
        }
    }

    static void save(Frame f, File file) throws Exception {
        if (f == null) return;
        BufferedImage img = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[f.width * f.height];
        f.data.position(0);
        f.data.asIntBuffer().get(px);
        if (!f.alphaValid) for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
        img.setRGB(0, 0, f.width, f.height, px, 0, f.width);
        ImageIO.write(img, "png", file);
        System.out.println("  wrote " + file.getName() + " " + f.width + "x" + f.height);
    }
}
