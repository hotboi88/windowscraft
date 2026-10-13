package dev.windowscraft.block;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import dev.windowscraft.display.MonitorPose;
import dev.windowscraft.display.WallRect;
import net.minecraft.text.Text;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * A gaming monitor you can put anywhere: it faces you when you place it, in any of 8 directions
 * (straight or diagonal). Look at it and press U and it becomes a screen like a black concrete
 * wall does — an in-game monitor, or a window of yours.
 */
public final class MonitorBlock extends Block implements BlockEntityProvider {
    public static final MapCodec<MonitorBlock> CODEC = createCodec(MonitorBlock::new);
    /** Which way the screen faces: yaw {@code turn × 45°} (0 south, 2 west, 4 north, 6 east, odd ones in between). */
    public static final IntProperty TURN = IntProperty.of("turn", 0, 7);
    /** Joined to another monitor: no stand, the panel hangs where its block entity says (drawn by its renderer). */
    public static final BooleanProperty FREE = BooleanProperty.of("free");
    /** Placing one right now: where its panel goes when it joins another (set by {@link MonitorItem}). */
    static final ThreadLocal<MonitorPose> PLACING = new ThreadLocal<>();

    /** The outline for each turn (the model is made facing north, turn 4). */
    private static final VoxelShape[] SHAPES = new VoxelShape[8];

    static {
        double[][] north = {{0, 4, 7, 16, 14, 9}, {7, 1, 9, 9, 7, 10}, {4, 0, 6, 12, 1, 12}};
        for (int t = 0; t < 8; t += 2) {
            int quarters = Math.floorMod(t - 4, 8) / 2; // clockwise quarter turns from north
            VoxelShape s = VoxelShapes.empty();
            for (double[] b : north) {
                double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
                for (int q = 0; q < quarters; q++) { // (x, z) → (16 − z, x)
                    double nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
                    x0 = nx0; x1 = nx1; z0 = nz0; z1 = nz1;
                }
                s = VoxelShapes.union(s, Block.createCuboidShape(x0, b[1], z0, x1, b[4], z1));
            }
            SHAPES[t] = s;
        }
        // diagonal: one tidy box around the turned panel and its stand (a staircase along the
        // panel looked jagged); the picture's own pointing and theater checks look past it
        for (int t = 1; t < 8; t += 2) SHAPES[t] = Block.createCuboidShape(2, 0, 2, 14, 14, 14);
    }

    public MonitorBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(TURN, 0).with(FREE, false));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() { return CODEC; }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(TURN, FREE); }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new MonitorBlockEntity(pos, state); }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        MonitorPose p = PLACING.get();
        if (p != null && state.get(FREE) && world.getBlockEntity(pos) instanceof MonitorBlockEntity be) be.setPose(p);
    }

    /** Where a monitor's panel is: its own pose if it floats, otherwise standing on its foot. */
    public static MonitorPose poseAt(BlockView world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        if (!(st.getBlock() instanceof MonitorBlock)) return null;
        if (st.get(FREE) && world.getBlockEntity(pos) instanceof MonitorBlockEntity be && be.pose() != null) return be.pose();
        return MonitorPose.standing(st.get(TURN));
    }

    /** The screen of the monitor block there (null if there isn't one). */
    public static WallRect rectAt(BlockView world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        if (!(st.getBlock() instanceof MonitorBlock)) return null;
        if (st.get(FREE) && world.getBlockEntity(pos) instanceof MonitorBlockEntity be && be.pose() != null) return WallRect.posed(pos, be.pose());
        return WallRect.monitor(pos, st.get(TURN));
    }

    /** A floating panel's outline: the box around it, kept inside its own block. */
    static VoxelShape shapeOf(MonitorPose p) {
        double x0 = 9, y0 = 9, z0 = 9, x1 = -9, y1 = -9, z1 = -9;
        for (int i = 0; i < 8; i++) {
            Vec3d v = new Vec3d(p.cx(), p.cy(), p.cz()).add(p.turn((i & 1) == 0 ? -MonitorPose.HALF_W : MonitorPose.HALF_W,
                    (i & 2) == 0 ? -MonitorPose.HALF_H : MonitorPose.HALF_H, (i & 4) == 0 ? -MonitorPose.FRONT : MonitorPose.FRONT));
            x0 = Math.min(x0, v.x); y0 = Math.min(y0, v.y); z0 = Math.min(z0, v.z);
            x1 = Math.max(x1, v.x); y1 = Math.max(y1, v.y); z1 = Math.max(z1, v.z);
        }
        Box b = new Box(Math.max(0, x0), Math.max(0, y0), Math.max(0, z0), Math.min(1, x1), Math.min(1, y1), Math.min(1, z1));
        if (b.getLengthX() < 0.05 || b.getLengthY() < 0.05 || b.getLengthZ() < 0.05) return Block.createCuboidShape(4, 4, 4, 12, 12, 12);
        return VoxelShapes.cuboid(b);
    }

    /** The screen faces you: whichever of the 8 directions is nearest. */
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        MonitorPose joined = PLACING.get();
        if (joined != null) return getDefaultState().with(TURN, joined.nearestTurn()).with(FREE, true);
        int turn = Math.floorMod(Math.round((ctx.getPlayerYaw() + 180) / 45f), 8);
        return getDefaultState().with(TURN, turn);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(FREE)) {
            return world.getBlockEntity(pos) instanceof MonitorBlockEntity be && be.shape() != null ? be.shape() : Block.createCuboidShape(4, 4, 4, 12, 12, 12);
        }
        return SHAPES[state.get(TURN)];
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        int quarters = switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        return state.with(TURN, Math.floorMod(state.get(TURN) + quarters * 2, 8));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        int t = state.get(TURN);
        return switch (mirror) {
            case LEFT_RIGHT -> state.with(TURN, Math.floorMod(4 - t, 8)); // north ↔ south
            case FRONT_BACK -> state.with(TURN, Math.floorMod(-t, 8)); // east ↔ west
            default -> state;
        };
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType options) {
        tooltip.add(Text.translatable("block.windowscraft.gaming_monitor.tip").formatted(Formatting.GRAY));
    }
}
