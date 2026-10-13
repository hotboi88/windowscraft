package dev.windowscraft.render;

import dev.windowscraft.config.ClientSettings;
import java.lang.reflect.Method;

/**
 * Shader packs (Iris) recolour everything in the world — lighting, tone mapping, colour grading,
 * bloom — screens included. With "Screens without shaders" on, screens are drawn after the shader
 * pack has finished the frame instead, so their colours are exactly what was shared. Iris keeps
 * the world's depth in Minecraft's own depth buffer, so walls, players and blocks in front of a
 * screen still hide it correctly.
 */
public final class ShaderCompat {
    private static Method getInstance, inUse;
    private static boolean looked;

    /** Is an Iris shader pack active right now? (False without Iris.) */
    public static boolean shaderPackInUse() {
        try {
            if (!looked) {
                looked = true;
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                getInstance = api.getMethod("getInstance");
                inUse = api.getMethod("isShaderPackInUse");
            }
            return inUse != null && (boolean) inUse.invoke(getInstance.invoke(null));
        } catch (Throwable t) {
            inUse = null;
            return false;
        }
    }

    /** Draw screens after the shader pack (true colours) this frame? */
    public static boolean drawAfterShaders() {
        return ClientSettings.screensWithoutShaders() && shaderPackInUse();
    }

    private ShaderCompat() {}
}
