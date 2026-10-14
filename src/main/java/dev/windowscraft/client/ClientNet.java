package dev.windowscraft.client;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.net.DisplayInfo;
import dev.windowscraft.net.Payloads;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Client side of the WindowsCraft protocol: knows whether this server runs WindowsCraft, where
 * its relay is, and forwards other players' screens to {@link RemoteDisplays}.
 */
public final class ClientNet {
    public record Ack(boolean relayOpen, String message) {}

    private static volatile Payloads.Hello hello;
    private static volatile String relayHost = "127.0.0.1";
    private static final Map<UUID, Ack> acks = new ConcurrentHashMap<>();

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            hello = null;
            acks.clear();
            SocketAddress a = handler.getConnection().getAddress();
            // the relay lives next to the Minecraft server: same host, its own port
            relayHost = a instanceof InetSocketAddress isa && isa.getAddress() != null && !isa.getAddress().isAnyLocalAddress()
                    ? isa.getAddress().getHostAddress() : "127.0.0.1";
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            hello = null;
            relayUdpPort = 0;
            acks.clear();
            mc.execute(RemoteDisplays::clear);
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Hello.ID, (p, ctx) -> {
            if (p.protocol() != Payloads.PROTOCOL) {
                WindowsCraftClient.LOGGER.warn("Server speaks WindowsCraft protocol {}, we speak {} — update the mod on both", p.protocol(), Payloads.PROTOCOL);
                return;
            }
            hello = p;
            WindowsCraftClient.LOGGER.info("Server runs WindowsCraft; relay at {}:{}", relayHost, p.relayPort());
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.RelayInfo.ID, (p, ctx) -> relayUdpPort = p.udpPort());
        ClientPlayNetworking.registerGlobalReceiver(Payloads.AnnounceAck.ID, (p, ctx) -> acks.put(p.displayId(), new Ack(p.relayOpen(), p.message())));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.DisplayState.ID, (p, ctx) -> ctx.client().execute(() -> RemoteDisplays.upsert(p)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.DisplayGone.ID, (p, ctx) -> ctx.client().execute(() -> RemoteDisplays.remove(p.displayId())));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.ControlFrom.ID, (p, ctx) -> ScreenControl.fromGuest(ctx.client(), p)); // (already on the game thread: straight to your monitor)
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Pointer.ID, (p, ctx) -> ctx.client().execute(() -> RemoteDisplays.pointer(p)));
        // someone watching one of your screens can't keep up: its stream sends less
        ClientPlayNetworking.registerGlobalReceiver(Payloads.StreamTrouble.ID, (p, ctx) -> ctx.client().execute(() -> {
            dev.windowscraft.display.Display d = dev.windowscraft.display.DisplayManager.get().byId(p.displayId());
            if (d != null && d.publisher() != null) d.publisher().viewerTrouble(p.kind());
        }));
    }

    /** Tells the screen's owner (through the server) that we can't keep up with it. Any thread. */
    public static void trouble(UUID displayId, int kind) {
        MinecraftClient.getInstance().execute(() -> {
            if (serverSupported() && ClientPlayNetworking.canSend(Payloads.StreamTrouble.ID))
                ClientPlayNetworking.send(new Payloads.StreamTrouble(displayId, kind));
        });
    }

    /** Does this server pass "a viewer can't keep up" on to whoever is streaming? */
    public static boolean troubleSupported() {
        return serverSupported() && ClientPlayNetworking.canSend(Payloads.StreamTrouble.ID);
    }

    /** True once the server has said it runs WindowsCraft. */
    public static boolean serverSupported() { return hello != null; }
    /** Can other people actually watch here? (False in a singleplayer world nobody can join.) */
    public static boolean streamingAvailable() { return hello != null && hello.streaming(); }
    public static String relayHost() { return relayHost; }
    public static int relayPort() { return hello == null ? 0 : hello.relayPort(); }
    private static volatile int relayUdpPort;
    /** The relay's UDP port for watching (0: this server's relay only does TCP). */
    public static int relayUdpPort() { return hello == null ? 0 : relayUdpPort; }
    public static String token() { return hello == null ? "" : hello.token(); }
    public static Ack ack(UUID displayId) { return acks.get(displayId); }

    public static void announce(DisplayInfo info) {
        MinecraftClient.getInstance().execute(() -> {
            if (serverSupported() && ClientPlayNetworking.canSend(Payloads.Announce.ID)) ClientPlayNetworking.send(new Payloads.Announce(info));
        });
    }

    public static void remove(UUID displayId) {
        acks.remove(displayId);
        MinecraftClient.getInstance().execute(() -> {
            if (serverSupported() && ClientPlayNetworking.canSend(Payloads.Remove.ID)) ClientPlayNetworking.send(new Payloads.Remove(displayId));
        });
    }

    /** Using someone else's screen (client thread). */
    public static void control(UUID displayId, int op, double x, double y, int a, double f) {
        // over the relay when it takes input: straight to the owner, not between the server's ticks
        dev.windowscraft.stream.StreamSubscriber sub = RemoteDisplays.subscriberOf(displayId);
        if (sub != null && sub.sendControl(displayId, op, (float) x, (float) y, a, (float) f)) return;
        if (serverSupported() && ClientPlayNetworking.canSend(Payloads.Control.ID))
            ClientPlayNetworking.send(new Payloads.Control(displayId, op, (float) x, (float) y, a, (float) f));
    }

    /** Where your screen's pointer is, for everyone watching it (client thread). */
    public static void pointer(UUID displayId, boolean visible, double x, double y) {
        if (serverSupported() && ClientPlayNetworking.canSend(Payloads.Pointer.ID))
            ClientPlayNetworking.send(new Payloads.Pointer(displayId, visible, (float) x, (float) y));
    }

    private ClientNet() {}
}
