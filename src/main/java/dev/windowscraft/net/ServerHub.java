package dev.windowscraft.net;

import dev.windowscraft.relay.RelayProtocol;
import dev.windowscraft.relay.RelayServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of WindowsCraft: remembers which screens are live, tells everyone, and runs the
 * relay. Works on dedicated servers and on "Open to LAN" worlds. Players without the mod are
 * simply never sent anything.
 */
public final class ServerHub {
    private static final Logger LOG = LoggerFactory.getLogger("WindowsCraft server");
    private static final long STALE_MILLIS = 30_000;
    private static final int MAX_DISPLAYS_PER_PLAYER = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Live(DisplayInfo info, UUID owner, String ownerName, long lastSeen) {}

    private static final Map<UUID, Live> displays = new ConcurrentHashMap<>();
    private static final Map<String, UUID> tokens = new ConcurrentHashMap<>();
    private static RelayServer relay;
    private static int relayPort = RelayProtocol.DEFAULT_PORT;
    private static boolean relayEnabled = true;
    /** The shader pack on screens, for everyone here (on unless someone turned it off). */
    private static volatile boolean screensShaded = true;
    private static int tick;

    public static void init() {
        loadConfig();
        MeasureServerCommand.register(); // Step 0 measurements of the relay (off unless started)
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(ServerHub::expireStale);
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Announce.ID, (p, ctx) -> onAnnounce(ctx.player(), p.info()));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Remove.ID, (p, ctx) -> onRemove(ctx.player(), p.displayId()));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Control.ID, (p, ctx) -> onControl(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Pointer.ID, (p, ctx) -> onPointer(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.StreamTrouble.ID, (p, ctx) -> onTrouble(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.UpdateRequest.ID, (p, ctx) -> UpdateServer.onRequest(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.ScreensShaded.ID, (p, ctx) -> onScreensShaded(ctx.player(), p.on()));
    }

    /** Per screen: when its owner was last told someone can't keep up (at most every 5 s). */
    private static final Map<String, Long> troubleSent = new ConcurrentHashMap<>(); // (per screen and kind of trouble)

    /** A viewer can't keep up with a screen: its owner's game sends less. */
    private static void onTrouble(ServerPlayerEntity player, Payloads.StreamTrouble t) {
        Live l = displays.get(t.displayId());
        if (l == null || l.owner.equals(player.getUuid())) return;
        int kind = t.kind() == Payloads.TROUBLE_DECODE ? Payloads.TROUBLE_DECODE : Payloads.TROUBLE_NETWORK;
        tellOwner(player.getServer(), l, kind);
    }

    private static void tellOwner(MinecraftServer server, Live l, int kind) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        Long last = troubleSent.get(l.info.displayId() + "/" + kind);
        if (last != null && now - last < 5000) return;
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(l.owner);
        if (owner == null || !ServerPlayNetworking.canSend(owner, Payloads.StreamTrouble.ID)) return;
        troubleSent.put(l.info.displayId() + "/" + kind, now);
        ServerPlayNetworking.send(owner, new Payloads.StreamTrouble(l.info.displayId(), kind));
    }

    private static void loadConfig() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("windowscraft-server.json");
        try {
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("relayPort")) relayPort = o.get("relayPort").getAsInt();
                if (o.has("relayEnabled")) relayEnabled = o.get("relayEnabled").getAsBoolean();
                if (o.has("screensShaded")) screensShaded = o.get("screensShaded").getAsBoolean();
            } else {
                JsonObject o = new JsonObject();
                o.addProperty("relayPort", relayPort);
                o.addProperty("relayEnabled", relayEnabled);
                o.addProperty("_help", "TCP port for WindowsCraft screen streams. Viewers connect to the same address as the Minecraft server on this port.");
                Files.createDirectories(f.getParent());
                Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read {}, using defaults", f, e);
        }
    }

    private static void onJoin(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, Payloads.Hello.ID)) return; // vanilla / no-mod client
        String token = HexFormat.of().formatHex(randomBytes());
        String old = tokenOf(player.getUuid());
        if (old != null) tokens.remove(old);
        tokens.put(token, player.getUuid());
        MinecraftServer server = player.getServer();
        ServerPlayNetworking.send(player, new Payloads.Hello(Payloads.PROTOCOL, relayPort, token, server != null && relayAllowed(server)));
        sendRelayInfo(player);
        if (ServerPlayNetworking.canSend(player, Payloads.ScreensShaded.ID)) ServerPlayNetworking.send(player, new Payloads.ScreensShaded(screensShaded));
        UpdateServer.onJoin(player);
        for (Live l : displays.values()) {
            if (!l.owner.equals(player.getUuid())) ServerPlayNetworking.send(player, new Payloads.DisplayState(l.info, l.owner, l.ownerName));
        }
    }

    /** Someone flipped the shader pack on screens: it changes for everyone, and stays so. */
    private static void onScreensShaded(ServerPlayerEntity player, boolean on) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (screensShaded != on) {
                screensShaded = on;
                saveSetting("screensShaded", on);
                LOG.info("Shader pack on screens turned {} for everyone by {}", on ? "on" : "off", player.getName().getString());
            }
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (ServerPlayNetworking.canSend(p, Payloads.ScreensShaded.ID)) ServerPlayNetworking.send(p, new Payloads.ScreensShaded(on));
            }
        });
    }

    /** Writes one setting into windowscraft-server.json, keeping the rest. */
    private static void saveSetting(String key, boolean value) {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("windowscraft-server.json");
        try {
            JsonObject o = Files.exists(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            o.addProperty(key, value);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("Couldn't save {} to {}", key, f, e);
        }
    }

    /** Tells a player the relay's UDP port (newer clients watch over UDP: live video that copes with long, lossy paths). */
    private static void sendRelayInfo(ServerPlayerEntity p) {
        RelayServer r = relay;
        if (r != null && r.udpPort() > 0 && ServerPlayNetworking.canSend(p, Payloads.RelayInfo.ID))
            ServerPlayNetworking.send(p, new Payloads.RelayInfo(r.udpPort()));
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String tokenOf(UUID player) {
        for (Map.Entry<String, UUID> e : tokens.entrySet()) if (e.getValue().equals(player)) return e.getKey();
        return null;
    }

    private static void onLeave(ServerPlayerEntity player) {
        controlRate.remove(player.getUuid());
        String token = tokenOf(player.getUuid());
        if (token != null) {
            tokens.remove(token);
            if (relay != null) relay.closeToken(token); // their streams and downloads end with them
        }
        displays.values().removeIf(l -> {
            if (!l.owner.equals(player.getUuid())) return false;
            broadcastGone(player.getServer(), l.info.displayId(), player.getUuid());
            return true;
        });
    }

    private static boolean relayAllowed(MinecraftServer server) {
        // A singleplayer world nobody else can join doesn't need a relay (and shouldn't open a port).
        return relayEnabled && (server.isDedicated() || server.isRemote());
    }

    private static synchronized boolean ensureRelay(MinecraftServer server) {
        if (relay != null) return true;
        if (!relayAllowed(server)) return false;
        try {
            // dev environment only: a fixed watch-only token for the automated latency probe
            String debug = FabricLoader.getInstance().isDevelopmentEnvironment() ? System.getProperty("windowscraft.relay.debugToken") : null;
            UUID debugViewer = UUID.randomUUID();
            // on the address the server itself listens on, when it's given one (server-ip)
            String ip = server.getServerIp();
            relay = new RelayServer(InetAddress.getByName(ip == null || ip.isBlank() ? "0.0.0.0" : ip), relayPort,
                    t -> debug != null && !debug.isBlank() && debug.equals(t) ? debugViewer : tokens.get(t),
                    (player, streamId) -> displays.values().stream().anyMatch(l -> l.owner.equals(player) && l.info.streamId().equals(streamId)));
            // someone's input over the relay: the same checks as onControl, then straight to the owner
            relay.onControl(new RelayServer.ControlRoute() {
                @Override
                public boolean allowed(UUID player, UUID displayId, String streamId) {
                    Live l = displays.get(displayId);
                    return l != null && l.info.streamId().equals(streamId) && l.info.controllableBy(player) && !l.owner.equals(player);
                }

                @Override
                public void viaServer(UUID player, UUID displayId, int op, float x, float y, int a, float f) {
                    server.execute(() -> {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
                        if (p != null) onControl(p, new Payloads.Control(displayId, op, x, y, a, f));
                    });
                }
            });
            // the relay had to skip a viewer ahead (the stream doesn't reach them in time): tell the owner
            relay.onViewerBehind(streamId -> server.execute(() -> displays.values().stream()
                    .filter(l -> l.info.streamId().equals(streamId)).findFirst()
                    .ifPresent(l -> tellOwner(server, l, Payloads.TROUBLE_NETWORK))));
            // anyone who joined while streaming wasn't possible yet (e.g. before "Open to LAN") should know now
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                String t = tokenOf(p.getUuid());
                if (t != null && ServerPlayNetworking.canSend(p, Payloads.Hello.ID)) {
                    ServerPlayNetworking.send(p, new Payloads.Hello(Payloads.PROTOCOL, relayPort, t, true));
                    sendRelayInfo(p);
                }
            }
            return true;
        } catch (Exception e) {
            LOG.error("Couldn't open the WindowsCraft relay on port {} — is something else using it? ({})", relayPort, e.getMessage());
            return false;
        }
    }

    private static void onAnnounce(ServerPlayerEntity player, DisplayInfo info) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!info.plausible()) {
            ServerPlayNetworking.send(player, new Payloads.AnnounceAck(info.displayId(), false, "Rejected: invalid display"));
            return;
        }
        Live existing = displays.get(info.displayId());
        if (existing != null && !existing.owner.equals(player.getUuid())) return; // not yours
        // one stream may feed several walls (mirroring), but only walls of the same owner
        boolean streamTaken = displays.values().stream().anyMatch(l -> l.info.streamId().equals(info.streamId())
                && !l.info.displayId().equals(info.displayId()) && !l.owner.equals(player.getUuid()));
        long mine = displays.values().stream().filter(l -> l.owner.equals(player.getUuid())).count();
        if (streamTaken || (existing == null && mine >= MAX_DISPLAYS_PER_PLAYER)) {
            ServerPlayNetworking.send(player, new Payloads.AnnounceAck(info.displayId(), false,
                    streamTaken ? "Rejected: duplicate stream" : "You already have " + MAX_DISPLAYS_PER_PLAYER + " live screens"));
            return;
        }
        boolean open = ensureRelay(server);
        String msg = open ? "ok" : relayEnabled ? "Nobody else can join this world, so it isn't streamed" : "Streaming is disabled on this server";
        String name = player.getGameProfile().getName();
        if (open) displays.put(info.displayId(), new Live(info, player.getUuid(), name, System.currentTimeMillis())); // before the ack: the relay checks it
        ServerPlayNetworking.send(player, new Payloads.AnnounceAck(info.displayId(), open, msg));
        if (!open) return;
        // heartbeats with unchanged info don't need re-broadcasting
        if (existing == null || !existing.info.equals(info)) {
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                if (!other.getUuid().equals(player.getUuid()) && ServerPlayNetworking.canSend(other, Payloads.DisplayState.ID)) {
                    ServerPlayNetworking.send(other, new Payloads.DisplayState(info, player.getUuid(), name));
                }
            }
        }
    }

    /** Per player: control messages this second (a mouse sends ~60 a second; anything far beyond is dropped). */
    private static final Map<UUID, int[]> controlRate = new ConcurrentHashMap<>();
    private static final int MAX_CONTROL_PER_SECOND = 300;

    private static boolean underLimit(UUID player, int max) {
        int now = (int) (System.currentTimeMillis() / 1000);
        int[] r = controlRate.computeIfAbsent(player, k -> new int[2]);
        if (r[0] != now) { r[0] = now; r[1] = 0; }
        return ++r[1] <= max;
    }

    /** Someone uses a screen: only passed on to its owner if the owner allowed exactly this player. */
    private static final dev.windowscraft.measure.Measure.Metric CONTROL_WAIT = dev.windowscraft.measure.Measure.ms("input.server_wait",
            "someone's click/key/move: arrived at the server → handled on the server thread (waits for the next tick)");
    private static final dev.windowscraft.measure.Measure.Counter CONTROL_DROPPED = dev.windowscraft.measure.Measure.counter("input.rate_dropped",
            "input dropped by the server's 300-a-second limit");
    private static final dev.windowscraft.measure.Measure.Counter CONTROL_DROPPED_RELEASE = dev.windowscraft.measure.Measure.counter("input.rate_dropped_release",
            "of those: a button or key being let go (it then stays held on the screen)");
    private static final dev.windowscraft.measure.Measure.Counter CONTROL_PASSED = dev.windowscraft.measure.Measure.counter("input.server_passed",
            "input passed on to a screen's owner");

    private static void onControl(ServerPlayerEntity player, Payloads.Control c) {
        CONTROL_WAIT.since(dev.windowscraft.measure.Stamps.take(c));
        MinecraftServer server = player.getServer();
        Live l = displays.get(c.displayId());
        if (server == null || l == null || c.op() < 0 || c.op() > Payloads.OP_LAST) return;
        if (!l.info.controllableBy(player.getUuid()) || l.owner.equals(player.getUuid())) return;
        if (!Float.isFinite(c.x()) || !Float.isFinite(c.y()) || !Float.isFinite(c.f())) return;
        if (!underLimit(player.getUuid(), MAX_CONTROL_PER_SECOND)) {
            CONTROL_DROPPED.inc();
            if (c.op() == Payloads.OP_BUTTON_UP || c.op() == Payloads.OP_KEY_UP) CONTROL_DROPPED_RELEASE.inc();
            return;
        }
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(l.owner);
        if (owner == null || !ServerPlayNetworking.canSend(owner, Payloads.ControlFrom.ID)) return;
        CONTROL_PASSED.inc();
        ServerPlayNetworking.send(owner, new Payloads.ControlFrom(player.getUuid(), player.getGameProfile().getName(), c));
    }

    /** The owner's screen pointer moved (or hid): everyone in that dimension sees it. */
    private static void onPointer(ServerPlayerEntity player, Payloads.Pointer p) {
        MinecraftServer server = player.getServer();
        Live l = displays.get(p.displayId());
        if (server == null || l == null || !l.owner.equals(player.getUuid())) return;
        if (!Float.isFinite(p.x()) || !Float.isFinite(p.y())) return;
        if (!underLimit(player.getUuid(), MAX_CONTROL_PER_SECOND)) return;
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            if (other == player || !other.getWorld().getRegistryKey().getValue().toString().equals(l.info.dimension())) continue;
            if (ServerPlayNetworking.canSend(other, Payloads.Pointer.ID)) ServerPlayNetworking.send(other, p);
        }
    }

    private static void onRemove(ServerPlayerEntity player, UUID displayId) {
        Live l = displays.get(displayId);
        if (l == null || !l.owner.equals(player.getUuid())) return;
        displays.remove(displayId);
        if (relay != null) relay.closeStream(l.info.streamId());
        broadcastGone(player.getServer(), displayId, player.getUuid());
    }

    private static void broadcastGone(MinecraftServer server, UUID displayId, UUID except) {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!p.getUuid().equals(except) && ServerPlayNetworking.canSend(p, Payloads.DisplayGone.ID)) {
                ServerPlayNetworking.send(p, new Payloads.DisplayGone(displayId));
            }
        }
    }

    private static void expireStale(MinecraftServer server) {
        if (++tick % 100 != 0) return;
        long now = System.currentTimeMillis();
        displays.values().removeIf(l -> {
            if (now - l.lastSeen < STALE_MILLIS) return false;
            broadcastGone(server, l.info.displayId(), l.owner);
            if (relay != null) relay.closeStream(l.info.streamId());
            return true;
        });
    }

    private static synchronized void shutdown() {
        if (relay != null) relay.close();
        relay = null;
        displays.clear();
        troubleSent.clear();
        tokens.clear();
    }

    private ServerHub() {}
}
