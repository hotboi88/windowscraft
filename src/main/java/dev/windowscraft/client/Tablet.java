package dev.windowscraft.client;

import dev.windowscraft.block.Monitors;
import dev.windowscraft.block.TabletItem;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.monitor.InGameMonitors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

/**
 * Your tablet: a hand-held in-game monitor of its own. Right-click turns it on (the first time)
 * and holds it up in front of you, big: your real mouse and keyboard work it, like sitting at a
 * monitor. Esc puts it down — it keeps going in your hand (a video keeps playing, its sound comes
 * from you). Sneak + right-click turns it off. Only you see what's on it.
 */
public final class Tablet {
    private static boolean raised, raiseWhenOn;
    /** Where your pointer was on it when you put it down (it's there again next time). */
    private static double[] lastSpot = {0.5, 0.5};
    /** First person with a shader pack and "screens without shaders": the picture's corners on your screen, drawn over the finished frame. */
    private static volatile float[] handScreen;
    private static volatile Identifier handTex;
    /** When you asked for it to come on (it may take a moment: the helper starts, the driver is checked). */
    private static long askedAt;
    /** Since when no tablet has been anywhere in your inventory (0: there is one). */
    private static long goneSince;
    /** Without a tablet for this long, its monitor turns off (its apps close, like turning it off). */
    private static final long GONE_NANOS = 60_000_000_000L;
    /** How big it is held up: its share of your screen's height (Alt + scroll changes it). */
    private static double size = 1.0; // (the whole screen, its frame around it)
    private static long frame;
    /** Set while the game draws an item in your own hand (first or third person). */
    private static final ThreadLocal<Boolean> OWN_HAND = ThreadLocal.withInitial(() -> false);

    public static boolean raised() { return raised; }

