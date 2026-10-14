package dev.windowscraft.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.windowscraft.measure.Measure;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /windowscraft measure start [seconds] | stop | status}: Step 0 measurements on this PC
 * (off unless you start them). The report lands in {@code windowscraft/measurements/}.
 */
public final class MeasureCommand {
    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("measurements");
    }

    public static void register() {
        Measure.autoFromProperty("client", folder());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("windowscraft").then(ClientCommandManager.literal("measure")
                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> start(0))
                                .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            Path p = Measure.stop();
                            if (p == null) say(Text.literal("WindowsCraft isn't measuring right now.").formatted(Formatting.GRAY));
                            else saved(p);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            say(Text.literal(Measure.status()).formatted(Formatting.GRAY));
                            return 1;
                        })))));
    }

    private static int start(int seconds) {
        boolean ok = seconds > 0
                ? Measure.startFor("client", folder(), seconds, p -> MinecraftClient.getInstance().execute(() -> saved(p)))
                : Measure.start("client", folder());
        if (!ok) say(Text.literal("WindowsCraft is already measuring (" + Measure.status() + ").").formatted(Formatting.GRAY));
        else say(Text.literal(seconds > 0 ? "Measuring for " + seconds + " s…" : "Measuring… stop with /windowscraft measure stop").formatted(Formatting.GRAY));
        return 1;
    }

    private static void saved(Path p) {
        say(Text.literal("Measurements saved: ").formatted(Formatting.GREEN)
                .append(Text.literal(p.getFileName().toString()).formatted(Formatting.UNDERLINE)
                        .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, p.getParent().toString())))));
    }

    private static void say(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(t, false);
    }

    private MeasureCommand() {}
}
