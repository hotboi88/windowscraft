package dev.windowscraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.windowscraft.render.DisplayRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Right after the whole world is rendered — including a shader pack's final pass — draw the screens
 * that should keep their true colours (see {@link dev.windowscraft.render.ShaderCompat}).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Before the world: which screen you're pointing at (and its pointer). */
    @org.spongepowered.asm.mixin.injection.Inject(method = "renderWorld", at = @At("HEAD"))
    private void windowscraft$pointing(RenderTickCounter tickCounter, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        dev.windowscraft.client.ScreenControl.frame(net.minecraft.client.MinecraftClient.getInstance());
    }

    @WrapOperation(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void windowscraft$afterWorld(WorldRenderer worldRenderer, RenderTickCounter tickCounter, boolean blockOutlines, Camera camera,
                                         GameRenderer gameRenderer, LightmapTextureManager lightmap, Matrix4f view, Matrix4f projection,
                                         Operation<Void> original) {
        original.call(worldRenderer, tickCounter, blockOutlines, camera, gameRenderer, lightmap, view, projection);
        DisplayRenderer.renderAfterShaders(camera, view, projection);
    }
}
