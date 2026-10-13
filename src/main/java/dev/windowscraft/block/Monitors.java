package dev.windowscraft.block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** The gaming monitor blocks and tablets, 32 colours each, and their creative tab. */
public final class Monitors {
    public static final List<String> COLORS = List.of(
            "white", "silver", "light_gray", "ash", "gray", "charcoal", "black", "brown",
            "maroon", "red", "vermilion", "orange", "amber", "yellow", "chartreuse", "lime",
            "fern", "green", "jade", "cyan", "aqua", "light_blue", "cerulean", "blue",
            "indigo", "purple", "violet", "magenta", "orchid", "pink", "rose", "sienna");

    public static final Map<String, MonitorBlock> MONITORS = new LinkedHashMap<>();
    public static final Map<String, Item> MONITOR_ITEMS = new LinkedHashMap<>();
    public static final Map<String, TabletItem> TABLETS = new LinkedHashMap<>();
    public static net.minecraft.block.entity.BlockEntityType<MonitorBlockEntity> MONITOR_ENTITY;

    public static void register() {
        for (String c : COLORS) {
            Identifier id = Identifier.of("windowscraft", c + "_gaming_monitor");
            MonitorBlock b = Registry.register(Registries.BLOCK, id, new MonitorBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.BLACK).strength(1.0f).nonOpaque().sounds(BlockSoundGroup.METAL)
                    .pistonBehavior(PistonBehavior.DESTROY)));
            MONITORS.put(c, b);
            MONITOR_ITEMS.put(c, Registry.register(Registries.ITEM, id, new MonitorItem(b, new Item.Settings())));
        }
        MONITOR_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of("windowscraft", "gaming_monitor"),
                net.minecraft.block.entity.BlockEntityType.Builder.create(MonitorBlockEntity::new, MONITORS.values().toArray(new Block[0])).build(null));
        for (String c : COLORS) {
            TABLETS.put(c, Registry.register(Registries.ITEM, Identifier.of("windowscraft", c + "_tablet"),
                    new TabletItem(new Item.Settings().maxCount(1))));
        }
        ItemGroup group = FabricItemGroup.builder()
                .icon(() -> new ItemStack(MONITOR_ITEMS.get("black")))
                .displayName(Text.translatable("itemGroup.windowscraft"))
                .entries((ctx, entries) -> {
                    for (Item i : MONITOR_ITEMS.values()) entries.add(i);
                    for (Item i : TABLETS.values()) entries.add(i);
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, Identifier.of("windowscraft", "main"), group);
    }

    public static boolean isTablet(ItemStack s) { return s != null && s.getItem() instanceof TabletItem; }

    private Monitors() {}
}
