package dev.windowscraft.render;

import dev.windowscraft.capture.FrameSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** The GPU texture behind one in-world screen, fed from a {@link FrameSource}. Render thread only. */
public final class DisplayTexture {
    private final String key;
    private StreamingTexture texture;
    private Identifier id;
    private GpuShareTexture gpuTexture;
    private Identifier gpuId;
    private long frames;

    public DisplayTexture(String key) { this.key = key; }

    /** Uploads the newest frame (if any) and returns the texture to draw, or null if nothing yet. */
    public Identifier poll(FrameSource source) {
        usingGpu = false;
        if (texture == null) {
            if (source == null) return null;
            texture = new StreamingTexture();
            id = Identifier.of("windowscraft", key);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        }
        if (texture.update(source)) frames++;
        return texture.hasContent() ? id : null;
    }

    /**
     * Direct GPU sharing: the newest frame, copied graphics-memory to graphics-memory. Returns
     * null until the first frame; throws if the driver won't cooperate (caller falls back).
     */
    public Identifier pollGpu(dev.windowscraft.capture.GpuShare share) {
        if (gpuTexture == null) {
            gpuTexture = new GpuShareTexture();
            gpuId = Identifier.of("windowscraft", key + "_gpu");
            MinecraftClient.getInstance().getTextureManager().registerTexture(gpuId, gpuTexture);
        }
        gpuTexture.update(share);
        if (gpuTexture.hasContent()) { usingGpu = true; frames++; }
        return gpuTexture.hasContent() ? gpuId : null;
    }

    /** Stops using the GPU-shared texture (e.g. sharing turned off or failed). */
    public void dropGpu() {
        usingGpu = false;
        if (gpuId != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(gpuId);
        gpuId = null;
        gpuTexture = null;
    }

    private boolean usingGpu;

    /** Tests: the OpenGL texture shown right now (0 if none). */
    public int glId() { return usingGpu && gpuTexture != null ? gpuTexture.getGlId() : texture == null ? 0 : texture.getGlId(); }

    public int width() { return usingGpu && gpuTexture != null ? gpuTexture.width() : texture == null ? 0 : texture.width(); }
    public int height() { return usingGpu && gpuTexture != null ? gpuTexture.height() : texture == null ? 0 : texture.height(); }
    public long framesShown() { return frames; }

    public void release() {
        dropGpu();
        if (id != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
        id = null;
        texture = null;
    }
}
