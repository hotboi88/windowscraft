package dev.windowscraft.net;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * The (tiny) WindowsCraft protocol. Video never travels over the Minecraft connection; these
 * packets only say which screens are live where, and how to reach the relay that carries them.
 */
public final class Payloads {
    public static final int PROTOCOL = 3;

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String path) {
        return new CustomPayload.Id<>(Identifier.of("windowscraft", path));
    }

    /** Server → client on join: "this server runs WindowsCraft", relay port and your relay token. */
    public record Hello(int protocol, int relayPort, String token, boolean streaming) implements CustomPayload {
        public static final Id<Hello> ID = id("hello");
        public static final PacketCodec<PacketByteBuf, Hello> CODEC = PacketCodec.of(
                (v, b) -> { b.writeVarInt(v.protocol); b.writeVarInt(v.relayPort); b.writeString(v.token, 64); b.writeBoolean(v.streaming); },
                b -> new Hello(b.readVarInt(), b.readVarInt(), b.readString(64), b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client → server: one of my screens is live (re-sent periodically as a heartbeat). */
    public record Announce(DisplayInfo info) implements CustomPayload {
        public static final Id<Announce> ID = id("announce");
        public static final PacketCodec<PacketByteBuf, Announce> CODEC = PacketCodec.of((v, b) -> v.info.write(b), b -> new Announce(DisplayInfo.read(b)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → client: reply to an announce; tells the owner whether the relay is accepting streams. */
    public record AnnounceAck(UUID displayId, boolean relayOpen, String message) implements CustomPayload {
        public static final Id<AnnounceAck> ID = id("announce_ack");
        public static final PacketCodec<PacketByteBuf, AnnounceAck> CODEC = PacketCodec.of(
                (v, b) -> { b.writeUuid(v.displayId); b.writeBoolean(v.relayOpen); b.writeString(v.message, 256); },
                b -> new AnnounceAck(b.readUuid(), b.readBoolean(), b.readString(256)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client → server: my screen stopped. */
    public record Remove(UUID displayId) implements CustomPayload {
        public static final Id<Remove> ID = id("remove");
        public static final PacketCodec<PacketByteBuf, Remove> CODEC = PacketCodec.of((v, b) -> b.writeUuid(v.displayId), b -> new Remove(b.readUuid()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → client: someone else's screen is live (or changed). */
    public record DisplayState(DisplayInfo info, UUID owner, String ownerName) implements CustomPayload {
        public static final Id<DisplayState> ID = id("display_state");
        public static final PacketCodec<PacketByteBuf, DisplayState> CODEC = PacketCodec.of(
                (v, b) -> { v.info.write(b); b.writeUuid(v.owner); b.writeString(v.ownerName, 64); },
                b -> new DisplayState(DisplayInfo.read(b), b.readUuid(), b.readString(64)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → client: that screen is no longer live. */
    public record DisplayGone(UUID displayId) implements CustomPayload {
        public static final Id<DisplayGone> ID = id("display_gone");
        public static final PacketCodec<PacketByteBuf, DisplayGone> CODEC = PacketCodec.of((v, b) -> b.writeUuid(v.displayId), b -> new DisplayGone(b.readUuid()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** What a player does with someone else's screen (only reaches its owner if they allowed it). */
    public static final int OP_MOVE = 0, OP_BUTTON_DOWN = 1, OP_BUTTON_UP = 2, OP_WHEEL = 3, OP_KEY_DOWN = 4, OP_KEY_UP = 5,
            OP_SIT = 6, OP_STAND = 7, OP_POINT = 8, OP_UNPOINT = 9, OP_PAD = 10, OP_LAST = 10;

    /**
     * A game controller's whole state in one {@link #OP_PAD} message (x, y, a, f): the left stick
     * as x and y, the buttons (Xbox layout: XInput's bits) and both triggers (0..255) in a, the
     * right stick in f — 12 bits each, a whole number a float carries exactly. Sticks are -1..1,
     * up positive (as XInput has it).
     */
    public record GamePad(int buttons, int leftTrigger, int rightTrigger, float lx, float ly, float rx, float ry) {
        public static final GamePad NEUTRAL = new GamePad(0, 0, 0, 0, 0, 0, 0);

        public float x() { return clamp(lx); }
        public float y() { return clamp(ly); }
        public int a() { return (buttons & 0xFFFF) | (Math.max(0, Math.min(255, leftTrigger)) << 16) | (Math.max(0, Math.min(255, rightTrigger)) << 24); }
        public float f() { return q(rx) * 4096 + q(ry); }

        public static GamePad of(float x, float y, int a, float f) {
            int packed = Math.max(0, Math.min(4096 * 4096 - 1, Math.round(f)));
            return new GamePad(a & 0xFFFF, (a >>> 16) & 0xFF, (a >>> 24) & 0xFF, clamp(x), clamp(y), unq(packed >> 12), unq(packed & 0xFFF));
        }

        /** XInput's -32768..32767 for a stick value. */
        public static int axis(float v) { return Math.max(-32768, Math.min(32767, Math.round(clamp(v) * 32767f))); }

        private static float clamp(float v) { return Float.isFinite(v) ? Math.max(-1f, Math.min(1f, v)) : 0f; }
        private static int q(float v) { return Math.round((clamp(v) + 1f) / 2f * 4095f); }
        private static float unq(int q) { return q == 2048 || q == 2047 ? 0f : q / 4095f * 2f - 1f; }
    }

    /** Client → server: I'm using that screen (x, y: the pointer, 0..1 across and down its picture; a: button/scancode; f: wheel). */
    public record Control(UUID displayId, int op, float x, float y, int a, float f) implements CustomPayload {
        public static final Id<Control> ID = id("control");
        public static final PacketCodec<PacketByteBuf, Control> CODEC = PacketCodec.of(
                (v, b) -> { b.writeUuid(v.displayId); b.writeByte(v.op); b.writeFloat(v.x); b.writeFloat(v.y); b.writeVarInt(v.a); b.writeFloat(v.f); },
                b -> dev.windowscraft.measure.Stamps.arrived(new Control(b.readUuid(), b.readByte(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat())));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → the screen's owner: a player they allowed is using it. */
    public record ControlFrom(UUID from, String fromName, Control c) implements CustomPayload {
        public static final Id<ControlFrom> ID = id("control_from");
        public static final PacketCodec<PacketByteBuf, ControlFrom> CODEC = PacketCodec.of(
                (v, b) -> { b.writeUuid(v.from); b.writeString(v.fromName, 64); Control.CODEC.encode(b, v.c); },
                b -> dev.windowscraft.measure.Stamps.arrived(new ControlFrom(b.readUuid(), b.readString(64), Control.CODEC.decode(b))));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Owner → server → everyone nearby: where the screen's pointer is, and whether it shows at all. */
    public record Pointer(UUID displayId, boolean visible, float x, float y) implements CustomPayload {
        public static final Id<Pointer> ID = id("pointer");
        public static final PacketCodec<PacketByteBuf, Pointer> CODEC = PacketCodec.of(
                (v, b) -> { b.writeUuid(v.displayId); b.writeBoolean(v.visible); b.writeFloat(v.x); b.writeFloat(v.y); },
                b -> new Pointer(b.readUuid(), b.readBoolean(), b.readFloat(), b.readFloat()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A viewer's PC can't play the stream in time (it keeps skipping ahead). */
    public static final int TROUBLE_DECODE = 1;
    /** The stream can't reach a viewer in time (the server had to skip them ahead). */
    public static final int TROUBLE_NETWORK = 2;

    /**
     * Viewer → server: I can't keep up with that screen. Server → its owner: someone watching it
     * can't keep up (from a viewer, or from the relay itself), so send less. Newer servers only;
     * without it everything works, the owner just doesn't hear about it.
     */
    public record StreamTrouble(UUID displayId, int kind) implements CustomPayload {
        public static final Id<StreamTrouble> ID = id("stream_trouble");
        public static final PacketCodec<PacketByteBuf, StreamTrouble> CODEC = PacketCodec.of(
                (v, b) -> { b.writeUuid(v.displayId); b.writeByte(v.kind); },
                b -> new StreamTrouble(b.readUuid(), b.readByte()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Server → player: the relay also takes viewers over UDP on this port (0: TCP only). Newer
     * servers only; a client that never hears it just uses TCP.
     */
    public record RelayInfo(int udpPort) implements CustomPayload {
        public static final Id<RelayInfo> ID = id("relay_info");
        public static final PacketCodec<PacketByteBuf, RelayInfo> CODEC = PacketCodec.of(
                (v, b) -> b.writeVarInt(v.udpPort), b -> new RelayInfo(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → player on join: the WindowsCraft this server has (players with an older one can get it from here). */
    public record UpdateOffer(String version, String fileName, int size, String sha256) implements CustomPayload {
        public static final Id<UpdateOffer> ID = id("update_offer");
        public static final PacketCodec<PacketByteBuf, UpdateOffer> CODEC = PacketCodec.of(
                (v, b) -> { b.writeString(v.version, 64); b.writeString(v.fileName, 128); b.writeVarInt(v.size); b.writeString(v.sha256, 64); },
                b -> new UpdateOffer(b.readString(64), b.readString(128), b.readVarInt(), b.readString(64)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Player → server: send me that WindowsCraft. */
    public record UpdateRequest(String version) implements CustomPayload {
        public static final Id<UpdateRequest> ID = id("update_request");
        public static final PacketCodec<PacketByteBuf, UpdateRequest> CODEC = PacketCodec.of(
                (v, b) -> b.writeString(v.version, 64), b -> new UpdateRequest(b.readString(64)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server → player: one piece of it. */
    public record UpdateChunk(int index, int total, byte[] data) implements CustomPayload {
        public static final int SIZE = 256 * 1024;
        public static final Id<UpdateChunk> ID = id("update_chunk");
        public static final PacketCodec<PacketByteBuf, UpdateChunk> CODEC = PacketCodec.of(
                (v, b) -> { b.writeVarInt(v.index); b.writeVarInt(v.total); b.writeByteArray(v.data); },
                b -> new UpdateChunk(b.readVarInt(), b.readVarInt(), b.readByteArray(SIZE)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(UpdateOffer.ID, UpdateOffer.CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateRequest.ID, UpdateRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(UpdateChunk.ID, UpdateChunk.CODEC);
        PayloadTypeRegistry.playS2C().register(RelayInfo.ID, RelayInfo.CODEC);
        PayloadTypeRegistry.playC2S().register(StreamTrouble.ID, StreamTrouble.CODEC);
        PayloadTypeRegistry.playS2C().register(StreamTrouble.ID, StreamTrouble.CODEC);
        PayloadTypeRegistry.playC2S().register(Control.ID, Control.CODEC);
        PayloadTypeRegistry.playS2C().register(ControlFrom.ID, ControlFrom.CODEC);
        PayloadTypeRegistry.playC2S().register(Pointer.ID, Pointer.CODEC);
        PayloadTypeRegistry.playS2C().register(Pointer.ID, Pointer.CODEC);
        PayloadTypeRegistry.playS2C().register(Hello.ID, Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(AnnounceAck.ID, AnnounceAck.CODEC);
        PayloadTypeRegistry.playS2C().register(DisplayState.ID, DisplayState.CODEC);
        PayloadTypeRegistry.playS2C().register(DisplayGone.ID, DisplayGone.CODEC);
        PayloadTypeRegistry.playC2S().register(Announce.ID, Announce.CODEC);
        PayloadTypeRegistry.playC2S().register(Remove.ID, Remove.CODEC);
    }

    private Payloads() {}
}
