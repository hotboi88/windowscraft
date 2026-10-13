package dev.windowscraft.client;

import dev.windowscraft.block.MonitorBlock;
import dev.windowscraft.block.MonitorBlockEntity;
import dev.windowscraft.block.Monitors;
import dev.windowscraft.display.MonitorPose;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

/** Draws the panel of a gaming monitor joined to another one, hanging the way it was placed (no stand). */
public final class MonitorPanelRenderer implements BlockEntityRenderer<MonitorBlockEntity> {
    private static Identifier panelModel(String color) { return Identifier.of("windowscraft", "block/" + color + "_gaming_monitor_panel"); }

    public static void register() {
        List<Identifier> ids = new ArrayList<>();
        for (String c : Monitors.COLORS) ids.add(panelModel(c));
        ModelLoadingPlugin.register(ctx -> ctx.addModels(ids));
        BlockEntityRendererRegistry.register(Monitors.MONITOR_ENTITY, ctx -> new MonitorPanelRenderer());
    }

    @Override
    public void render(MonitorBlockEntity be, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcs, int light, int overlay) {
        BlockState st = be.getCachedState();
        MonitorPose p = be.pose();
        if (p == null || !(st.getBlock() instanceof MonitorBlock) || !st.get(MonitorBlock.FREE)) return;
        String color = null;
        for (var e : Monitors.MONITORS.entrySet()) if (e.getValue() == st.getBlock()) { color = e.getKey(); break; }
        if (color == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        BakedModel model = mc.getBakedModelManager().getModel(panelModel(color));
        if (model == null) return;
        matrices.push();
        matrices.translate(p.cx(), p.cy(), p.cz());
        matrices.multiply(new Quaternionf((float) p.qx(), (float) p.qy(), (float) p.qz(), (float) p.qw()));
        matrices.translate(-0.5, -9 / 16.0, -0.5); // (the panel model's centre)
        mc.getBlockRenderManager().getModelRenderer().render(matrices.peek(), vcs.getBuffer(TexturedRenderLayers.getEntityCutout()),
                st, model, 1f, 1f, 1f, light, overlay);
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(MonitorBlockEntity be) { return true; } // (a joined panel can reach a little past its block)
}
