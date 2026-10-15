package dev.windowscraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.capture.GpuShare;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

/**
 * A wall fed straight from graphics memory. The capture helper's three shared Direct3D textures
 * are imported into OpenGL as external memory (GL_EXT_memory_object_win32 — the same mechanism
 * Vulkan and Direct3D 12 use to share with OpenGL); each new frame is copied GPU-to-GPU
 * (glCopyImageSubData) into this mipmapped texture. No CPU copies, nothing locked per frame: the
 * helper only announces a picture once the graphics card has finished writing it.
 */
public final class GpuShareTexture extends AbstractTexture {
    private static final int GL_TEXTURE_SWIZZLE_R = 0x8E42, GL_TEXTURE_SWIZZLE_B = 0x8E44, GL_TEXTURE_SWIZZLE_A = 0x8E45;
    private final int[] glSources = new int[3];
    private final int[] memory = new int[3];
    private int width, height, version = -1;
    private long lastSeq, copied;
    private boolean hasContent;
    private final boolean mipmaps;
    // measurements (off by default)
    private static final dev.windowscraft.measure.Measure.Metric SHOW_AGE = dev.windowscraft.measure.Measure.ms("gpu_share.show_age",
            "direct GPU sharing: captured by Windows → copied into the wall's texture (render thread)");
    private static final dev.windowscraft.measure.Measure.Counter SKIPPED = dev.windowscraft.measure.Measure.counter("gpu_share.frames_not_shown",
            "pictures the helper made that were replaced by a newer one before a frame copied them");
    private static final GpuTimer GPU_COPY = new GpuTimer(dev.windowscraft.measure.Measure.ms("gpu.share_copy",
            "graphics card time: one GPU-shared picture copied into the wall (+ mipmaps)"));

    public GpuShareTexture() { this(true); }

    public GpuShareTexture(boolean mipmaps) { this.mipmaps = mipmaps; }

    @Override public void load(ResourceManager manager) {}
    @Override public void setFilter(boolean bilinear, boolean mipmap) {}

    public int width() { return width; }
    public int height() { return height; }
    public boolean hasContent() { return hasContent; }

