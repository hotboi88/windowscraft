package dev.windowscraft.display;

import java.util.function.Predicate;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Finds the screen rectangle around the black concrete block you're looking at: it grows a
 * rectangle outward from that block while every block on the new edge is black concrete with
 * an uncovered front face.
 */
public final class WallDetector {
    public static final int MAX_WIDTH = 32, MAX_HEIGHT = 24; // same limits as Dream Displays

    public static boolean isScreenBlock(ClientWorld world, BlockPos pos, Direction face) {
        if (!world.getBlockState(pos).isOf(Blocks.BLACK_CONCRETE)) return false;
        BlockPos front = pos.offset(face);
        return !world.getBlockState(front).isOpaqueFullCube(world, front);
    }

    /** Returns null if the face isn't a usable wall (e.g. floor/ceiling or covered). */
    public static WallRect detect(ClientWorld world, BlockPos hit, Direction face, Predicate<BlockPos> taken) {
        if (!face.getAxis().isHorizontal()) return null;
        Predicate<BlockPos> ok = p -> !taken.test(p) && isScreenBlock(world, p, face);
        if (!ok.test(hit)) return null;
        Direction right = face.rotateYCounterclockwise();
        int left = 0, rightN = 0, down = 0, up = 0;
        boolean grew = true;
        while (grew) {
            grew = false;
            if (left + rightN + 1 < MAX_WIDTH && column(hit, right, rightN + 1, down, up, ok)) { rightN++; grew = true; }
            if (left + rightN + 1 < MAX_WIDTH && column(hit, right, -(left + 1), down, up, ok)) { left++; grew = true; }
            if (down + up + 1 < MAX_HEIGHT && row(hit, right, up + 1, left, rightN, ok)) { up++; grew = true; }
            if (down + up + 1 < MAX_HEIGHT && row(hit, right, -(down + 1), left, rightN, ok)) { down++; grew = true; }
        }
        BlockPos a = hit.offset(right, -left).down(down);
        BlockPos b = hit.offset(right, rightN).up(up);
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        return new WallRect(face, min, max);
    }

    /** Is every block in a full rectangle still a valid screen block? */
    public static boolean stillValid(ClientWorld world, WallRect rect) {
        if (rect.isMonitor()) { // the same gaming monitor block, turned the same way
            var state = world.getBlockState(rect.min());
            if (!(state.getBlock() instanceof dev.windowscraft.block.MonitorBlock)) return false;
            boolean free = state.get(dev.windowscraft.block.MonitorBlock.FREE);
            if (rect.pose() == null) return !free && state.get(dev.windowscraft.block.MonitorBlock.TURN) == rect.turn();
            if (!free) return false;
            // (a floating one: hanging the same way — or not known yet, while its chunk's details arrive)
            var be = world.getBlockEntity(rect.min());
            if (!(be instanceof dev.windowscraft.block.MonitorBlockEntity m) || m.pose() == null) return true;
            return m.pose().near(rect.pose());
        }
        for (BlockPos p : BlockPos.iterate(rect.min(), rect.max())) {
            if (!isScreenBlock(world, p, rect.face())) return false;
        }
        return true;
    }

    private static boolean column(BlockPos hit, Direction right, int h, int down, int up, Predicate<BlockPos> ok) {
        for (int v = -down; v <= up; v++) if (!ok.test(hit.offset(right, h).up(v))) return false;
        return true;
    }

    private static boolean row(BlockPos hit, Direction right, int v, int left, int rightN, Predicate<BlockPos> ok) {
        for (int h = -left; h <= rightN; h++) if (!ok.test(hit.offset(right, h).up(v))) return false;
        return true;
    }

    private WallDetector() {}
}
