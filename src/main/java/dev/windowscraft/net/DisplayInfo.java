package dev.windowscraft.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/**
 * What other players need to know about a live screen: where it is, what it shows, and which
 * relay stream carries its picture and sound. Sizes are the encoded video size. {@code controllers}
 * are the players the owner lets use this screen (mouse and keyboard); nobody else can. The box is
 * where the shared app's client area is inside the stream's picture (0..1), so a pointer means
 * the same spot of the app for everyone.
 */
public record DisplayInfo(UUID displayId, String dimension, String face, BlockPos min, BlockPos max,
                          String title, String streamId, int width, int height, int fps, int fit, List<UUID> controllers,
                          float boxX0, float boxY0, float boxX1, float boxY1) {
    public static final int MAX_TITLE = 120;
    public static final int MAX_CONTROLLERS = 16;

    public DisplayInfo {
        controllers = List.copyOf(controllers);
    }

    public boolean controllableBy(UUID player) { return player != null && controllers.contains(player); }

    public void write(PacketByteBuf buf) {
        buf.writeUuid(displayId);
        buf.writeString(dimension, 256);
        buf.writeString(face, 128); // (a floating monitor says how it hangs)
        buf.writeBlockPos(min);
        buf.writeBlockPos(max);
        buf.writeString(title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title, MAX_TITLE * 4);
        buf.writeString(streamId, 64);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        buf.writeVarInt(fps);
        buf.writeByte(fit);
        int n = Math.min(MAX_CONTROLLERS, controllers.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUuid(controllers.get(i));
        buf.writeFloat(boxX0);
        buf.writeFloat(boxY0);
        buf.writeFloat(boxX1);
        buf.writeFloat(boxY1);
    }

    public static DisplayInfo read(PacketByteBuf buf) {
        UUID id = buf.readUuid();
        String dim = buf.readString(256), face = buf.readString(128);
        BlockPos min = buf.readBlockPos(), max = buf.readBlockPos();
        String title = buf.readString(MAX_TITLE * 4), stream = buf.readString(64);
        int w = buf.readVarInt(), h = buf.readVarInt(), fps = buf.readVarInt(), fit = buf.readByte();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CONTROLLERS) throw new IllegalArgumentException("too many controllers");
        List<UUID> c = new ArrayList<>(n);
        for (int i = 0; i < n; i++) c.add(buf.readUuid());
        return new DisplayInfo(id, dim, face, min, max, title, stream, w, h, fps, fit, c, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    /** Server-side sanity check of something a client sent us. */
    public boolean plausible() {
        if (face.contains("@")) return min.equals(max) && face.matches("(north|south|east|west)@[0-7]") && plausibleRest(); // a gaming monitor block
        int dx = Math.abs(max.getX() - min.getX()) + 1, dy = Math.abs(max.getY() - min.getY()) + 1, dz = Math.abs(max.getZ() - min.getZ()) + 1;
        boolean flat = dx == 1 || dz == 1;
        boolean box = Float.isFinite(boxX0) && Float.isFinite(boxY0) && Float.isFinite(boxX1) && Float.isFinite(boxY1)
                && boxX0 >= -0.02f && boxY0 >= -0.02f && boxX1 <= 1.02f && boxY1 <= 1.02f && boxX1 > boxX0 && boxY1 > boxY0;
        return flat && Math.max(dx, dz) <= 32 && dy <= 24 && plausibleRest() && switch (face) { case "north", "south", "east", "west" -> true; default -> false; };
    }

    private boolean plausibleRest() {
        boolean box = Float.isFinite(boxX0) && Float.isFinite(boxY0) && Float.isFinite(boxX1) && Float.isFinite(boxY1)
                && boxX0 >= -0.02f && boxY0 >= -0.02f && boxX1 <= 1.02f && boxY1 <= 1.02f && boxX1 > boxX0 && boxY1 > boxY0;
        return box && width > 0 && height > 0 && width <= 7680 && height <= 4320 && fps >= 1 && fps <= 120 && streamId.matches("[0-9a-f]{32}");
    }
}
