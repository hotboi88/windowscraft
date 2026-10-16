package dev.windowscraft.render;

import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.stream.StreamPublisher;
import dev.windowscraft.stream.StreamSubscriber;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Draws every display as one full-bright textured quad, the same way vanilla draws maps in item
 * frames (RenderLayer#getText), which keeps it compatible with Sodium and Iris shader packs.
 */
public final class DisplayRenderer {
    private static final double MAX_RENDER_DISTANCE = 128;
    private static long renderFrame;

    /** This frame's number (polling a picture again in the same frame does no more work). */
    public static long frameNumber() { return renderFrame; }

    /** Normal path: inside the world, so a shader pack lights and colours the screens like everything else. */
    public static void render(WorldRenderContext ctx) {
        if (ShaderCompat.drawAfterShaders()) return; // drawn later, in true colours
        MatrixStack matrices = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        draw(matrices, ctx.camera().getPos());
    }

    /**
     * True-colour path (shader pack active, "Screens without shaders" on): called right after the
     * world — and the shader pack's final pass — is done, drawing straight into the final image
     * with the world's depth, so anything in front of a screen still covers it.
     */
    public static void renderAfterShaders(net.minecraft.client.render.Camera camera, Matrix4f view, Matrix4f projection) {
        dev.windowscraft.client.FrameView.capture(view, projection, camera.getPos());
        if (!ShaderCompat.drawAfterShaders()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getFramebuffer().beginWrite(false);
        com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.systems.VertexSorter.BY_DISTANCE);
        org.joml.Matrix4fStack modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(Float.MAX_VALUE); // shown exactly as shared: no fog either
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        try {
            MatrixStack matrices = new MatrixStack();
            matrices.multiplyPositionMatrix(view);
            draw(matrices, camera.getPos());
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(fogStart);
            modelView.popMatrix();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();
        }
    }

    // ---- what the walls cost (for /windowscraft report and the log) ----
    private static long statStart, statFrames, statNanos, statUploads, statUploadNanos;
    private static String lastStats = "walls: nothing drawn yet";

    /** "walls: 0.12 ms a frame (uploads 0.05 ms, 31/s), game 74 fps" over the last 10 s. */
    public static String stats() { return lastStats; }

    private static void stat(long nanos) {
        long now = System.nanoTime();
        if (statStart == 0) statStart = now;
        statFrames++;
        statNanos += nanos;
        if (now - statStart >= 10_000_000_000L) {
            double secs = (now - statStart) / 1e9;
            lastStats = String.format(java.util.Locale.ROOT, "walls: %.2f ms a frame (uploads %.2f ms, %.0f/s), game %.0f fps",
                    statNanos / 1e6 / statFrames, statUploadNanos / 1e6 / statFrames, statUploads / secs, statFrames / secs);
            if (!DisplayManager.get().liveShares().isEmpty()) {
                dev.windowscraft.WindowsCraftClient.LOGGER.info("[perf] {}", lastStats);
                for (var s : DisplayManager.get().liveShares()) dev.windowscraft.WindowsCraftClient.LOGGER.info("[perf] {}", s.describe());
            }
            statStart = now;
            statFrames = statNanos = statUploads = statUploadNanos = 0;
        }
    }

    // measurements (off by default)
    private static final dev.windowscraft.measure.Measure.Metric WALLS = dev.windowscraft.measure.Measure.ms("frame.wc_walls",
            "drawing every screen in the world, per frame (render thread, incl. picking up new pictures)");
    private static final dev.windowscraft.measure.Measure.Metric UPLOAD = dev.windowscraft.measure.Measure.ms("wall.poll",
            "one wall picking up its newest picture (render thread)");
    private static final GpuTimer GPU_WALLS = new GpuTimer(dev.windowscraft.measure.Measure.ms("gpu.walls_frame",
            "graphics card time for all our screens in one frame (uploads + drawing)"));

    private static void draw(MatrixStack matrices, Vec3d cam) {
        MinecraftClient pmc = MinecraftClient.getInstance();
        int fbH = pmc.getWindow().getFramebufferHeight();
        if (fbH > 0) pixelAngle = 2 * Math.tan(Math.toRadians(pmc.options.getFov().getValue()) / 2) / fbH;
        long t0 = System.nanoTime();
        GPU_WALLS.begin();
        try {
            drawWalls(matrices, cam);
        } finally {
            GPU_WALLS.end();
            long dt = System.nanoTime() - t0;
            stat(dt);
            WALLS.record(dt);
        }
    }

    private static void drawWalls(MatrixStack matrices, Vec3d cam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        DisplayManager manager = DisplayManager.get();
        manager.releasePendingTextures();
        RemoteDisplays.releaseTextures();
        if (manager.all().isEmpty() && RemoteDisplays.all().isEmpty()) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        long frame = ++renderFrame;
        View view = new View(mc);
        boolean pauseHidden = dev.windowscraft.config.ClientSettings.offscreenPause();
        boolean limitFar = dev.windowscraft.config.ClientSettings.distanceFpsLimit();
        long now = System.nanoTime();
        Display looked = null;
        double lookedPx = 0;
        for (Display d : manager.all()) {
            if (d.tablet || !d.dimension.equals(dim) || !facing(d.rect, cam)) continue;
            boolean seen = view.sees(d.rect, cam);
            double px = view.pixelsTall(d.rect, cam);
            d.noteView(seen, px);
            if (seen && px > lookedPx && dev.windowscraft.monitor.InGameMonitors.isOn(d)) { lookedPx = px; looked = d; }
            if (pauseHidden && !seen) continue; // off-screen pause: no work at all for a wall you can't see
            long tu = System.nanoTime();
            boolean upload = !limitFar || px >= FAR_PX || due(d.id, now) || !dev.windowscraft.config.ClientSettings.on(dev.windowscraft.config.ClientSettings.Auto.DISTANCE_LIMIT, d);
            Identifier tex = d.pollTexture(frame, upload);
            if (upload) { statUploads++; long du = System.nanoTime() - tu; statUploadNanos += du; UPLOAD.record(du); }
            if (tex != null && dev.windowscraft.client.DeskView.covers(d)) {
                // (you sit at it: it's drawn flat and sharp on your view, right over this spot)
            } else if (tex != null) {
                placed(matrices, immediate, d.rect, tex, d.textureWidth(), d.textureHeight(), d.fit(), cam);
                // the pointer: only while someone points with Alt, or sits at the wall and moves the mouse
                if (dev.windowscraft.client.ScreenControl.pointerVisible(d)) {
                    if (dev.windowscraft.monitor.InGameMonitors.realCursorOn(d)) // your real pointer, its real shape, no delay
                        drawRealPointer(matrices, immediate, dev.windowscraft.client.ScreenControl.pictureCorners(d), d,
                                dev.windowscraft.client.ScreenControl.pictureSpot(d), cam);
                    else drawPointer(matrices, immediate, dev.windowscraft.client.ScreenControl.pictureCorners(d), d.rect,
                            dev.windowscraft.client.ScreenControl.box(d), dev.windowscraft.client.ScreenControl.pictureSpot(d), cam);
                }
                // people you allowed, each with their own pointer (a real Windows arrow) on your in-game monitor
                var mon = dev.windowscraft.monitor.InGameMonitors.monitorOf(d);
                if (mon != null) for (double[] gp : dev.windowscraft.client.ScreenControl.guestPointers(d))
                    drawArrow(matrices, immediate, dev.windowscraft.client.ScreenControl.pictureCorners(d), mon.width(), mon.height(), gp, cam);
            }
            else quad(matrices, immediate, d.rect, idleTexture(d), 1, 1, cam); // (the card is made in the screen's own shape: edge to edge)
        }
        // the one of your in-game monitors you're looking at (big on your view): its full picture
        dev.windowscraft.monitor.InGameMonitors.lookingAt(lookedPx >= 280 ? looked : null);
        for (RemoteDisplays.Remote r : RemoteDisplays.all()) {
            if (!r.dimension().equals(dim) || !r.wallOk || !facing(r.rect, cam)) continue;
            if (pauseHidden && !view.sees(r.rect, cam)) continue;
            double rpx = view.pixelsTall(r.rect, cam);
            Identifier tex = r.pollTexture(frame, !limitFar || rpx >= FAR_PX || due(r.id, now));
            if (tex != null && dev.windowscraft.client.DeskView.covers(r)) {
                // (you watch or use it flat on your view: drawn there, sharp)
            } else if (tex != null) {
                placed(matrices, immediate, r.rect, tex, r.textureWidth(), r.textureHeight(), r.info.fit(), cam);
                if (dev.windowscraft.client.ScreenControl.pointerVisible(r)) {
                    Vec3d[] pc = dev.windowscraft.client.ScreenControl.pictureCorners(r);
                    double[] spot = dev.windowscraft.client.ScreenControl.pictureSpot(r);
                    // someone's in-game monitor: a real Windows arrow, like on a PC; otherwise the usual pointer
                    if (!r.info.title().startsWith("In-game monitor") || !drawArrow(matrices, immediate, pc, r.info.width(), r.info.height(), spot, cam))
                        drawPointer(matrices, immediate, pc, r.rect, r.box(), spot, cam);
                }
            } else {
                StreamSubscriber sub = r.subscriber();
                String detail = sub == null ? "Come closer to watch" : sub.status();
                quad(matrices, immediate, r.rect, IdleCards.get(r.ownerName + "'s screen", detail, r.rect.width() / r.rect.height()), 1, 1, cam);
            }
        }
    }

    /** Draws a picture on a wall, filling it edge to edge or letterboxing it according to {@code fit}. */
    private static void placed(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, WallRect rect, Identifier tex,
                               int cw, int ch, int fit, Vec3d cam) {
        boolean fill = CaptureSettings.shouldFill(fit, cw, ch, rect.width(), rect.height());
        quad(matrices, immediate, rect, tex, fill ? 1 : cw, fill ? 1 : ch, cam);
    }

    /**
     * The picture's corners are pulled toward your eye along the line you see them on: they stay
     * exactly where they appear on screen, but end up a little nearer than the wall — as much as
     * one and a half of your screen's pixels span along the surface from here. A shader pack's
     * jitter (TAA) moves the wall by up to about a pixel, which at a steep angle far away is a long
     * way in depth: there the margin grows, and the concrete never pokes through; straight on and
     * close it's a hair (~0.007 of a block at 3 blocks), so the screen sits flush on its block and
     * in a monitor's screen (0.15.10 and before: ~0.08 there — "a gap", "popping out"). Measured
     * with Solas (testmod ScreenTour): a pull of just depth precision lost the picture entirely
     * seen steeply from 26 blocks; this one keeps it.
     */
    private static Vec3d towardCamera(Vec3d p, Vec3d cam, Vec3d normal) {
        Vec3d to = p.subtract(cam);
        double d = to.length();
        if (d < 1e-4 || NO_PULL || PULL.equals("off")) return p;
        double facing = Math.max(0.02, Math.abs(to.dotProduct(normal)) / d);
        double pull = switch (PULL) {
            case "full" -> 0.004 + 0.012 * d + Math.min(0.25 + 0.02 * d, (1 / 32.0) / facing); // (dev: as up to 0.15.10)
            case "small" -> 0.002 + 0.0005 * d + Math.min(0.02, (1 / 512.0) / facing); // (dev: depth precision only)
            default -> Math.min(0.002 + 1.5 * d * pixelAngle / facing, 0.25 + 0.02 * d);
        };
        // a gaming monitor's screen sits behind the frame's lip: never pulled out past it
        if (recessed) pull = Math.min(pull, (WallRect.RECESS - 0.004) / facing);
        return cam.add(to.multiply(Math.max(0.5, 1 - pull / d)));
    }

    /** What's being drawn now is a gaming monitor's screen (set back behind its frame): see towardCamera, drawInFront. */
    private static boolean recessed;

    /** A wall shorter than this on your screen counts as "far away" (distance frame-rate limit). */
    public static final double FAR_PX = 160; // ~1/7 of a 1080p screen: really far away, not just across the room
    private static final java.util.Map<java.util.UUID, Long> LAST_UPLOAD = new java.util.HashMap<>();

    /** Distance frame-rate limit: a far wall takes a new picture at most 15 times a second. */
    private static boolean due(java.util.UUID id, long now) {
        Long last = LAST_UPLOAD.get(id);
        if (last != null && now - last < 66_000_000L) return false;
        LAST_UPLOAD.put(id, now);
        return true;
    }

    /** Where the camera looks this frame: is a wall in view, and how tall does it look? */
    private static final class View {
        final Vec3d fwd;
        final double cosLimit, pxPerUnit;

        View(MinecraftClient mc) {
            org.joml.Vector3f f = mc.gameRenderer.getCamera().getHorizontalPlane();
            fwd = new Vec3d(f.x(), f.y(), f.z()).normalize();
            double vfov = Math.toRadians(mc.options.getFov().getValue());
            double aspect = mc.getWindow().getFramebufferWidth() / (double) Math.max(1, mc.getWindow().getFramebufferHeight());
            double tanV = Math.tan(vfov / 2);
            // the corner of the view, plus a margin (sprinting / zoom mods widen the view)
            double half = Math.atan(tanV * Math.sqrt(1 + aspect * aspect)) * 1.15 + Math.toRadians(4);
            cosLimit = Math.cos(Math.min(Math.PI * 0.95, half));
            pxPerUnit = mc.getWindow().getFramebufferHeight() / (2 * tanV);
        }

        /** Any of nine spots across the wall inside the view (or you're right at it)? */
        boolean sees(WallRect rect, Vec3d cam) {
            Vec3d[] c = rect.corners(1, 1);
            Vec3d right = c[1].subtract(c[0]), down = c[3].subtract(c[0]);
            if (rect.center().squaredDistanceTo(cam) < 4) return true;
            for (int i = 0; i <= 2; i++) {
                for (int j = 0; j <= 2; j++) {
                    Vec3d p = c[0].add(right.multiply(i / 2.0)).add(down.multiply(j / 2.0));
                    Vec3d to = p.subtract(cam);
                    double len = to.length();
                    if (len < 1e-6 || to.dotProduct(fwd) / len >= cosLimit) return true;
                }
            }
            return false;
        }

        /** About how many pixels tall the wall looks from here. */
        double pixelsTall(WallRect rect, Vec3d cam) {
            double dist = Math.max(0.5, rect.center().distanceTo(cam));
            return rect.height() / dist * pxPerUnit;
        }
    }

    /** Within range and seen from the front? */
    private static boolean facing(WallRect rect, Vec3d cam) {
        Vec3d center = rect.center();
        if (center.squaredDistanceTo(cam) > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) return false;
        Vec3d toCam = cam.subtract(center);
        Vec3d n = rect.normal();
        return toCam.dotProduct(n) > 0;
    }

    private static void quad(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, WallRect rect, Identifier tex,
                             int cw, int ch, Vec3d cam) {
        recessed = rect.isMonitor(); // (its pointer and hovers are drawn next, the same way)
        {
            Vec3d[] c = rect.corners(cw, ch);
            // "always in front of its own wall", yet flat on it: the picture is pulled toward your
            // eye along your line of sight (so it looks exactly where it is), and drawn with a
            // slope-scaled depth bias (drawInFront)
            for (int i = 0; i < c.length; i++) c[i] = towardCamera(c[i], cam, rect.normal());
            RenderLayer layer = RenderLayer.getText(tex);
            VertexConsumer vc = immediate.getBuffer(layer);
            matrices.push();
            Matrix4f m = matrices.peek().getPositionMatrix();
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            // counter-clockwise as seen from the front: TL, BL, BR, TR
            vertex(vc, m, c[0], cam, 0, 0, light);
            vertex(vc, m, c[3], cam, 0, 1, light);
            vertex(vc, m, c[2], cam, 1, 1, light);
            vertex(vc, m, c[1], cam, 1, 0, light);
            matrices.pop();
            drawInFront(immediate, layer);
        }
    }

    private static Identifier idleTexture(Display d) {
        CaptureSession s = d.session();
        double aspect = d.rect.width() / d.rect.height();
        if (s != null) {
            String why = s.message();
            return IdleCards.get(s.state() == CaptureSession.State.PAUSED ? "Paused" : "Connecting…",
                    why != null ? why : s.source().title(), aspect);
        }
        if (d.endedMessage() != null) return IdleCards.get("Share ended", d.endedMessage(), aspect);
        return IdleCards.get("Ready to share", "Look here and press " + dev.windowscraft.WindowsCraftClient.keyName(), aspect);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3d p, Vec3d cam, float u, float v, int light) {
        vc.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                .color(255, 255, 255, 255)
                .texture(u, v)
                .light(light);
    }

    // ---- the pointer ----

    private static Identifier pointerTex;
    private static int pointerW, pointerH;

    /** The arrow: our own drawing, white with a dark edge, made once at a generous size and drawn smaller. */
    private static Identifier pointerTexture() {
        if (pointerTex != null) return pointerTex;
        double k = 2.0;
        pointerW = (int) Math.ceil(19 * k);
        pointerH = (int) Math.ceil(29 * k);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(pointerW, pointerH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
        g.scale(k, k);
        java.awt.geom.GeneralPath p = new java.awt.geom.GeneralPath();
        p.moveTo(1.5, 1.5);
        p.lineTo(1.5, 22.5);
        p.lineTo(6.6, 17.6);
        p.lineTo(10.4, 26.4);
        p.lineTo(14.0, 24.8);
        p.lineTo(10.3, 16.2);
        p.lineTo(17.5, 16.2);
        p.closePath();
        g.setColor(java.awt.Color.WHITE);
        g.fill(p);
        g.setColor(new java.awt.Color(20, 20, 20));
        g.setStroke(new java.awt.BasicStroke(1.3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.draw(p);
        g.dispose();
        net.minecraft.client.texture.NativeImage ni = new net.minecraft.client.texture.NativeImage(pointerW, pointerH, true);
        for (int y = 0; y < pointerH; y++)
            for (int x = 0; x < pointerW; x++) {
                int argb = img.getRGB(x, y);
                ni.setColor(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
            }
        net.minecraft.client.texture.NativeImageBackedTexture tex = new net.minecraft.client.texture.NativeImageBackedTexture(ni) {
            @Override public void setFilter(boolean bilinear, boolean mipmap) { super.setFilter(true, false); } // always smooth
        };
        pointerTex = Identifier.of("windowscraft", "pointer");
        MinecraftClient.getInstance().getTextureManager().registerTexture(pointerTex, tex);
        return pointerTex;
    }

    /** A Windows pointer image as a texture (re-uploaded only when the shape changes). */
    private static final class ShapeTex {
        final String name;
        net.minecraft.client.texture.NativeImageBackedTexture tex;
        Identifier id;
        long handle;
        int w, h;

        ShapeTex(String name) { this.name = name; }

        Identifier upload(dev.windowscraft.capture.win.RealCursor.Shape shape) {
            if (shape.handle() == handle && tex != null && w == shape.width() && h == shape.height()) return id;
            if (tex == null || w != shape.width() || h != shape.height()) {
                if (tex != null) tex.close();
                tex = new net.minecraft.client.texture.NativeImageBackedTexture(new net.minecraft.client.texture.NativeImage(shape.width(), shape.height(), true)) {
                    @Override public void setFilter(boolean bilinear, boolean mipmap) { super.setFilter(true, false); }
                };
                id = Identifier.of("windowscraft", name);
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                w = shape.width();
                h = shape.height();
            }
            var img = tex.getImage();
            int[] px = bleed(shape.argb(), w, h);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int argb = px[y * w + x];
                    img.setColor(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16)); // → ABGR
                }
            tex.upload();
            handle = shape.handle();
            return id;
        }
    }

    /**
     * The see-through pixels around a pointer get the colour of their neighbours (still see-through):
     * smoothing it on a wall blends each edge pixel with them, and with "black, see-through" that
     * was a dark fringe and dark specks around the pointer. Also drops the faintest bits of its
     * shadow, which a wall's drawing would cut off in hard steps anyway.
     */
    static int[] bleed(int[] argb, int w, int h) {
        int[] out = argb.clone();
        for (int i = 0; i < out.length; i++) if ((out[i] >>> 24) < 26) out[i] = 0;
        int[] src = out.clone();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((src[i] >>> 24) != 0) continue;
                long r = 0, g = 0, b = 0, wsum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int c = src[ny * w + nx], a = c >>> 24;
                        if (a == 0) continue;
                        r += (long) ((c >> 16) & 0xFF) * a;
                        g += (long) ((c >> 8) & 0xFF) * a;
                        b += (long) (c & 0xFF) * a;
                        wsum += a;
                    }
                if (wsum > 0) out[i] = (int) (r / wsum) << 16 | (int) (g / wsum) << 8 | (int) (b / wsum);
            }
        return out;
    }

    private static final ShapeTex REAL = new ShapeTex("real-pointer"), ARROW = new ShapeTex("arrow-pointer");

    /** A real Windows arrow's picture as a texture (someone's pointer, drawn flat on your view). */
    public static Identifier arrowTexture(dev.windowscraft.capture.win.RealCursor.Shape shape) { return ARROW.upload(shape); }

    /** The plain pointer (where there's no Windows arrow to draw): its texture and size in pixels. */
    public static Identifier plainPointer() { return pointerTexture(); }
    public static int[] plainPointerSize() { pointerTexture(); return new int[]{pointerW, pointerH}; }

    /** Your real pointer's picture as a texture (for the tablet held up). */
    public static Identifier realPointerTexture(dev.windowscraft.capture.win.RealCursor.Shape shape) { return REAL.upload(shape); }

    /** Your real pointer on your in-game monitor: its real shape, where it really is, no delay. */
    private static void drawRealPointer(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d[] c, Display d, double[] uv, Vec3d cam) {
        dev.windowscraft.capture.win.RealCursor.Shape shape = dev.windowscraft.capture.win.RealCursor.current();
        var mon = dev.windowscraft.monitor.InGameMonitors.monitorOf(d);
        if (shape == null || mon == null) return;
        drawShape(matrices, immediate, c, REAL, shape, mon.width(), mon.height(), uv, cam);
    }

    /**
     * Someone's pointer drawn as a real Windows arrow (from your own pointer scheme) at its real
     * size on a picture of pw × ph pixels. False if there's no such image (not Windows).
     */
    private static boolean drawArrow(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d[] c, int pw, int ph, double[] uv, Vec3d cam) {
        dev.windowscraft.capture.win.RealCursor.Shape shape = dev.windowscraft.capture.win.RealCursor.arrow();
        if (shape == null || pw <= 0 || ph <= 0) return false;
        drawShape(matrices, immediate, c, ARROW, shape, pw, ph, uv, cam);
        return true;
    }

    private static void drawShape(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d[] c, ShapeTex st,
                                  dev.windowscraft.capture.win.RealCursor.Shape shape, int pw, int ph, double[] uv, Vec3d cam) {
        Identifier id = st.upload(shape);
        int sw = shape.width(), sh = shape.height();
        Vec3d right = c[1].subtract(c[0]), down = c[3].subtract(c[0]);
        // its real size: the same share of the picture as on a real screen — but never smaller than
        // a normal cursor on a 1080p screen (a high resolution mustn't make it tiny)
        double k = Math.max(1, (ph / 34.0) / Math.max(1, sh));
        Vec3d r = right.multiply(k * sw / (double) pw), dn = down.multiply(k * sh / (double) ph);
        Vec3d spot = c[0].add(right.multiply(uv[0])).add(down.multiply(uv[1]));
        Vec3d tl = spot.subtract(r.multiply(shape.hotX() / (double) sw)).subtract(dn.multiply(shape.hotY() / (double) sh));
        Vec3d[] q = {tl, tl.add(r), tl.add(r).add(dn), tl.add(dn)};
        for (int i = 0; i < 4; i++) q[i] = towardCamera(q[i], cam, right.crossProduct(down).normalize()).add(cam.subtract(q[i]).normalize().multiply(0.003));
        RenderLayer layer = RenderLayer.getText(id);
        VertexConsumer vc = immediate.getBuffer(layer);
        Matrix4f m = matrices.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        vertex(vc, m, q[0], cam, 0, 0, light);
        vertex(vc, m, q[3], cam, 0, 1, light);
        vertex(vc, m, q[2], cam, 1, 1, light);
        vertex(vc, m, q[1], cam, 1, 0, light);
        drawInFront(immediate, layer);
    }

    private static void drawPointer(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d[] c, WallRect rect,
                                    dev.windowscraft.client.Box box, double[] uv, Vec3d cam) {
        recessed = rect.isMonitor();
        Identifier tex = pointerTexture();
        Vec3d right = c[1].subtract(c[0]), down = c[3].subtract(c[0]);
        double hBlocks = down.length() * (box.y1() - box.y0()) * 0.022, wBlocks = hBlocks * pointerW / pointerH;
        Vec3d r = right.normalize().multiply(wBlocks), dn = down.normalize().multiply(hBlocks);
        Vec3d tip = c[0].add(right.multiply(uv[0])).add(down.multiply(uv[1]));
        Vec3d[] q = {tip, tip.add(r), tip.add(r).add(dn), tip.add(dn)};
        // a little nearer than the picture itself (same pull, plus a hair), so it's always on top of it
        for (int i = 0; i < 4; i++) q[i] = towardCamera(q[i], cam, right.crossProduct(down).normalize()).add(cam.subtract(q[i]).normalize().multiply(0.003));
        RenderLayer layer = RenderLayer.getText(tex);
        VertexConsumer vc = immediate.getBuffer(layer);
        Matrix4f m = matrices.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        vertex(vc, m, q[0], cam, 0, 0, light);
        vertex(vc, m, q[3], cam, 0, 1, light);
        vertex(vc, m, q[2], cam, 1, 1, light);
        vertex(vc, m, q[1], cam, 1, 0, light);
        drawInFront(immediate, layer);
    }

    /**
     * Draws with a depth bias toward the camera that grows with the slope of the surface (OpenGL
     * polygon offset), so a picture is always in front of its own wall: seen at a steep angle from
     * far away, a fixed lift alone isn't enough and the concrete shows through in jagged steps.
     * Anything really in front of the screen (a block, a player) is far more than the bias away.
     */
    private static void drawInFront(VertexConsumerProvider.Immediate immediate, RenderLayer layer) {
        if (NO_BIAS) { immediate.draw(layer); return; }
        com.mojang.blaze3d.systems.RenderSystem.enablePolygonOffset();
        // (a monitor's screen behind its lip: a steep bias would lift it over the lip seen from the side)
        com.mojang.blaze3d.systems.RenderSystem.polygonOffset(recessed ? 0f : -4f, -16f);
        try {
            immediate.draw(layer);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.polygonOffset(0f, 0f);
            com.mojang.blaze3d.systems.RenderSystem.disablePolygonOffset();
        }
    }

    /** How big one pixel of your screen is, as an angle (radians), this frame. */
    private static double pixelAngle = 0.0015;

    /** Dev: how far pictures are pulled toward your eye — pixel (the normal), full (0.15.10), small, off (testmod ScreenTour compares them). */
    private static final String PULL = System.getProperty("windowscraft.exp.pull", "pixel");

    /** Dev only: draw without the depth bias (to compare in tests). */
    private static final boolean NO_BIAS = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
            && "1".equals(System.getenv("WC_NO_DEPTH_BIAS"));

    /** Dev only: no pull toward the eye either (to see what the test catches without it). */
    private static final boolean NO_PULL = NO_BIAS && "1".equals(System.getenv("WC_NO_PULL"));

    private DisplayRenderer() {}
}
