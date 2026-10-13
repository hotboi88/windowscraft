package dev.windowscraft.block;

import dev.windowscraft.display.MonitorPose;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * A gaming monitor placed beside, above or below another one (against its edge, or on the desk
 * right next to it) joins it: no stand, its edge meeting the other's, turned to face you (sneak
 * while placing: kept flat, in line with it).
 */
public final class MonitorItem extends BlockItem {
    public MonitorItem(Block block, Settings settings) { super(block, settings); }

    @Override
    public ActionResult place(ItemPlacementContext ctx) {
        MonitorPose joined = null;
        BlockPos at = ctx.getBlockPos(), clicked = at.offset(ctx.getSide().getOpposite());
        if (!clicked.equals(at) && ctx.getWorld().getBlockState(clicked).getBlock() instanceof MonitorBlock) {
            MonitorPose parent = MonitorBlock.poseAt(ctx.getWorld(), clicked);
            boolean flat = ctx.getPlayer() != null && ctx.getPlayer().isSneaking();
            if (parent != null) joined = MonitorPose.join(parent, clicked, at, ctx.getPlayer() != null ? ctx.getPlayer().getEyePos() : null, flat);
        }
        // placed right beside, above or below another monitor (on the desk next to it, not only
        // against its thin edge): it joins that one too
        if (joined == null && !(ctx.getWorld().getBlockState(clicked).getBlock() instanceof MonitorBlock)) {
            boolean flat = ctx.getPlayer() != null && ctx.getPlayer().isSneaking();
            net.minecraft.util.math.Vec3d eye = ctx.getPlayer() != null ? ctx.getPlayer().getEyePos() : null;
            double best = Double.MAX_VALUE;
            for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                    net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST, net.minecraft.util.math.Direction.NORTH,
                    net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.DOWN, net.minecraft.util.math.Direction.UP}) {
                BlockPos p = at.offset(dir);
                if (!(ctx.getWorld().getBlockState(p).getBlock() instanceof MonitorBlock)) continue;
                MonitorPose parent = MonitorBlock.poseAt(ctx.getWorld(), p);
                if (parent == null) continue;
                MonitorPose pose = MonitorPose.join(parent, p, at, eye, flat);
                if (pose == null) continue;
                // (two to choose from: the one you're looking at more)
                double d = eye == null ? 0 : net.minecraft.util.math.Vec3d.ofCenter(p).squaredDistanceTo(eye.add(ctx.getPlayer().getRotationVec(1f).multiply(2)));
                if (d < best) {
                    best = d;
                    joined = pose;
                }
            }
        }
        MonitorBlock.PLACING.set(joined);
        try {
            return super.place(ctx);
        } finally {
            MonitorBlock.PLACING.remove();
        }
    }
}
