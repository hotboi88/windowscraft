package dev.windowscraft.client;

import dev.windowscraft.display.DisplayManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Are you looking at a screen (yours or someone else's)? Then the game's block outline and the
 * crosshair are hidden, so the picture looks like it's really on the wall.
 */
public final class ScreenLook {
    /** Same reach as the share key, so it also works from across the room. */
    private static final double REACH = 48;

    /** For the vanilla outline: the block it's about to outline. */
    public static boolean isScreen(HitResult hit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK || mc.world == null) return false;
        String dim = mc.world.getRegistryKey().getValue().toString();
        return DisplayManager.get().findAt(dim, b.getBlockPos(), b.getSide()) != null
                || RemoteDisplays.findAt(dim, b.getBlockPos(), b.getSide()) != null;
    }

    /** For the crosshair (once per frame): a longer look than the block reach the game uses. */
    public static boolean lookingAtScreen(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if ((ScreenControl.pointed() != null || ScreenControl.pointedRemote() != null) && ScreenControl.altHeld(mc)) return true; // the screen's own pointer shows instead
        Entity eye = mc.getCameraEntity();
        if (eye == null || mc.world == null || (DisplayManager.get().all().isEmpty() && RemoteDisplays.all().isEmpty())) return false;
        HitResult hit = mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY
                ? null // pointing at a mob or player: keep the crosshair
                : eye.raycast(REACH, tickDelta, false);
        return hit != null && isScreen(hit);
    }

    private ScreenLook() {}
}
