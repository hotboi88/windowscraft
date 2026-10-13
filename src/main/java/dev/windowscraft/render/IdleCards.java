package dev.windowscraft.render;

import dev.windowscraft.capture.Frame;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Static "not sharing" cards shown on displays without a live picture. Cached per message. */
public final class IdleCards {
    public static final int W = 960, H = 540;
    private static final Map<String, Identifier> CACHE = new HashMap<>();
    private static int seq;

    public static Identifier get(String headline, String detail) {
        String key = headline + "\n" + detail;
        Identifier id = CACHE.get(key);
        if (id != null) return id;
        if (CACHE.size() > 48) { // status texts change; don't keep every one forever
            for (Identifier old : CACHE.values()) MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
            CACHE.clear();
        }
        CaptureTexture tex = new CaptureTexture(true);
        tex.upload(paint(headline, detail));
        id = Identifier.of("windowscraft", "idle/" + Integer.toHexString(key.hashCode()) + "_" + (seq++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        CACHE.put(key, id);
        return id;
    }

    private static Frame paint(String headline, String detail) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1A1B1E), 0, H, new Color(0x0E0F11)));
        g.fillRect(0, 0, W, H);
        // monitor glyph
        int gw = 150, gh = 96, gx = (W - gw) / 2, gy = 120;
        g.setColor(new Color(0x6E74F7));
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(gx, gy, gw, gh, 22, 22);
        g.drawLine(W / 2, gy + gh + 8, W / 2, gy + gh + 30);
        g.drawLine(W / 2 - 40, gy + gh + 32, W / 2 + 40, gy + gh + 32);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
        centered(g, headline, 340);
        g.setColor(new Color(0xB5BAC1));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
        centered(g, detail, 395);
        g.dispose();
        Frame f = Frame.allocate(W, H);
        f.data.asIntBuffer().put(((DataBufferInt) img.getRaster().getDataBuffer()).getData());
        f.data.limit(f.byteSize());
        f.alphaValid = true;
        return f;
    }

    private static void centered(Graphics2D g, String s, int y) {
        g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, y);
    }

    private IdleCards() {}
}
