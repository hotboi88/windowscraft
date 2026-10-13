package dev.windowscraft.client;

import dev.windowscraft.WindowsCraftClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /windowscraft report}: puts everything needed to track down a problem — recent game
 * logs, crash files, WindowsCraft's sound samples and test results, and a short system summary —
 * into one zip on the Desktop, ready to send.
 */
public final class Report {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("windowscraft").then(ClientCommandManager.literal("report").executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    String system = systemSummary(); // GL strings: this thread
                    Thread t = new Thread(() -> {
                        Text msg;
                        try {
                            Path zip = write(system);
                            msg = Text.literal("WindowsCraft report saved: ").formatted(Formatting.GREEN)
                                    .append(Text.literal(zip.toString()).formatted(Formatting.UNDERLINE)
                                            .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, zip.getParent().toString()))))
                                    .append(Text.literal(" — send that file along with your message.").formatted(Formatting.GREEN));
                        } catch (Exception e) {
                            WindowsCraftClient.LOGGER.warn("Couldn't write the report", e);
                            msg = Text.literal("WindowsCraft: couldn't write the report (" + e.getMessage() + ")").formatted(Formatting.RED);
                        }
                        Text m = msg;
                        mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(m, false); });
                    }, "WindowsCraft report");
                    t.setDaemon(true);
                    t.start();
                    return 1;
                }))));
    }

    static Path destination() {
        Path home = Path.of(System.getProperty("user.home"));
        for (Path p : List.of(home.resolve("Desktop"), home.resolve("OneDrive").resolve("Desktop"))) if (Files.isDirectory(p)) return p;
        return FabricLoader.getInstance().getGameDir();
    }

    static Path write(String system) throws IOException {
        Path game = FabricLoader.getInstance().getGameDir();
        Path wc = game.resolve("windowscraft");
        Path zip = destination().resolve("WindowsCraft-report-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "system.txt", system.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            add(z, game.resolve("logs").resolve("latest.log"), "logs/latest.log");
            for (Path p : newest(game.resolve("logs"), "*.log.gz", 3)) add(z, p, "logs/" + p.getFileName());
            for (Path p : newest(game.resolve("crash-reports"), "*.txt", 2)) add(z, p, "crash-reports/" + p.getFileName());
            for (Path p : newest(game, "hs_err_pid*.log", 2)) add(z, p, p.getFileName().toString());
            for (Path p : newest(wc.resolve("diagnostics"), "*.wav", 9)) add(z, p, "sound-samples/" + p.getFileName());
            for (String f : List.of("gpu-selftest.txt", "gpu-sharing-crashed.txt", "gpu-selftest-ext.txt", "gpu-sharing-ext-crashed.txt", "settings.json"))
                add(z, wc.resolve(f), f);
        }
        WindowsCraftClient.LOGGER.info("Wrote report {}", zip);
        return zip;
    }

    private static List<Path> newest(Path dir, String glob, int n) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new java.util.ArrayList<>();
        try (var s = Files.newDirectoryStream(dir, glob)) { s.forEach(p -> { if (!p.getFileName().toString().startsWith("debug")) out.add(p); }); } catch (IOException e) { return List.of(); }
        out.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    private static void add(ZipOutputStream z, Path file, String name) throws IOException {
        if (!Files.isRegularFile(file)) return;
        if (Files.size(file) > 40L << 20) return; // nothing huge
        put(z, name, Files.readAllBytes(file));
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    static String systemSummary() {
        StringBuilder b = new StringBuilder();
        b.append("WindowsCraft ").append(FabricLoader.getInstance().getModContainer(WindowsCraftClient.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        b.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
        b.append("Java: ").append(System.getProperty("java.version")).append(", max memory ").append(Runtime.getRuntime().maxMemory() >> 20).append(" MB\n");
        if (dev.windowscraft.monitor.InGameMonitors.supported()) {
            b.append("In-game monitors: driver ").append(dev.windowscraft.monitor.InGameMonitors.knownDriver())
                    .append(", on: ").append(dev.windowscraft.monitor.InGameMonitors.all()).append('\n');
        }
        try {
            b.append("GL: ").append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR)).append(" / ")
                    .append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)).append(" / ")
                    .append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION)).append('\n');
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                var wgl = org.lwjgl.opengl.GL.getCapabilitiesWGL();
                b.append("WGL_NV_DX_interop: ").append(wgl.WGL_NV_DX_interop).append(", interop2: ").append(wgl.WGL_NV_DX_interop2).append('\n');
                var gl = org.lwjgl.opengl.GL.getCapabilities();
                b.append("GL_EXT_memory_object: ").append(gl.GL_EXT_memory_object).append(", win32: ").append(gl.GL_EXT_memory_object_win32)
                        .append(", copy_image: ").append(gl.OpenGL43 || gl.GL_ARB_copy_image).append('\n');
            }
        } catch (Throwable t) {
            b.append("GL: ? (").append(t).append(")\n");
        }
        b.append("Direct GPU sharing: setting ").append(dev.windowscraft.config.ClientSettings.gpuSharing())
                .append(", state ").append(dev.windowscraft.render.GpuInterop.state()).append(" — ").append(dev.windowscraft.render.GpuInterop.reason()).append('\n');
        b.append("Performance settings: off-screen pause ").append(dev.windowscraft.config.ClientSettings.offscreenPause())
                .append(", distance limit ").append(dev.windowscraft.config.ClientSettings.distanceFpsLimit())
                .append(", fewer mipmaps ").append(dev.windowscraft.config.ClientSettings.lightMipmaps()).append('\n');
        b.append(dev.windowscraft.render.DisplayRenderer.stats()).append('\n');
        for (var s : dev.windowscraft.display.DisplayManager.get().liveShares()) b.append("Sharing ").append(s.describe()).append('\n');
        b.append("Server passes on \"can't keep up\": ").append(ClientNet.troubleSupported()).append('\n');
        for (RemoteDisplays.Remote r : RemoteDisplays.all()) {
            dev.windowscraft.stream.StreamSubscriber sub = r.subscriber();
            b.append("Watching '").append(r.info.title()).append("' by ").append(r.ownerName).append(' ')
                    .append(r.info.width()).append('x').append(r.info.height()).append('@').append(r.info.fps()).append(": ")
                    .append(sub == null ? "not playing" : sub.status() + ", " + sub.framesDecoded() + " pictures, skipped ahead " + sub.catchUps()
                            + "x, sound refills " + sub.audio().underruns() + ", via " + sub.transport()).append('\n');
        }
        b.append("Mods: ");
        FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
                .sorted().forEach(s -> b.append(s).append(", "));
        b.append('\n');
        return b.toString();
    }

    private Report() {}
}
