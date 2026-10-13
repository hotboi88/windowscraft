package dev.windowscraft.mixin;

import dev.windowscraft.client.ScreenLook;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crosshair while you're looking at a screen. */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void windowscraft$hideOnScreens(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (ScreenLook.lookingAtScreen(tickCounter.getTickDelta(true))) ci.cancel();
    }
}
