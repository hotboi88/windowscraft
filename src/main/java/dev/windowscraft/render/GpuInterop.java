package dev.windowscraft.render;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.win.D3D;
import dev.windowscraft.config.ClientSettings;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Can OpenGL take Direct3D pictures here? Direct GPU sharing uses OpenGL's external memory
 * (GL_EXT_memory_object_win32, the route Vulkan/Direct3D 12 interop uses) — not the older
 * WGL_NV_DX_interop, which crashed some drivers. Before it's ever used in the game, the exact
 * steps run in a throwaway helper ({@code wc-capture --selftest}); if anything goes wrong there,
 * or the game ever dies while it's starting, it stays off. Decided once; render thread only.
 */
public final class GpuInterop {
    private static Boolean available;
    private static String reason = "";
    private static D3D.Adapter found;

    public static boolean wanted() {
        return ClientSettings.gpuSharing() && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * True once it's safe to use. False while the safety test is still running (try again later)
     * or if it isn't possible here ({@link #state()} says which).
     */
    public static boolean ensure() {
        if (available != null) return available;
        if (!wanted()) return false;
        try {
            GLCapabilities gl = GL.getCapabilities();
            if (!(gl.OpenGL43 || gl.GL_ARB_copy_image)) return decide("OpenGL 4.3 copy not supported");
            if (!gl.GL_EXT_memory_object || !gl.GL_EXT_memory_object_win32)
                return decide("the graphics driver can't take Direct3D pictures as OpenGL memory");
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            GpuCrashGuard.driverKey = vendor + " / " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION);
            if (GpuCrashGuard.crashedBefore(GpuCrashGuard.driverKey))
                return decide("it closed the game when it was tried with this graphics driver (it will be tried again after a driver update)");
            // first, the same steps in a throwaway helper process: if the driver can't take it,
            // the helper fails instead of Minecraft
            if (found == null) found = D3D.find(vendor);
            Boolean tested = dev.windowscraft.capture.GpuSelfTest.check(vendor, GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION),
                    found == null ? null : new int[]{found.luidLow(), found.luidHigh()});
            if (tested == null) { reason = "testing whether your graphics driver supports it…"; return false; }
            if (!tested) return decide(dev.windowscraft.capture.GpuSelfTest.reason());
        } catch (Throwable t) {
            return decide(t.toString());
        }
        available = true;
        WindowsCraftClient.LOGGER.info("Direct GPU sharing ready (OpenGL external memory{})", found == null ? "" : ", " + found.name());
        return true;
    }

    private static boolean decide(String why) {
        available = false;
        reason = why;
        WindowsCraftClient.LOGGER.info("Direct GPU sharing unavailable: {}", why);
        return false;
    }

    /** It doesn't work in the game after all: off for the rest of this session (and remembered). */
    public static void broken(String why) {
        decide(why);
        dev.windowscraft.capture.GpuSelfTest.markBroken(why);
    }

    /** Has it been decided, and what was the outcome (null = not yet tried). */
    public static Boolean state() { return available; }
    public static String reason() { return reason; }
    public static int[] luid() { return found == null ? null : new int[]{found.luidLow(), found.luidHigh()}; }

    private GpuInterop() {}
}
