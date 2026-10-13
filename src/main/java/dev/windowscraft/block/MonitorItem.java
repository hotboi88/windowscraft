package dev.windowscraft.block;

import dev.windowscraft.display.MonitorPose;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * A gaming monitor placed against the side, top or bottom of another one joins it: no stand, its
 * edge meeting the other's, turned to face you (sneak while placing: kept flat, in line with it).
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
        MonitorBlock.PLACING.set(joined);
        try {
            return super.place(ctx);
        } finally {
            MonitorBlock.PLACING.remove();
        }
    }
}