    /** Render thread: pick up a new frame if there is one. Throws on any failure (the wall then goes back to the normal way). */
    public void update(GpuShare share) {
        RenderSystem.assertOnRenderThread();
        GpuShare.Slots s = share.slots();
        if (s == null) return;
        if (s.version() != version) {
            // a newer size can replace these before we get to them; then they're already given back
            if (!s.taken().compareAndSet(false, true)) return;
            register(s);
            for (long f : copyFences) if (f != 0) GL32.glDeleteSync(f);
            java.util.Arrays.fill(copyFences, 0L);
        }
        // pictures whose copy has finished on the graphics card go back to the helper
        for (int i = 0; i < copyFences.length; i++) {
            if (copyFences[i] == 0) continue;
            int st = GL32.glClientWaitSync(copyFences[i], 0, 0);
            if (st == GL32.GL_ALREADY_SIGNALED || st == GL32.GL_CONDITION_SATISFIED || st == GL32.GL_WAIT_FAILED) {
                GL32.glDeleteSync(copyFences[i]);
                copyFences[i] = 0;
                share.free(i);
            }
        }
        // only the newest new picture is copied; the ones before it go straight back
        java.util.List<Integer> fresh = share.takeAnnounced();
        int slot = fresh.isEmpty() ? -1 : fresh.get(fresh.size() - 1);
        for (int i = 0; i < fresh.size() - 1; i++) if (fresh.get(i) != slot) share.free(fresh.get(i));
        if (fresh.size() > 1) SKIPPED.add(fresh.size() - 1);
        if (slot >= 0 && slot < 3) SHOW_AGE.since(share.capturedAt(slot));
        long seq = share.frameSeq();
        if (mipmaps && mips.due() && hasContent) {
            GlStateManager._bindTexture(getGlId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            mips.done();
        }
        if (slot < 0 || slot >= 3) return;
        lastSeq = seq;
        GPU_COPY.begin();
        while (GL11.glGetError() != 0) { /* not ours */ }
        if (!blit) {
            GL43.glCopyImageSubData(glSources[slot], GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    getGlId(), GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
            if (GL11.glGetError() != 0) blit = true; // this driver refuses that copy from shared pictures: blit instead
        }
        if (blit) blit(slot);
        int err = GL11.glGetError();
        if (err != 0) throw new IllegalStateException("OpenGL error " + Integer.toHexString(err) + " copying the shared picture");
        // the helper gets this picture back once the graphics card has really finished copying it
        if (copyFences[slot] != 0) GL32.glDeleteSync(copyFences[slot]);
        copyFences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (mipmaps && mips.now()) {
            GlStateManager._bindTexture(getGlId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        }
        GPU_COPY.end();
        hasContent = true;
        copied++; // the crash guard stays armed while this runs: if the game ever dies meanwhile, it's off next time
        checkNotBlack();
    }

    /** The memory size (and dedicated or not) the driver took last time: tried first. */
    private static long[] worked;

    private static long up(long v, long a) { return (v + a - 1) / a * a; }

    /**
     * Does this OpenGL context report errors? Sodium makes one that doesn't ("no error" context,
     * on by default): every try below would look as if it worked.
     */
    private static boolean noErrorContext() {
        return (GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & 0x00000008) != 0; // GL_CONTEXT_FLAG_NO_ERROR_BIT_KHR
    }

    private boolean noErrors;

    /** Is the picture's memory really there? Its middle and its very last pixel (the end of the memory) aren't all zeros. */
    private boolean readable(int tex, int w, int h) {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), prevPack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (probeFbo == 0) probeFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer px = st.malloc(4);
            for (int[] p : new int[][]{{w / 2, h / 2}, {w - 1, h - 1}}) {
                px.putInt(0, 0);
                GL11.glReadPixels(p[0], p[1], 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                if (px.getInt(0) == 0) return false;
            }
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, prevPack);
        }
    }

    private void register(GpuShare.Slots s) {
        GpuCrashGuard.arm();
        noErrors = noErrorContext();
        // (a refused copy can't be noticed there either: the blit, which every driver takes)
        if (noErrors && !blit) {
            blit = true;
            dev.windowscraft.WindowsCraftClient.LOGGER.info("Direct GPU sharing: this OpenGL context reports no errors (Sodium's \"no error\" context); checking the pictures themselves");
        }
        copied = 0;
        unregister();
        while (GL11.glGetError() != 0) { /* start clean */ }
        try {
            // how big the picture's memory is, as the driver sees it, isn't something Direct3D tells
            // us: the plain size first, then the sizes drivers round pictures up to (some refuse a
            // texture on memory they think is too small); dedicated memory first, then not
            long base = (long) s.width() * s.height() * 4, pitch = up(s.width() * 4L, 256);
            java.util.List<long[]> tries = new java.util.ArrayList<>();
            if (worked != null) tries.add(worked);
            for (long size : new long[]{base, up(base, 65536), up(pitch * s.height(), 65536), base * 2, base * 4})
                for (long dedicated : new long[]{1}) // (Direct3D pictures are dedicated memory: anything else imports, but shows black)
                    if (tries.stream().noneMatch(t -> t[0] == size && t[1] == dedicated)) tries.add(new long[]{size, dedicated});
            for (int i = 0; i < 3; i++) {
                String last = null;
                for (long[] t : tries) {
                    while (GL11.glGetError() != 0) { /* start clean */ }
                    int mem = EXTMemoryObject.glCreateMemoryObjectsEXT();
                    EXTMemoryObject.glMemoryObjectParameteriEXT(mem, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, t[1] == 1 ? GL11.GL_TRUE : GL11.GL_FALSE);
                    EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(mem, t[0], EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT, s.handles()[i]);
                    int e = GL11.glGetError();
                    if (e != 0) {
                        EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
                        last = "OpenGL error " + Integer.toHexString(e) + " taking the shared picture's memory";
                        continue;
                    }
                    int tex = TextureUtil.generateTextureId();
                    GlStateManager._bindTexture(tex);
                    EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, s.width(), s.height(), mem, 0);
                    e = GL11.glGetError();
                    // where OpenGL reports no errors (Sodium's "no error" context), a size the driver
                    // refused looks just like one it took: look at the picture itself — the helper
                    // fills new ones with opaque black; memory taken at a wrong size reads all zeros
                    if (e == 0 && noErrors && !readable(tex, s.width(), s.height())) e = -1;
                    if (e != 0) {
                        TextureUtil.releaseTextureId(tex);
                        EXTMemoryObject.glDeleteMemoryObjectsEXT(mem);
                        last = e == -1 ? "the shared picture read back empty at every memory size tried"
                                : "OpenGL error " + Integer.toHexString(e) + " making a texture on the shared picture";
                        continue;
                    }
                    memory[i] = mem;
                    glSources[i] = tex;
                    worked = t;
                    last = null;
                    break;
                }
                if (last != null) throw new IllegalStateException(last);
            }
        } finally {
            for (long h : s.handles()) GpuShare.closeHandle(h); // OpenGL holds its own reference now
        }
        width = s.width();
        height = s.height();
        version = s.version();
        hasContent = false;
        // our own texture: same size, with mipmaps (text stays readable from a distance)
        GlStateManager._bindTexture(getGlId());
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        int levels = mipmaps ? 31 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 0;
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmaps ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        if (mipmaps) { // seen at a steep angle, stay sharp instead of smearing (same as the other picture routes)
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            if (caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic || caps.OpenGL46) {
                float max = GL11.glGetFloat(0x84FF);
                if (max > 1) GL11.glTexParameterf(GL11.GL_TEXTURE_2D, 0x84FE, Math.min(8f, max));
            }
        }
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // window pixels are opaque: whatever alpha the app left behind is ignored (no see-through specks)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL11.GL_ONE);
        if (dev.windowscraft.capture.GpuSelfTest.redBlueSwapped()) { // the driver hands Direct3D's BGRA over as-is
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL11.GL_BLUE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL11.GL_RED);
        }
    }

    // (dev: -Dwindowscraft.exp.gpuRoute=copy|blit tries one route regardless of the safety test's pick)
    private boolean blit = "blit".equals(System.getProperty("windowscraft.exp.gpuRoute")) || !"copy".equals(System.getProperty("windowscraft.exp.gpuRoute"))
            && dev.windowscraft.capture.GpuSelfTest.useBlit();
    private final MipPacer mips = new MipPacer();
    private final long[] copyFences = new long[3];
    private int readFbo, drawFbo;

    /** The same GPU-to-GPU copy through two framebuffers (accepted by drivers that refuse glCopyImageSubData here). */
    private void blit(int slot) {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (readFbo == 0) {
            readFbo = GL30.glGenFramebuffers();
            drawFbo = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glSources[slot], 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, getGlId(), 0);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
    }

    // ---- does the picture really arrive? ---------------------------------------------------
    //
    // Some drivers take the shared memory without complaint and then hand over nothing but
    // black. For its first seconds, the wall looks at a few spots of what it got: if every one of
    // them is pure black every time, direct GPU sharing doesn't work here — the wall goes back to
    // the normal way, and the game remembers it for this graphics driver.
    //
    // A wrong verdict switches it off for good on that driver, so it must be sure: the spots are
    // read with the read-back settings put to plain values (another mod — a minimap, a shader
    // pack — can leave a buffer bound that would catch the read, leaving "black" behind), a read
    // that didn't land counts as no verdict, and a screen whose own picture is black (the normal
    // capture of it sees black too: a black wallpaper on an empty monitor) is looked at again
    // later instead.

    private long checkStarted, lastCheck;
    private int checks, blackChecks, blackSourceRounds, noCompareRounds, unreadable;
    private int probeFbo;
    private boolean unreadableSaid;
    /** Does the normal (CPU) capture of the same screen see black too? (null: no picture of it yet) */
    private java.util.function.Supplier<Boolean> sourceBlack;

    void sourceBlack(java.util.function.Supplier<Boolean> s) { sourceBlack = s; }

    private void checkNotBlack() {
        long now = System.nanoTime();
        if (checks >= 6) return;
        if (checkStarted == 0) checkStarted = now;
        if (now - lastCheck < 700_000_000L || now - checkStarted < 300_000_000L) return;
        lastCheck = now;
        Boolean black = sampledBlack();
        if (black == null) { // (the read-back didn't land: no verdict — after a few, stop looking)
            if (++unreadable >= 6) checks = 6;
            return;
        }
        checks++;
        if (black) blackChecks++;
        if (checks == 6 && blackChecks == 6) {
            Boolean cpu = sourceBlack == null ? null : sourceBlack.get();
            // the screen really is black right now (or there's nothing to compare with yet): no
            // verdict — look again in a while (it keeps showing through GPU sharing meanwhile)
            if (Boolean.TRUE.equals(cpu) || (cpu == null && noCompareRounds++ < 3)) {
                if (blackSourceRounds++ == 0)
                    dev.windowscraft.WindowsCraftClient.LOGGER.info("Direct GPU sharing: {}; checking again later",
                            cpu == null ? "nothing to compare its black picture with yet" : "the screen's own picture is black too (normal capture)");
                if (blackSourceRounds < 20) {
                    checks = blackChecks = 0;
                    checkStarted = now + 10_000_000_000L; // (10 s from now)
                    lastCheck = 0;
                }
                return;
            }
            String why = "the shared pictures came out black in the game";
            // what was going on, so a misreading can be told from a real black picture
            dev.windowscraft.WindowsCraftClient.LOGGER.warn("Direct GPU sharing looked black: {}; normal capture {}", blackDetails(),
                    cpu == null ? "had no picture to compare" : "saw a picture");
            GpuInterop.broken(why);
            throw new IllegalStateException(why);
        }
    }

    private String lastProbe = "";

    /** The copy route and the OpenGL state around the read-back (only when it's judged black). */
    private String blackDetails() {
        StringBuilder b = new StringBuilder();
        try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer box = st.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            java.nio.ByteBuffer mask = st.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            b.append("route ").append(blit ? "blit" : "copy").append(", ").append(width).append('×').append(height)
                    .append(", scissor ").append(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)).append(' ')
                    .append(box.get(0)).append(',').append(box.get(1)).append(' ').append(box.get(2)).append('×').append(box.get(3))
                    .append(", colour mask ").append(mask.get(0)).append(mask.get(1)).append(mask.get(2)).append(mask.get(3))
                    .append(", sRGB ").append(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB))
                    .append(", rasterizer discard ").append(GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD))
                    .append(", pack buffer ").append(GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING))
                    .append(", pack row/skip/align ").append(GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH)).append('/')
                    .append(GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS)).append('/').append(GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT))
                    .append(", read/draw fbo ").append(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)).append('/')
                    .append(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING))
                    .append(", last probe ").append(lastProbe)
                    .append("; middle of the shared pictures (as OpenGL sees them) ").append(middleOf(glSources[0])).append(" | ")
                    .append(middleOf(glSources[1])).append(" | ").append(middleOf(glSources[2]))
                    .append("; the wall's now ").append(middleOf(getGlId()));
        } catch (Throwable t) {
            b.append(" (").append(t).append(')');
        }
        while (GL11.glGetError() != 0) { /* (a query this driver doesn't know: no matter) */ }
        return b.toString();
    }

    /** The middle pixel of a texture as "r,g,b,a" (diagnostics: is the shared picture itself empty, or the copy?). */
    private String middleOf(int tex) {
        if (tex == 0) return "-";
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), prevPack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (probeFbo == 0) probeFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        String out;
        try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer px = st.malloc(4);
            px.putInt(0, 0x5A5A5A5A);
            GL11.glReadPixels(width / 2, height / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            int e = GL11.glGetError();
            out = px.getInt(0) == 0x5A5A5A5A ? "unread" + (e != 0 ? " (0x" + Integer.toHexString(e) + ")" : "")
                    : (px.get(0) & 0xFF) + "," + (px.get(1) & 0xFF) + "," + (px.get(2) & 0xFF) + "," + (px.get(3) & 0xFF);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            while (GL11.glGetError() != 0) { /* (diagnostics only) */ }
        }
        return out;
    }

    /**
     * True if every one of a grid of spots (plus the top and bottom edges) is pure black; null if
     * the read-back didn't land (no verdict).
     */
    private Boolean sampledBlack() {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        // the read goes into our memory with plain settings, whatever the last mod left set
        int prevPack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevRow = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH), prevSkipPx = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS),
                prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS), prevAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        if (probeFbo == 0) probeFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, getGlId(), 0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        int fboStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        boolean black = true, landed = true;
        try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer px = st.malloc(4);
            for (int gy = 0; gy <= 4 && black && landed; gy++) {
                for (int gx = 0; gx <= 4 && black && landed; gx++) {
                    int x = Math.min(width - 1, gx * (width - 1) / 4), y = gy == 0 ? 6 : Math.min(height - 1, gy * (height - 1) / 4);
                    px.putInt(0, 0x5A5A5A5A); // (still there afterwards: nothing was read)
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                    if (gx == 2 && gy == 2) lastProbe = "fbo 0x" + Integer.toHexString(fboStatus) + ", gl error 0x" + Integer.toHexString(GL11.glGetError())
                            + ", middle " + (px.get(0) & 0xFF) + "," + (px.get(1) & 0xFF) + "," + (px.get(2) & 0xFF) + "," + (px.get(3) & 0xFF)
                            + ", pack buffer before " + prevPack;
                    if (px.getInt(0) == 0x5A5A5A5A) landed = false;
                    else if ((px.get(0) & 0xFF) > 1 || (px.get(1) & 0xFF) > 1 || (px.get(2) & 0xFF) > 1) black = false;
                }
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRow);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPx);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
            while (GL11.glGetError() != 0) { /* (a driver that can't read this back: no verdict) */ }
        }
        if (!landed) {
            if (!unreadableSaid) {
                unreadableSaid = true;
                dev.windowscraft.WindowsCraftClient.LOGGER.info("Direct GPU sharing: the picture couldn't be read back to check it ({}); not judged", lastProbe);
            }
            return null;
        }
        return black;
    }

    private static void check(String what) {
        int err = GL11.glGetError();
        if (err != 0) throw new IllegalStateException("OpenGL error " + Integer.toHexString(err) + " " + what);
    }

    private void unregister() {
        for (int i = 0; i < copyFences.length; i++) {
            if (copyFences[i] != 0) GL32.glDeleteSync(copyFences[i]);
            copyFences[i] = 0;
        }
        if (probeFbo != 0) {
            GL30.glDeleteFramebuffers(probeFbo);
            probeFbo = 0;
        }
        if (readFbo != 0) {
            GL30.glDeleteFramebuffers(readFbo);
            GL30.glDeleteFramebuffers(drawFbo);
            readFbo = drawFbo = 0;
        }
        for (int i = 0; i < 3; i++) {
            if (glSources[i] != 0) TextureUtil.releaseTextureId(glSources[i]);
            glSources[i] = 0;
            if (memory[i] != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(memory[i]);
            memory[i] = 0;
        }
        version = -1;
    }

    @Override
    public void clearGlId() {
        RenderSystem.assertOnRenderThread();
        unregister();
        super.clearGlId();
    }

    @Override
    public void close() {
        clearGlId();
    }
}
