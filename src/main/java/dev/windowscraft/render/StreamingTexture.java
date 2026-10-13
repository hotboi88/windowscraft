package dev.windowscraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.FrameSource;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A wall's picture, updated up to 60 times a second at full resolution without stalling the
 * game's render thread.
 *
 * <p>The plain way ({@code glTexSubImage2D} from memory) makes the render thread copy ~8 MB per
 * 1080p frame and can wait for the GPU to finish drawing the texture before replacing it. Here:
 * <ul>
 *   <li>a background thread copies each new frame into a persistently mapped GPU buffer
 *       (three slots, guarded by fences), so the render thread copies nothing;</li>
 *   <li>the render thread then issues an asynchronous buffer→texture transfer (DMA);</li>
 *   <li>two textures alternate: the GPU draws one while the next frame lands in the other, so
 *       there's no implicit wait on a texture in use.</li>
 * </ul>
 * Falls back to the plain upload if the driver lacks buffer storage (OpenGL 4.4).
 */
public final class StreamingTexture extends AbstractTexture {
    private static final int SLOTS = 3;
    private static final int GL_TEXTURE_SWIZZLE_A = 0x8E45;
    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE, GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;
    private static final ExecutorService COPIER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "WindowsCraft frame copy");
        t.setDaemon(true);
        return t;
    });
    private static Boolean capable;   // does the driver support this at all (decided once)
    private boolean plain;            // this texture uses plain uploads (driver lacks support, or mapping failed)
    private boolean decided;
    private static float maxAniso = -1;

    private int width, height, frameBytes;
    private final int[] textures = new int[2];
    private final Boolean[] opaque = new Boolean[2];
    private int front;                 // the texture being drawn
    private boolean hasContent;
    private int pbo;
    private ByteBuffer mapped;
    private final long[] fences = new long[SLOTS];
    private int nextSlot;
    private Future<?> pending;
    private int pendingSlot;
    private boolean pendingOpaque;
    private final CaptureTexture fallback = new CaptureTexture(true);

    @Override public void load(ResourceManager manager) {}
    @Override public void setFilter(boolean bilinear, boolean mipmap) {}

    @Override
    public int getGlId() {
        if (plain) return fallback.getGlId();
        if (textures[front] == 0) return super.getGlId();
        return textures[front];
    }

    public int width() { return plain ? fallback.width() : width; }
    public int height() { return plain ? fallback.height() : height; }
    public boolean hasContent() { return plain ? fallback.hasContent() : hasContent; }

    /**
     * Render thread, once per frame: finish the previous transfer and, if a new frame is waiting,
     * start copying it. Returns true if a new picture was taken from {@code source}.
     */
    private final MipPacer mips = new MipPacer();

    public boolean update(FrameSource source) {
        RenderSystem.assertOnRenderThread();
        detectCaps();
        if (mips.due() && textures[front] != 0) { // a skipped update: the picture settled, catch up
            GlStateManager._bindTexture(textures[front]);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            mips.done();
        }
        if (!decided) {
            decided = true;
            plain = !capable;
        }
        if (plain) {
            Frame f = source == null ? null : source.takeFrame();
            if (f == null) return false;
            fallback.upload(f);
            source.recycle(f);
            return true;
        }
        if (pending != null) {
            if (!pending.isDone()) return false; // the copy of the previous frame is still running
            try {
                pending.get();
                transfer();
            } catch (Exception e) {
                WindowsCraftClient.LOGGER.warn("Frame copy failed", e);
            }
            pending = null;
        }
        if (source == null) return false;
        Frame f = source.takeFrame();
        if (f == null) return false;
        if (f.width != width || f.height != height || pbo == 0) allocate(f.width, f.height);
        if (plain) { // the driver refused the mapping: plain uploads for this screen from now on
            fallback.upload(f);
            source.recycle(f);
            return true;
        }
        int slot = freeSlot();
        if (slot < 0) { // GPU is still reading all three slots (very busy): skip this frame
            source.recycle(f);
            return false;
        }
        boolean wantOpaque = !f.alphaValid;
        ByteBuffer dst = mapped.duplicate();
        dst.position(slot * frameBytes).limit(slot * frameBytes + frameBytes);
        ByteBuffer target = dst.slice();
        int bytes = frameBytes;
        pendingSlot = slot;
        pendingOpaque = wantOpaque;
        pending = COPIER.submit(() -> {
            try {
                f.data.position(0).limit(bytes);
                target.put(f.data);
                f.data.position(0);
            } finally {
                source.recycle(f);
            }
        });
        return true;
    }

    private int freeSlot() {
        for (int i = 0; i < SLOTS; i++) {
            int s = (nextSlot + i) % SLOTS;
            long fence = fences[s];
            if (fence != 0) {
                int r = GL32.glClientWaitSync(fence, 0, 0);
                if (r == GL32.GL_TIMEOUT_EXPIRED) continue; // the GPU is still reading this slot
                // signaled (or WAIT_FAILED: don't lose the slot forever over a broken fence)
                GL32.glDeleteSync(fence);
                fences[s] = 0;
            }
            nextSlot = (s + 1) % SLOTS;
            return s;
        }
        return -1;
    }

    /** Buffer slot → the back texture (asynchronous on the GPU), then show it. */
    private void transfer() {
        int back = 1 - front;
        GlStateManager._bindTexture(textures[back]);
        if (opaque[back] == null || opaque[back] != pendingOpaque) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, pendingOpaque ? GL11.GL_ONE : GL11.GL_ALPHA);
            opaque[back] = pendingOpaque;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                (long) pendingSlot * frameBytes);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        if (mips.now()) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        fences[pendingSlot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        front = back;
        hasContent = true;
    }

    private void allocate(int w, int h) {
        freeGpu();
        width = w;
        height = h;
        frameBytes = w * h * 4;
        int levels = 31 - Integer.numberOfLeadingZeros(Math.max(w, h));
        for (int i = 0; i < 2; i++) {
            textures[i] = TextureUtil.generateTextureId();
            opaque[i] = null;
            GlStateManager._bindTexture(textures[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            if (maxAniso > 1) GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, Math.min(8f, maxAniso));
        }
        pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        long size = (long) frameBytes * SLOTS;
        int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) { /* clear stale errors from others */ }
        GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size, flags);
        mapped = GL11.glGetError() == GL11.GL_NO_ERROR ? GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, size, flags) : null;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        if (mapped == null) {
            WindowsCraftClient.LOGGER.warn("Couldn't map a streaming buffer ({}×{}); this screen uses plain texture uploads", w, h);
            freeGpu();
            plain = true;
        }
        front = 0;
        hasContent = false;
    }

    private void freeGpu() {
        if (pending != null) {
            try { pending.get(); } catch (Exception ignored) {} // never unmap under a running copy
            pending = null;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (fences[i] != 0) GL32.glDeleteSync(fences[i]);
            fences[i] = 0;
        }
        if (pbo != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            if (mapped != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(pbo);
            pbo = 0;
            mapped = null;
        }
        for (int i = 0; i < 2; i++) {
            if (textures[i] != 0) TextureUtil.releaseTextureId(textures[i]);
            textures[i] = 0;
        }
    }

    @Override
    public void clearGlId() {
        RenderSystem.assertOnRenderThread();
        freeGpu();
        fallback.clearGlId();
        super.clearGlId();
    }

    @Override
    public void close() {
        clearGlId();
    }

    private static void detectCaps() {
        if (capable != null) return;
        GLCapabilities caps = GL.getCapabilities();
        boolean swizzle = caps.OpenGL33 || caps.GL_ARB_texture_swizzle;
        capable = swizzle && (caps.OpenGL44 || caps.GL_ARB_buffer_storage) && (caps.OpenGL32 || caps.GL_ARB_sync)
                && !"false".equals(System.getProperty("windowscraft.streamingUpload"));
        if (caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic || caps.OpenGL46) {
            maxAniso = GL11.glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY);
        }
        WindowsCraftClient.LOGGER.info("Screen uploads: {}", capable ? "background copy + persistent buffers (fast)" : "plain");
    }
}
