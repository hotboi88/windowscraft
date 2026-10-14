package dev.windowscraft.net;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.windowscraft.measure.Measure;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /windowscraft-server measure start [seconds] | stop | status} (server console or an
 * operator): Step 0 measurements of the relay on a dedicated server, off unless started. In a
 * world opened to LAN the host's {@code /windowscraft measure} covers the relay too (same game).
 */
public final class MeasureServerCommand {
    static void register() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        Path folder = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("measurements");
        Measure.autoFromProperty("server", folder);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
                CommandManager.literal("windowscraft-server").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("measure")
                                .then(CommandManager.literal("start")
                                        .executes(ctx -> start(ctx.getSource(), folder, 0))
                                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                                .executes(ctx -> start(ctx.getSource(), folder, IntegerArgumentType.getInteger(ctx, "seconds")))))
                                .then(CommandManager.literal("stop").executes(ctx -> {
                                    Path p = Measure.stop();
                                    ctx.getSource().sendFeedback(() -> Text.literal(p == null ? "Not measuring" : "Measurements saved: " + p), false);
                                    return 1;
                                }))
                                .then(CommandManager.literal("status").executes(ctx -> {
                                    ctx.getSource().sendFeedback(() -> Text.literal(Measure.status()), false);
                                    return 1;
                                })))));
    }

    private static int start(ServerCommandSource src, Path folder, int seconds) {
        boolean ok = seconds > 0 ? Measure.startFor("server", folder, seconds, null) : Measure.start("server", folder);
        src.sendFeedback(() -> Text.literal(ok ? "Measuring the relay" + (seconds > 0 ? " for " + seconds + " s" : "") : Measure.status()), false);
        return 1;
    }

    private MeasureServerCommand() {}
}
