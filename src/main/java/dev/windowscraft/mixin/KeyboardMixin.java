package dev.windowscraft.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Theater mode on your own screen: every key goes to the screen, none to Minecraft (or other mods). */
@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void windowscraft$toScreen(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (window == mc.getWindow().getHandle() && dev.windowscraft.client.ScreenControl.onKey(mc, key, scancode, action, modifiers)) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void windowscraft$charToScreen(long window, int codePoint, int modifiers, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (window == mc.getWindow().getHandle() && dev.windowscraft.client.ScreenControl.onChar(mc, codePoint)) ci.cancel();
    }
}
