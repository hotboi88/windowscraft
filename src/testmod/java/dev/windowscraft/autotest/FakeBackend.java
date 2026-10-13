package dev.windowscraft.autotest;

import dev.windowscraft.capture.CaptureBackend;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FramePool;
import dev.windowscraft.capture.FrameProducer;
import dev.windowscraft.capture.SourceInfo;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/** Dev-only stand-in for the Windows backend so the full picker can be exercised on Linux. */
final class FakeBackend implements CaptureBackend {
    record App(String title, String exe, int color, int w, int h) {}

    static final App[] APPS = {
            new App("YouTube - Lofi beats to study to - Google Chrome", "chrome.exe", 0xE53935, 1920, 1080),
            new App("Discord", "Discord.exe", 0x5865F2, 1600, 900),
            new App("Spotify Premium", "Spotify.exe", 0x1DB954, 1280, 800),
            new App("WindowsCraft — Visual Studio Code", "Code.exe", 0x007ACC, 1920, 1080),
            new App("Steam", "steamwebhelper.exe", 0x1B2838, 1280, 720),
            new App("File Explorer", "explorer.exe", 0xF4B400, 1024, 768),
            new App("Notepad", "notepad.exe", 0x607D8B, 900, 1100),
    };

    @Override public boolean isSupported() { return true; }
    @Override public String unsupportedReason() { return null; }

    @Override
    public List<SourceInfo> listWindows() {
        List<SourceInfo> out = new ArrayList<>();
        for (int i = 0; i < APPS.length; i++) {
            App a = APPS[i];
            out.add(new SourceInfo(SourceInfo.Kind.WINDOW, "win:fake" + i, a.title, a.exe, 1000 + i, 0, 0, a.w, a.h, a.exe, false));
        }
        return out;
    }

    @Override
    public List<SourceInfo> listScreens() {
        return List.of(
                new SourceInfo(SourceInfo.Kind.SCREEN, "mon:\\\\.\\DISPLAY1", "Screen 1 (main)", "2560×1440", 0, 0, 0, 2560, 1440, "", true),
                new SourceInfo(SourceInfo.Kind.SCREEN, "mon:\\\\.\\DISPLAY2", "Screen 2", "1920×1080", 0, 2560, 0, 1920, 1080, "", false));
    }

    static App app(SourceInfo s) {
        if (s.kind() == SourceInfo.Kind.SCREEN) return new App(s.title(), "", 0x2B2D31, s.width(), s.height());
        return APPS[(int) (s.handle() - 1000)];
    }

    @Override
    public Frame thumbnail(SourceInfo source, int maxW, int maxH) {
        App a = app(source);
        double s = Math.min(maxW / (double) a.w, maxH / (double) a.h);
        BufferedImage img = paint(a, Math.max(1, (int) (a.w * s)), Math.max(1, (int) (a.h * s)), 0);
        return toFrame(img);
    }

    @Override
    public Frame icon(SourceInfo source) {
        App a = app(source);
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(a.color));
        g.fillRoundRect(1, 1, 30, 30, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(a.title.substring(0, 1), 9, 23);
        g.dispose();
        return toFrame(img);
    }

    @Override
    public FrameProducer open(SourceInfo source) {
        App a = app(source);
        return new FrameProducer() {
            final long start = System.nanoTime();
            @Override
            public Frame grab(FramePool pool, CaptureSettings settings) {
                int[] o = settings.outputSize(a.w, a.h);
                return toFrame(paint(a, o[0], o[1], (System.nanoTime() - start) / 1e9));
            }
            @Override public void close() {}
        };
    }

    /** A crude "app window": coloured title bar, sidebar, content lines, and a moving dot. */
    static BufferedImage paint(App a, int w, int h, double t) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float u = w / 100f;
        g.setColor(new Color(0xF5F5F7));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(a.color));
        g.fillRect(0, 0, w, (int) (7 * u));
        g.setColor(new Color(0xE3E3E8));
        g.fillRect(0, (int) (7 * u), (int) (22 * u), h);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, (int) (3.6 * u))));
        g.drawString(a.title, (int) (2 * u), (int) (4.9 * u));
        g.setColor(new Color(0x9EA0A6));
        for (int i = 0; i < 9; i++) g.fillRoundRect((int) (3 * u), (int) ((11 + i * 5) * u), (int) (15 * u), (int) (2.2 * u), 4, 4);
        g.setColor(new Color(0x2B2D31));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, (int) (6 * u))));
        g.drawString(a.exe.isEmpty() ? a.title : a.exe.replace(".exe", ""), (int) (27 * u), (int) (20 * u));
        g.setColor(new Color(0xC7C8CE));
        for (int i = 0; i < 6; i++) g.fillRoundRect((int) (27 * u), (int) ((27 + i * 5.5) * u), (int) ((60 - (i % 3) * 12) * u), (int) (2.6 * u), 6, 6);
        g.setColor(new Color(a.color));
        int dx = (int) ((27 + (Math.sin(t * 2) * 0.5 + 0.5) * 60) * u);
        g.fillOval(dx, (int) (h - 12 * u), (int) (6 * u), (int) (6 * u));
        g.dispose();
        return img;
    }

    static Frame toFrame(BufferedImage img) {
        Frame f = Frame.allocate(img.getWidth(), img.getHeight());
        f.data.asIntBuffer().put(((DataBufferInt) img.getRaster().getDataBuffer()).getData());
        f.data.limit(f.byteSize());
        f.alphaValid = true;
        return f;
    }
}
