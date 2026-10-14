package dev.windowscraft.capture;

import dev.windowscraft.WindowsCraftClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Before direct GPU sharing touches the graphics driver inside the game, the same steps are tried
 * in a throwaway helper process ({@code wc-capture --selftest}). Some drivers end the whole
 * program without warning when Direct3D↔OpenGL sharing goes wrong — if that happens in the test,
 * only the helper dies and the feature stays off. The outcome is remembered per graphics card and
 * driver version, so the test runs once (again after a driver update).
 */
public final class GpuSelfTest {
    private static volatile Boolean result;
    private static volatile String reason = "";
    private static volatile boolean running;
    private static String key;

    private static Path cacheFile() {
        // its own file: the earlier sharing route (WGL_NV_DX_interop) kept its results in gpu-selftest.txt
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("gpu-selftest-ext.txt");
    }

    /**
     * True = safe to use, false = not here (see {@link #reason()}), null = still testing.
     * Render thread; the GL strings identify the graphics card and driver Minecraft draws with.
     */
    public static synchronized Boolean check(String glVendor, String glRenderer, String glVersion, int[] luid) {
        // (v2: the test now also tries the memory sizes drivers round pictures up to — tested again)
        // (v3: tested again once — a "black" verdict on a card where it works elsewhere may have
        // been the check misreading; if it's really black it's turned off again, with details)
        // (v4: the in-game black check can't be fooled by a read-back another mod catches, nor by a
        // screen that's black itself — a "black" verdict is tested again once; other results stay)
        String gl = glVendor + " / " + glRenderer + " / " + glVersion;
        String k = "v4 " + gl, before = "v3 " + gl;
        if (k.equals(key) && (result != null || running)) return result;
        key = k;
        result = null;
        try {
            for (String line : Files.readAllLines(cacheFile())) {
                String[] p = line.split("\t", 3);
                boolean carried = p.length == 3 && p[0].equals(before) && !p[2].contains("black in the game");
                if (p.length == 3 && (p[0].equals(k) || carried)) {
                    reason = p[2];
                    swapped = p[2].contains("(bgra");
                    blit = p[2].contains("+blit)");
                    result = p[1].equals("ok");
                    return result;
                }
            }
        } catch (Exception ignored) {
        }
        running = true;
        Thread t = new Thread(() -> run(k, glRenderer, luid), "WindowsCraft GPU sharing test");
        t.setDaemon(true);
        t.start();
        return null;
    }

    public static String reason() { return reason; }

    /**
     * It passed the test but doesn't work in the game after all (e.g. black pictures): off from
     * now on for this graphics card and driver (a driver update tests again).
     */
    public static synchronized void markBroken(String why) {
        if (key == null) return;
        result = false;
        reason = why;
        WindowsCraftClient.LOGGER.warn("Direct GPU sharing turned off: {}", why);
        try {
            Files.createDirectories(cacheFile().getParent());
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (Files.exists(cacheFile())) for (String l : Files.readAllLines(cacheFile())) if (!l.startsWith(key + "\t")) lines.add(l);
            lines.add(key + "\tfail\t" + why);
            Files.write(cacheFile(), lines);
        } catch (Exception ignored) {
        }
    }

    private static volatile boolean swapped;

    /** Does the driver hand the picture over with red and blue swapped (the test measures it)? */
    public static boolean redBlueSwapped() { return swapped; }

    private static volatile boolean blit;

    /** Does this driver refuse glCopyImageSubData from shared pictures (a framebuffer blit does the copy instead)? */
    public static boolean useBlit() { return blit; }
    public static boolean running() { return running; }

    private static void run(String k, String glRenderer, int[] luid) {
        boolean ok = false, remember = true;
        String why;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(GpuShare.extract().toString(), "--selftest"));
            if (luid != null) cmd.addAll(java.util.List.of("--adapter-luid", Integer.toUnsignedString(luid[0]) + ":" + luid[1]));
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            String line = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (l.startsWith("selftest=")) line = l;
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                why = "the test didn't finish";
                remember = false;
            } else if (line == null || p.exitValue() != 0) {
                why = "the graphics driver crashed while testing it (exit code " + p.exitValue() + ") — it would have closed Minecraft";
            } else {
                String[] f = line.substring("selftest=".length()).split("\\|", -1);
                if (f[0].equals("ok")) {
                    String testRenderer = f.length > 2 ? f[2] : "";
                    String mode = f.length > 3 ? f[3] : "";
                    String colours = mode.replace("+blit", "");
                    if (!colours.equals("rgba") && !colours.equals("bgra")) {
                        why = "the test gave an answer this version doesn't understand";
                    } else if (testRenderer.equalsIgnoreCase(glRenderer.replace('|', '/'))) {
                        ok = true;
                        swapped = colours.equals("bgra");
                        blit = mode.endsWith("+blit");
                        why = "tested OK on " + testRenderer + " (" + mode + ")";
                    } else {
                        why = "it could only be tested on another graphics card (" + testRenderer + ")";
                    }
                } else {
                    why = f.length > 1 ? f[1] : "the test failed";
                }
            }
        } catch (Exception e) {
            why = "couldn't run the test (" + e.getMessage() + ")";
            remember = false;
        }
        reason = why;
        result = ok;
        running = false;
        WindowsCraftClient.LOGGER.info("Direct GPU sharing test: {} — {}", ok ? "passed" : "not usable", why);
        if (remember) {
            try {
                Files.createDirectories(cacheFile().getParent());
                java.util.List<String> lines = new java.util.ArrayList<>();
                if (Files.exists(cacheFile())) for (String l : Files.readAllLines(cacheFile())) if (!l.startsWith(k + "\t")) lines.add(l);
                lines.add(k + "\t" + (ok ? "ok" : "fail") + "\t" + why.replace('\n', ' '));
                Files.write(cacheFile(), lines);
            } catch (Exception ignored) {
            }
        }
    }

    private GpuSelfTest() {}
}
