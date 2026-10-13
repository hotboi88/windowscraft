package dev.windowscraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.windowscraft.block.Monitors;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In first person you hold a tablet the way you hold a map: in your hand (both hands if the other is free), its live screen on it. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @WrapOperation(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;isOf(Lnet/minecraft/item/Item;)Z"), require = 0)
    private boolean windowscraft$heldLikeAMap(ItemStack stack, Item item, Operation<Boolean> original) {
        if (item == Items.FILLED_MAP && Monitors.isTablet(stack)) return true;
        return original.call(stack, item);
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void windowscraft$heldUp(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
                                     float equipProgress, MatrixStack matrices, VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        if (!Monitors.isTablet(item)) return;
        if (dev.windowscraft.client.Tablet.raised()) ci.cancel(); // held up big instead
        else if (hand == Hand.OFF_HAND && Monitors.isTablet(player.getMainHandStack())) ci.cancel(); // (one tablet's enough: it's the same screen)
    }

    @Inject(method = "renderFirstPersonMap", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;get(Lnet/minecraft/component/ComponentType;)Ljava/lang/Object;"),
            cancellable = true, require = 0)
    private void windowscraft$tablet(MatrixStack matrices, VertexConsumerProvider vcp, int light, ItemStack stack, CallbackInfo ci) {
        if (!Monitors.isTablet(stack)) return;
        dev.windowscraft.client.Tablet.drawLikeMap(stack, matrices, vcp, light);
        ci.cancel();
    }
}
