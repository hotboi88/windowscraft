package dev.windowscraft.mixin;

import dev.windowscraft.client.TheaterMode;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Theater mode: after vanilla places the camera, move it in front of the screen being watched. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow public abstract Vec3d getPos();
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();
    @Shadow private boolean thirdPerson;

    @Inject(method = "update", at = @At("TAIL"))
    private void windowscraft$theater(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        // only the game's own camera (other mods may run extra cameras for mirrors, minimaps…)
        if ((Object) this != net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera()) return;
        TheaterMode.Pose p = TheaterMode.cameraPose(getPos(), getYaw(), getPitch(), focused);
        if (p == null) return;
        setRotation(p.yaw(), p.pitch());
        setPos(p.pos());
        if (p.showBody()) this.thirdPerson = true; // the world renderer then draws your own character too
    }
}
