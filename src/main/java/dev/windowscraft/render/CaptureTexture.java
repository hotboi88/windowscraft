package dev.windowscraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.capture.Frame;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A GPU texture fed with BGRA frames. Uploads straight from the capture buffer in the driver's
 * native BGRA layout (no per-pixel conversion), generates mipmaps so text stays readable from a
 * distance, and uses a texture swizzle to treat GDI's undefined alpha as opaque.
 */
public final class CaptureTexture extends AbstractTexture {
    private static final int GL_TEXTURE_SWIZZLE_A = 0x8E45;
    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;
    private static Boolean swizzleSupported;
    private static float maxAniso = -1;

    private final boolean mipmapped;
    private int width, height;
    private boolean allocated;
    private Boolean opaqueSwizzle;

    public CaptureTexture(boolean mipmapped) {
        this.mipmapped = mipmapped;
    }

    @Override
    public void load(ResourceManager manager) {
        // contents arrive via upload()
    }

    /** Render layers call this with their own preferences; we keep our filtering. */
    @Override
    public void setFilter(boolean bilinear, boolean mipmap) {}

    public int width() { return width; }
    public int height() { return height; }
    public boolean hasContent() { return allocated; }

    public void upload(Frame f) {
        RenderSystem.assertOnRenderThread();
        detectCaps();
        GlStateManager._bindTexture(getGlId());
        if (!allocated || f.width != width || f.height != height) {
            width = f.width;
            height = f.height;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            int levels = mipmapped ? 31 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 0;
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            if (mipmapped && maxAniso > 1) GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, Math.min(8f, maxAniso));
            allocated = true;
            opaqueSwizzle = null;
        }
        boolean wantOpaque = !f.alphaValid;
        if (swizzleSupported) {
            if (opaqueSwizzle == null || opaqueSwizzle != wantOpaque) {
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, wantOpaque ? GL11.GL_ONE : GL11.GL_ALPHA);
                opaqueSwizzle = wantOpaque;
            }
        } else if (wantOpaque) {
            for (int i = 3, n = f.byteSize(); i < n; i += 4) f.data.put(i, (byte) 0xFF);
        }
        // A pixel-unpack buffer left bound by another mod would make GL read our pointer as an offset.
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        // Vanilla leaves unpack state behind after NativeImage uploads; reset what we rely on.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        f.data.position(0).limit(f.byteSize());
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, f.data);
        if (mipmapped) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    private static void detectCaps() {
        if (swizzleSupported != null) return;
        GLCapabilities caps = GL.getCapabilities();
        swizzleSupported = caps.OpenGL33 || caps.GL_ARB_texture_swizzle;
        if (caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic || caps.OpenGL46) {
            maxAniso = GL11.glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY);
        }
    }
}
