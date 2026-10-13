package dev.windowscraft.render;

import dev.windowscraft.capture.Frame;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
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

    public static Identifier get(String headline, String detail) { return get(headline, detail, W / (double) H); }

    /** The card made in the shape of its screen ({@code aspect} = width / height), so it fills it edge to edge. */
    public static Identifier get(String headline, String detail, double aspect) {
        int[] size = size(aspect);
        String key = headline + "\n" + detail + "\n" + size[0] + "x" + size[1];
        Identifier id = CACHE.get(key);
        if (id != null) return id;
        if (CACHE.size() > 48) { // status texts change; don't keep every one forever
            for (Identifier old : CACHE.values()) MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
            CACHE.clear();
        }
        CaptureTexture tex = new CaptureTexture(true);
        tex.upload(paint(headline, detail, size[0], size[1]));
        id = Identifier.of("windowscraft", "idle/" + Integer.toHexString(key.hashCode()) + "_" + (seq++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        CACHE.put(key, id);
        return id;
    }

    /** A card size in that shape (the shorter side 540 pixels, the longer at most 2048). */
    public static int[] size(double aspect) {
        if (!(aspect > 0) || !Double.isFinite(aspect)) aspect = W / (double) H;
        int w, h;
        if (aspect >= 1) {
            w = (int) Math.min(2048, Math.round(H * aspect));
            h = (int) Math.max(2, Math.round(w / aspect));
        } else {
            h = (int) Math.min(2048, Math.round(H / aspect));
            w = (int) Math.max(2, Math.round(h * aspect));
        }
        return new int[]{w, h};
    }

    private static Frame paint(String headline, String detail, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // a plain dark screen, edge to edge (like a monitor that's on but showing nothing yet)
        g.setColor(new Color(0x0C0D0F));
        g.fillRect(0, 0, w, h);
        // everything in proportion to the screen's shorter side, centred
        double u = Math.min(w, h) / 540.0;
        int cy = h / 2;
        int gw = (int) (150 * u), gh = (int) (96 * u), gx = (w - gw) / 2, gy = cy - (int) (150 * u);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (10 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(gx, gy, gw, gh, (int) (22 * u), (int) (22 * u));
        g.drawLine(w / 2, gy + gh + (int) (8 * u), w / 2, gy + gh + (int) (30 * u));
        g.drawLine(w / 2 - (int) (40 * u), gy + gh + (int) (32 * u), w / 2 + (int) (40 * u), gy + gh + (int) (32 * u));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (52 * u)));
        centered(g, headline, w, cy + (int) (70 * u));
        g.setColor(new Color(0xB5BAC1));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) (32 * u)));
        centered(g, detail, w, cy + (int) (125 * u));
        g.dispose();
        Frame f = Frame.allocate(w, h);
        f.data.asIntBuffer().put(((DataBufferInt) img.getRaster().getDataBuffer()).getData());
        f.data.limit(f.byteSize());
        f.alphaValid = true;
        return f;
    }

    private static void centered(Graphics2D g, String s, int w, int y) {
        // (too long for a narrow screen: smaller, never cut)
        while (g.getFontMetrics().stringWidth(s) > w * 0.92 && g.getFont().getSize() > 8) g.setFont(g.getFont().deriveFont(g.getFont().getSize2D() * 0.9f));
        g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, y);
    }

    private IdleCards() {}
}
