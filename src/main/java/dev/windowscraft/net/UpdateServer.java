package dev.windowscraft.net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server hands out the WindowsCraft it runs itself: on join every player with the mod hears
 * which version that is, and one with an older version (who said yes to updates from this
 * server) asks for it and gets the jar in pieces over the Minecraft connection. Updating
 * everyone is then just updating the server.
 */
final class UpdateServer {
    private static final Logger LOG = LoggerFactory.getLogger("WindowsCraft server");
    private static byte[] jar;
    private static Payloads.UpdateOffer offer;
    private static boolean looked;
    private static final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    /** This server's own WindowsCraft jar (null in a development setup, where it isn't one file). */
    private static synchronized Payloads.UpdateOffer offer() {
        if (looked) return offer;
        looked = true;
        try {
            Path file = null;
            String test = System.getProperty("windowscraft.update.testJar"); // (automated tests)
            String version;
            if (test != null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
                file = Path.of(test);
                version = System.getProperty("windowscraft.update.testVersion", "999.0.0");
            } else {
                var mod = FabricLoader.getInstance().getModContainer("windowscraft").orElse(null);
                if (mod == null) return null;
                version = mod.getMetadata().getVersion().getFriendlyString();
                for (Path p : mod.getOrigin().getPaths()) if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) file = p;
            }
            if (file == null) return null;
            jar = Files.readAllBytes(file);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
            offer = new Payloads.UpdateOffer(version, file.getFileName().toString(), jar.length, sha);
            LOG.info("Offering WindowsCraft {} ({} KB) to players with an older one", version, jar.length / 1024);
        } catch (Exception e) {
            LOG.warn("Couldn't read this server's WindowsCraft jar for updates: {}", e.toString());
        }
        return offer;
    }

    static void onJoin(ServerPlayerEntity player) {
        Payloads.UpdateOffer o = offer();
        if (o != null && ServerPlayNetworking.canSend(player, Payloads.UpdateOffer.ID)) ServerPlayNetworking.send(player, o);
    }

    static void onRequest(ServerPlayerEntity player, Payloads.UpdateRequest r) {
        Payloads.UpdateOffer o = offer();
        if (o == null || !o.version().equals(r.version()) || !ServerPlayNetworking.canSend(player, Payloads.UpdateChunk.ID)) return;
        long now = System.currentTimeMillis();
        Long last = lastSent.get(player.getUuid());
        if (last != null && now - last < 60_000) return; // once a minute at most
        lastSent.put(player.getUuid(), now);
        int total = (jar.length + Payloads.UpdateChunk.SIZE - 1) / Payloads.UpdateChunk.SIZE;
        for (int i = 0; i < total; i++) {
            int from = i * Payloads.UpdateChunk.SIZE, to = Math.min(jar.length, from + Payloads.UpdateChunk.SIZE);
            ServerPlayNetworking.send(player, new Payloads.UpdateChunk(i, total, java.util.Arrays.copyOfRange(jar, from, to)));
        }
        LOG.info("Sent WindowsCraft {} to {}", o.version(), player.getGameProfile().getName());
    }

    private UpdateServer() {}
}
