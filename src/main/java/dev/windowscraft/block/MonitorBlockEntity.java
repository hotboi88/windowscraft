package dev.windowscraft.block;

import dev.windowscraft.display.MonitorPose;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

/** A gaming monitor joined to another: where its panel hangs (saved with the world, sent to everyone). */
public final class MonitorBlockEntity extends BlockEntity {
    private MonitorPose pose;
    private VoxelShape shape;

    public MonitorBlockEntity(BlockPos pos, BlockState state) { super(Monitors.MONITOR_ENTITY, pos, state); }

    public MonitorPose pose() { return pose; }

    VoxelShape shape() { return shape; }

    public void setPose(MonitorPose p) {
        pose = p;
        shape = p != null ? MonitorBlock.shapeOf(p) : null;
        markDirty();
        if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        if (pose != null) nbt.putString("pose", pose.encode());
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        pose = nbt.contains("pose") ? MonitorPose.parse(nbt.getString("pose")) : null;
        shape = pose != null ? MonitorBlock.shapeOf(pose) : null;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) { return createNbt(registries); }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
