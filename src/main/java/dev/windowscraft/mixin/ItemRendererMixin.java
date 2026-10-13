package dev.windowscraft.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your tablet shows what's on it, in your hand. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/world/World;III)V",
            at = @At("HEAD"), require = 0)
    private void windowscraft$whose(LivingEntity entity, ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                    VertexConsumerProvider vcp, World world, int light, int overlay, int seed, CallbackInfo ci) {
        dev.windowscraft.client.Tablet.ownHand(entity != null && entity == MinecraftClient.getInstance().player);
    }

    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/world/World;III)V",
            at = @At("RETURN"), require = 0)
    private void windowscraft$whoseDone(LivingEntity entity, ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                        VertexConsumerProvider vcp, World world, int light, int overlay, int seed, CallbackInfo ci) {
        dev.windowscraft.client.Tablet.ownHand(false);
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;pop()V"), require = 0)
    private void windowscraft$screen(ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                     VertexConsumerProvider vcp, int light, int overlay, BakedModel model, CallbackInfo ci) {
        dev.windowscraft.client.Tablet.drawOnItem(stack, mode, matrices, vcp);
    }
}