    public static void register() {
        TabletItem.onUse = (player, sneaking) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> use(mc, sneaking));
        };
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(Tablet::renderHandScreen);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(Tablet::renderHeld);
    }

    /** Your tablet's screen, if you've used one in this world (null otherwise). */
    public static Display display() {
        for (Display d : DisplayManager.get().all()) if (d.tablet) return d;
        return null;
    }

    private static ItemStack held(MinecraftClient mc) {
        if (mc.player == null) return null;
        if (Monitors.isTablet(mc.player.getMainHandStack())) return mc.player.getMainHandStack();
        if (Monitors.isTablet(mc.player.getOffHandStack())) return mc.player.getOffHandStack();
        return null;
    }

    static void use(MinecraftClient mc, boolean sneaking) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (!InGameMonitors.supported()) {
            hint(mc, "A tablet is an in-game monitor you hold, so it needs Windows");
            return;
        }
        Display d = DisplayManager.get().tablet(mc);
        if (d == null) return;
        offByYou = sneaking;
        if (sneaking) {
            raiseWhenOn = false;
            if (InGameMonitors.isOn(d)) {
                lower();
                InGameMonitors.turnOff(d);
                hint(mc, "Tablet off (its apps closed)");
            }
            return;
        }
        if (raised) {
            lower();
            return;
        }
        follow(mc, d);
        if (InGameMonitors.isOn(d)) raise(mc, d);
        else if (!InGameMonitors.starting(d)) {
            raiseWhenOn = true;
            askedAt = System.nanoTime();
            InGameMonitors.turnOn(mc, d.dimension, d.rect, d);
        }
    }

    private static void raise(MinecraftClient mc, Display d) {
        if (TheaterMode.active() || InGameMonitors.seated() != null) return;
        KeyBinding.unpressAll(); // from now on, keys belong to the tablet
        raised = true;
        ScreenControl.nativePointer(mc, true);
        if (!InGameMonitors.seat(d, lastSpot[0], lastSpot[1])) { // (your pointer where you left it)
            raised = false;
            ScreenControl.nativePointer(mc, false);
            return;
        }
        hint(mc, "Esc puts it down · hold Alt to look around · Alt + scroll: bigger or smaller");
    }

    /** Puts it down (it keeps going in your hand). */
    public static void lower() {
        if (!raised) return;
        double[] at = InGameMonitors.pointerOnSeat();
        if (at != null && at[0] >= 0 && at[0] <= 1 && at[1] >= 0 && at[1] <= 1) lastSpot = at;
        raised = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        InGameMonitors.unseat();
        ScreenControl.nativePointer(mc, false);
        KeyBinding.updatePressedStates();
    }

    /** Alt + scroll while holding it up: bigger or smaller. */
    public static void zoom(double notches) {
        size = Math.max(0.35, Math.min(1.0, size + notches * 0.05));
    }

    /** Its screen goes wherever you go (so its sound comes from you, and nothing pauses it). */
    private static void follow(MinecraftClient mc, Display d) {
        if (mc.player == null || mc.world == null) return;
        String dim = mc.world.getRegistryKey().getValue().toString();
        BlockPos at = BlockPos.ofFloored(mc.player.getEyePos());
        int turn = Math.floorMod(Math.round((mc.player.getYaw() + 180) / 45f), 8); // facing you
        if (!dim.equals(d.dimension)) d.dimension = dim;
        if (!d.rect.min().equals(at) || d.rect.turn() != turn) d.rect = WallRect.monitor(at, turn);
    }

    /** Client tick. */
    /** You turned it off yourself (sneak + right-click): it waits for your next right-click. */
    private static boolean offByYou;
    private static long heldSince;
    private static boolean autoTried;

    public static void tick(MinecraftClient mc) {
        // your tablet is yours alone, and it's there whenever you hold one: holding a tablet for a
        // moment turns your tablet's monitor on (one per person, whichever tablet you hold)
        // (once each time you take one in your main hand — never over and over in the background:
        // if it couldn't come on, a right-click tries again)
        long now = System.nanoTime();
        boolean inHand = mc.player != null && Monitors.isTablet(mc.player.getMainHandStack());
        if (mc.player != null && mc.world != null && inHand && !offByYou && InGameMonitors.supported()) {
            if (heldSince == 0) heldSince = now;
            else if (!autoTried && now - heldSince > 1_000_000_000L && !InGameMonitors.anyStarting()) {
                autoTried = true;
                Display t = DisplayManager.get().tablet(mc);
                if (t != null && !InGameMonitors.isOn(t) && !InGameMonitors.starting(t)) {
                    follow(mc, t);
                    InGameMonitors.turnOn(mc, t.dimension, t.rect, t);
                }
            }
        } else {
            heldSince = 0;
            autoTried = false;
        }
        Display d = display();
        if (d == null) {
            lower();
            raiseWhenOn = false;
            return;
        }
        follow(mc, d);
        boolean on = InGameMonitors.isOn(d);
        if (raiseWhenOn && on) {
            raiseWhenOn = false;
            if (held(mc) != null && mc.currentScreen == null) raise(mc, d);
        } else if (raiseWhenOn && !InGameMonitors.starting(d) && System.nanoTime() - askedAt > 20_000_000_000L) {
            raiseWhenOn = false; // it didn't come on
        }
        // no tablet on you any more (dropped, died, put away): after a minute, it turns off
        boolean have = mc.player != null && mc.player.getInventory().containsAny(s -> Monitors.isTablet(s));
        if (!on || have) goneSince = 0;
        else if (goneSince == 0) {
            goneSince = System.nanoTime();
            hint(mc, "No tablet on you: it turns off in a minute (its apps close)");
        } else if (System.nanoTime() - goneSince > GONE_NANOS) {
            goneSince = 0;
            InGameMonitors.turnOff(d);
            hint(mc, "Tablet off (no tablet on you)");
        }
        if (raised && (held(mc) == null || !on || mc.currentScreen != null || InGameMonitors.seated() != d)) lower();
    }

    private static void hint(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(s).formatted(Formatting.GRAY), true);
    }

    private static String colorOf(ItemStack s) {
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return path.endsWith("_tablet") ? path.substring(0, path.length() - "_tablet".length()) : "black";
    }

    // ---- held up: big, in front of you ---------------------------------------------------------

    /** The shape of the tablet's screen: its monitor's (wider or narrower as you pick its size). */
    private static double aspect(Display d) {
        var mon = InGameMonitors.monitorOf(d);
        if (mon != null && mon.height() > 0) return mon.width() / (double) mon.height();
        return d.monitorH > 0 ? d.monitorW / (double) d.monitorH : 16 / 9.0;
    }

    /** One piece of the tablet's frame, from its item picture: (u, v) pixels of the 16×16 texture. */
    private interface Piece { void draw(float x0, float y0, float x1, float y1, int u, int v, int uw, int vh); }

    /**
     * The tablet's frame at any shape: the corners and edges of its item picture (a 1-pixel bevel)
     * stay the same size, only the middle stretches — so it's a real tablet of whatever shape
     * its screen has, not a squashed picture of one. {@code b} is the frame's thickness.
     */
    private static void frame(Piece p, float x0, float y0, float x1, float y1, float b, boolean middle) {
        // the tablet in the texture: columns 0..15, rows 2..13; its frame is 1 pixel wide
        p.draw(x0, y0, x0 + b, y0 + b, 0, 2, 1, 1);
        p.draw(x1 - b, y0, x1, y0 + b, 15, 2, 1, 1);
        p.draw(x0, y1 - b, x0 + b, y1, 0, 13, 1, 1);
        p.draw(x1 - b, y1 - b, x1, y1, 15, 13, 1, 1);
        p.draw(x0 + b, y0, x1 - b, y0 + b, 1, 2, 14, 1);
        p.draw(x0 + b, y1 - b, x1 - b, y1, 1, 13, 14, 1);
        p.draw(x0, y0 + b, x0 + b, y1 - b, 0, 3, 1, 10);
        p.draw(x1 - b, y0 + b, x1, y1 - b, 15, 3, 1, 10);
        if (middle) p.draw(x0 + b, y0 + b, x1 - b, y1 - b, 1, 3, 14, 10);
    }

    private static void renderHeld(DrawContext g, RenderTickCounter counter) {
        if (!raised) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Display d = display();
        ItemStack stack = held(mc);
        if (d == null || stack == null) return;
        int sw = g.getScaledWindowWidth(), sh = g.getScaledWindowHeight();
        double a = aspect(d);
        // as tall as you've made it (Alt + scroll), and the screen exactly its monitor's shape
        float h = (float) (sh * size), b = h / 14f, w = (float) ((h - 2 * b) * a) + 2 * b;
        if (w > sw) { // (as big as fits: the whole screen, frame and all)
            float k = sw / w;
            w *= k;
            h *= k;
            b *= k;
        }
        float x0 = (sw - w) / 2, y0 = (sh - h) / 2;
        Identifier frameTex = Identifier.of("windowscraft", "textures/item/" + colorOf(stack) + "_tablet.png");
        var m = g.getMatrices();
        Identifier tex = d.pollTexture(++frame, true);
        int tw = d.textureWidth(), th = d.textureHeight();
        boolean picture = tex != null && tw > 0 && th > 0;
        frame((fx0, fy0, fx1, fy1, u, v, uw, vh) -> {
            m.push();
            m.translate(fx0, fy0, 0);
            m.scale((fx1 - fx0) / uw, (fy1 - fy0) / vh, 1);
            g.drawTexture(frameTex, 0, 0, (float) u, (float) v, uw, vh, 16, 16);
            m.pop();
        }, x0, y0, x0 + w, y0 + h, b, !picture);
        float px = x0 + b, py = y0 + b, pw = w - 2 * b, ph = h - 2 * b;
        d.noteView(true, ph * mc.getWindow().getScaleFactor());
        if (picture) {
            m.push();
            m.translate(px, py, 0);
            m.scale(pw / tw, ph / th, 1);
            sharp(tex, () -> g.drawTexture(tex, 0, 0, 0f, 0f, tw, th, tw, th));
            m.pop();
        }
        // your real pointer on it
        double[] uv = InGameMonitors.pointerOnSeat();
        var shape = dev.windowscraft.capture.win.RealCursor.current();
        var mon = InGameMonitors.monitorOf(d);
        if (uv != null && shape != null && mon != null && uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1) {
            Identifier ct = dev.windowscraft.render.DisplayRenderer.realPointerTexture(shape);
            // your real cursor at its real size on your screen, pixel for pixel — like on your desktop
            float s = (float) (1 / mc.getWindow().getScaleFactor());
            m.push();
            m.translate(px + (float) uv[0] * pw - shape.hotX() * s, py + (float) uv[1] * ph - shape.hotY() * s, 1);
            m.scale(s, s, 1);
            sharpNearest(ct, () -> g.drawTexture(ct, 0, 0, 0f, 0f, shape.width(), shape.height(), shape.width(), shape.height()));
            m.pop();
        }
    }

    /** Draws with the full-size picture only (no blurrier smaller copies): sharp, as big as it is on your screen. */
    private static void sharp(Identifier tex, Runnable draw) { filtered(tex, org.lwjgl.opengl.GL11.GL_LINEAR, org.lwjgl.opengl.GL11.GL_LINEAR, draw); }

    private static void sharpNearest(Identifier tex, Runnable draw) { filtered(tex, org.lwjgl.opengl.GL11.GL_NEAREST, org.lwjgl.opengl.GL11.GL_NEAREST, draw); }

    private static void filtered(Identifier tex, int min, int mag, Runnable draw) {
        var t = MinecraftClient.getInstance().getTextureManager().getTexture(tex);
        if (t == null) { draw.run(); return; }
        int id = t.getGlId();
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(id);
        int oldMin = org.lwjgl.opengl.GL11.glGetTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER);
        int oldMag = org.lwjgl.opengl.GL11.glGetTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, min);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, mag);
        try {
            draw.run();
        } finally {
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(id);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, oldMin);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, oldMag);
        }
    }

    /**
     * With a shader pack and "screens without shaders": the picture in your hand is drawn over the
     * finished frame (in true colours, sharp), exactly where the tablet's screen is.
     */
    private static void renderHandScreen(DrawContext g, RenderTickCounter counter) {
        float[] q = handScreen;
        Identifier tex = handTex;
        handScreen = null;
        if (q == null || tex == null || raised) return;
        var mat = g.getMatrices().peek().getPositionMatrix();
        VertexConsumer vc = g.getVertexConsumers().getBuffer(RenderLayer.getText(tex));
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        // TL, BL, BR, TR (counter-clockwise on screen)
        vc.vertex(mat, q[0], q[1], 0).color(255, 255, 255, 255).texture(0, 0).light(light);
        vc.vertex(mat, q[6], q[7], 0).color(255, 255, 255, 255).texture(0, 1).light(light);
        vc.vertex(mat, q[4], q[5], 0).color(255, 255, 255, 255).texture(1, 1).light(light);
        vc.vertex(mat, q[2], q[3], 0).color(255, 255, 255, 255).texture(1, 0).light(light);
        g.draw();
    }

    /** Where a point of the hand's drawing ends up on your screen (GUI units), or null if behind you. */
    private static float[] onScreen(Matrix4f full, float x, float y, float z) {
        org.joml.Vector4f v = full.transform(new org.joml.Vector4f(x, y, z, 1));
        if (v.w <= 1e-5f) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        float gx = (v.x / v.w * 0.5f + 0.5f) * mc.getWindow().getScaledWidth();
        float gy = (1 - (v.y / v.w * 0.5f + 0.5f)) * mc.getWindow().getScaledHeight();
        return new float[]{gx, gy};
    }

    // ---- in your hand: what's on it --------------------------------------------------------------

    /** (ItemRenderer, around drawing an item for someone: is it you?) */
    public static void ownHand(boolean mine) { OWN_HAND.set(mine); }

    /**
     * Right after the game drew a tablet item (its model's own space, 0..1): the live picture on
     * its screen, if it's yours and on.
     */
    public static void drawOnItem(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vcp) {
        if (!Monitors.isTablet(stack) || !OWN_HAND.get()) return;
        if (mode == ModelTransformationMode.GUI || mode == ModelTransformationMode.GROUND || mode == ModelTransformationMode.FIXED) return;
        if (raised && mode.isFirstPerson()) return; // it's held up big instead
        Display d = display();
        if (d == null || !InGameMonitors.isOn(d)) return;
        Identifier tex = d.pollTexture(++frame, true);
        if (tex == null) return;
        d.noteView(true, 200);
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getText(tex));
        Matrix4f mat = matrices.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        // the screen in the item's 16×16 picture: columns 1..14, rows 3..12 (y goes up in the model)
        float l = 1 / 16f, r = 15 / 16f, t = 13 / 16f, b = 3 / 16f, front = 8.5f / 16 + 0.002f;
        // front (seen from +z), counter-clockwise: TL, BL, BR, TR
        v(vc, mat, l, t, front, 0, 0, light);
        v(vc, mat, l, b, front, 0, 1, light);
        v(vc, mat, r, b, front, 1, 1, light);
        v(vc, mat, r, t, front, 1, 0, light);
        // (the back is the tablet's own back)
    }

    /**
     * First person: the game holds it like a map (your arm, or both hands); this draws the tablet
     * where the map would be — in the map's space, 0..128 across with y going down.
     */
    public static void drawLikeMap(ItemStack stack, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Display d = display();
        double a = d == null ? 16 / 9.0 : aspect(d);
        // as wide as a map in your hand; as tall as its screen's shape makes it
        float b = 142 / 16f, w = 142, h = (float) ((w - 2 * b) / a) + 2 * b;
        if (h > 142) { // (a tall one: as tall as a map instead)
            float k = 142 / h;
            w *= k;
            h *= k;
            b *= k;
        }
        float x0 = 64 - w / 2, y0 = 64 - h / 2, x1 = x0 + w, y1 = y0 + h;
        Identifier frameTex = Identifier.of("windowscraft", "textures/item/" + colorOf(stack) + "_tablet.png");
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getText(frameTex));
        boolean on = d != null && InGameMonitors.isOn(d);
        Identifier tex = on ? d.pollTexture(++frame, true) : null;
        frame((fx0, fy0, fx1, fy1, u, v, uw, vh) -> mapQuad(vc, mat, fx0, fy0, fx1, fy1, 0, u / 16f, v / 16f, (u + uw) / 16f, (v + vh) / 16f, light),
                x0, y0, x1, y1, b, tex == null);
        if (tex == null) return; // (off: its own black screen)
        d.noteView(true, 300);
        if (dev.windowscraft.render.ShaderCompat.drawAfterShaders() || Boolean.getBoolean("windowscraft.test.handOverlay")) {
            // the shader pack would tint and blur it: black here, the picture itself over the finished frame
            Identifier black = Identifier.of("windowscraft", "textures/item/" + colorOf(stack) + "_tablet.png");
            mapQuad(vcp.getBuffer(RenderLayer.getText(black)), mat, x0 + b, y0 + b, x1 - b, y1 - b, -0.2f, 1 / 16f, 3 / 16f, 15 / 16f, 13 / 16f, light);
            Matrix4f full = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix())
                    .mul(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).mul(mat);
            float[] tl = onScreen(full, x0 + b, y0 + b, -0.4f), tr = onScreen(full, x1 - b, y0 + b, -0.4f),
                    br = onScreen(full, x1 - b, y1 - b, -0.4f), bl = onScreen(full, x0 + b, y1 - b, -0.4f);
            if (tl != null && tr != null && br != null && bl != null) {
                handTex = tex;
                handScreen = new float[]{tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]};
            }
            return;
        }
        mapQuad(vcp.getBuffer(RenderLayer.getText(tex)), mat, x0 + b, y0 + b, x1 - b, y1 - b, -0.4f, 0, 0, 1, 1,
                LightmapTextureManager.MAX_LIGHT_COORDINATE);
    }

    private static void mapQuad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                                float u0, float v0, float u1, float v1, int light) {
        v(vc, m, x0, y1, z, u0, v1, light);
        v(vc, m, x1, y1, z, u1, v1, light);
        v(vc, m, x1, y0, z, u1, v0, light);
        v(vc, m, x0, y0, z, u0, v0, light);
    }

    private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float vv, int light) {
        vc.vertex(m, x, y, z).color(255, 255, 255, 255).texture(u, vv).light(light);
    }

    private Tablet() {}
}
