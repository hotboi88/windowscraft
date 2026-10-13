package dev.windowscraft.block;

import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * A tablet: your own hand-held screen. In your hand it shows what's on it; right-click holds it up
 * in front of you to use it with your mouse and keyboard (Esc puts it down). Sneak + right-click
 * turns it off. What it does lives in the client (see {@code dev.windowscraft.client.Tablet}).
 */
public final class TabletItem extends Item {
    /** Set by the client: what right-clicking does (sneaking?). */
    public static volatile BiConsumer<PlayerEntity, Boolean> onUse;

    public TabletItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient() && onUse != null) onUse.accept(user, user.isSneaking());
        return TypedActionResult.consume(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.windowscraft.tablet.tip").formatted(Formatting.GRAY));
    }
}
