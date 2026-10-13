package dev.windowscraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.windowscraft.client.TheaterMode;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Your screens: clicks and the wheel go to the screen you look at; in theater mode the mouse moves its pointer.
 * Theater mode: the mouse wheel moves the camera closer/further instead of changing the hotbar
 * slot, and moving the mouse looks around from the theater seat instead of turning your body.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void windowscraft$theaterZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (dev.windowscraft.client.ScreenControl.onScroll(mc, vertical) || TheaterMode.scroll(vertical)) ci.cancel();
    }

    /** Clicks on a screen you're looking at (or using in theater mode) go to the screen. */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void windowscraft$buttonToScreen(long window, int button, int action, int mods, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (window == mc.getWindow().getHandle() && dev.windowscraft.client.ScreenControl.onMouseButton(mc, button, action)) ci.cancel();
    }

    @org.spongepowered.asm.mixin.Shadow private double cursorDeltaX;
    @org.spongepowered.asm.mixin.Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void windowscraft$rawDelta(double timeDelta, CallbackInfo ci) {
        dev.windowscraft.client.ScreenControl.rawDX = cursorDeltaX;
        dev.windowscraft.client.ScreenControl.rawDY = cursorDeltaY;
    }

    @WrapOperation(method = "updateMouse",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void windowscraft$theaterLook(ClientPlayerEntity player, double dx, double dy, Operation<Void> original) {
        // your screen in theater mode: the mouse moves its pointer (by the movement before
        // Minecraft's sensitivity, so it feels like your desktop cursor)
        if (dev.windowscraft.client.ScreenControl.onLook(net.minecraft.client.MinecraftClient.getInstance(),
                dev.windowscraft.client.ScreenControl.rawDX, dev.windowscraft.client.ScreenControl.rawDY)) return;
        if (!TheaterMode.look(dx, dy)) original.call(player, dx, dy);
    }
}
