package dev.windowscraft.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.windowscraft.WindowsCraftClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Someone else's screen in colour, made on the graphics card. The viewer's decoder hands over
 * pictures as they come out of the video decoder (NV12: brightness per pixel, colour per 2×2
 * pixels, 1.5 bytes a pixel); this draws them into the wall's ordinary RGBA texture. Before,
 * FFmpeg converted every picture to BGRA on the CPU — 1–1.5 cores at 1080p60 together with
 * moving 4 bytes a pixel through the loopback socket, Java and the upload.
 *
 * <p>It's one small draw into the wall's texture in the middle of the world's rendering (shader
 * packs included), so every bit of OpenGL state it touches is read first and put back as it was.
 * If anything about it fails, it switches itself off for good and viewers go back to BGRA.
 * {@code windowscraft.gpuConvert=false}: always the old way.
 */
public final class Nv12Converter {
    private enum State { UNKNOWN, READY, OFF }

    private static volatile State state = State.UNKNOWN;
    private static int program, vao, fbo;
    private static boolean samplers;
    private static boolean checkedFirst;

    // BT.601, limited range: how all of WindowsCraft's senders make their pictures (NVENC from
    // BGRA, x264/AMF/QSV via FFmpeg's converter — neither says otherwise in the stream), and so
    // what FFmpeg's own conversion on the viewer used: within 3 of 255 of its BGRA on a decoded
    // picture. Colour is stored once per 2×2 pixels and repeated over them, as FFmpeg did —
    // blending it between blocks (what video players do) came out further from the original
    // screen on hard-edged content (colour bars 37.9 → 31.6 dB) and no better on text.
    private static final String VERTEX = """
            #version 150
            void main() {
                // one triangle that covers the whole target
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D luma;
            uniform sampler2D chroma;
            out vec4 colour;
            void main() {
                vec2 pos = gl_FragCoord.xy;
                float y = texelFetch(luma, ivec2(pos), 0).r;
                vec2 uv = texelFetch(chroma, ivec2(pos) / 2, 0).rg;
                float l = 1.164384 * (y - 0.062745);
                float u = uv.r - 0.501961, v = uv.g - 0.501961;
                colour = vec4(l + 1.596027 * v, l - 0.391762 * u - 0.812968 * v, l + 2.017232 * u, 1.0);
            }
            """;

    /** For the viewer's decoder: send pictures as NV12? (Not until the conversion is set up, never after it failed.) */
    public static boolean ready() {
        return state == State.READY;
    }

    /** Render thread: sets the conversion up once (cheap; a no-op after the first call). */
    public static void init() {
        if (state != State.UNKNOWN) return;
        RenderSystem.assertOnRenderThread();
        if ("false".equals(System.getProperty("windowscraft.gpuConvert"))) {
            state = State.OFF;
            return;
        }
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (!StreamingTexture.fastUploads()) throw new IllegalStateException("no persistent upload buffers");
            samplers = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
            int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX), fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL30.glBindFragDataLocation(p, 0, "colour");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(p);
                GL20.glDeleteProgram(p);
                throw new IllegalStateException("link: " + log);
            }
            int prev = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "luma"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "chroma"), 1);
            GL20.glUseProgram(prev);
            program = p;
            int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            vao = GL30.glGenVertexArrays(); // (core profile: a draw needs one bound, even without attributes)
            GL30.glBindVertexArray(vao);
            GL30.glBindVertexArray(prevVao);
            fbo = GL30.glGenFramebuffers();
            state = State.READY;
            WindowsCraftClient.LOGGER.info("Others' screens: colours made on the graphics card");
        } catch (Throwable t) {
            off("setting up: " + t.getMessage());
        }
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException("compile: " + log);
        }
        return s;
    }

    /** Switches the conversion off for good (viewers reconnect and get BGRA again). */
    static void off(String why) {
        if (state == State.OFF) return;
        state = State.OFF;
        WindowsCraftClient.LOGGER.warn("Others' screens: colours on the graphics card don't work here ({}); FFmpeg makes them again", why);
    }

    /**
     * Render thread: the planes in {@code luma} (R8, w×h) and {@code chroma} (RG8, w/2×h/2) into
     * {@code target} (RGBA8, w×h, level 0). False if it couldn't (then it's off from now on).
     */
    static boolean convert(int luma, int chroma, int target, int w, int h) {
        if (state != State.READY) return false;
        if (!checkedFirst) for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) { /* (others' errors: not ours to judge by) */ }
        // what's bound and set now (put back exactly, below)
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean[] mask = new boolean[4];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer m = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
            for (int i = 0; i < 4; i++) mask[i] = m.get(i) != 0;
        }
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST), logic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP),
                discard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        int[] prevTex = new int[2], prevSampler = new int[2];
        for (int u = 0; u < 2; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            prevTex[u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (samplers) prevSampler[u] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        boolean ok = true;
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                ok = false;
                off("the wall's texture can't be drawn into");
            } else {
                GL11.glViewport(0, 0, w, h);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL11.glColorMask(true, true, true, true);
                GL20.glUseProgram(program);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, luma);
                if (samplers) GL33.glBindSampler(0, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, chroma);
                if (samplers) GL33.glBindSampler(1, 0);
                GL30.glBindVertexArray(vao);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        } finally {
            // everything back as it was
            GL30.glBindVertexArray(prevVao);
            for (int u = 1; u >= 0; u--) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex[u]);
                if (samplers) GL33.glBindSampler(u, prevSampler[u]);
            }
            GL13.glActiveTexture(prevUnit);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glColorMask(mask[0], mask[1], mask[2], mask[3]);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_DEPTH_TEST, depth);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL11.GL_STENCIL_TEST, stencil);
            set(GL11.GL_COLOR_LOGIC_OP, logic);
            set(GL30.GL_RASTERIZER_DISCARD, discard);
        }
        if (ok && !checkedFirst) { // the first one: did the driver take all of it?
            checkedFirst = true;
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                off("OpenGL error 0x" + Integer.toHexString(err));
                return false;
            }
        }
        return ok;
    }

    private static void set(int cap, boolean on) {
        if (on) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }

    private Nv12Converter() {}
}
