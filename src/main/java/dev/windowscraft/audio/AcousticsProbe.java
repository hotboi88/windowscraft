/*
 * Ported to Java / Yarn mappings from Dream Displays' VoxelAcousticsProbe
 * (https://github.com/arnodoelinger/dreamdisplays), Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio;

import dev.windowscraft.audio.AcousticModel.Environment;
import dev.windowscraft.audio.AcousticModel.Material;
import dev.windowscraft.audio.AcousticModel.SourcePlane;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Voxel raytracer that turns the blocks between you and a screen into occlusion (walls muffle the
 * sound) and the blocks around the screen into reverb (stone rooms echo, wool absorbs).
 */
public final class AcousticsProbe {
    private static final int REVERB_RAYS = 24;
    private static final double REVERB_MAX_DISTANCE = 24.0, OCCLUSION_STEP_EPSILON = 0.15, OCCLUSION_SAMPLE_INSET = 0.35;
    private static final int MAX_REVERB_PIERCE_STEPS = 6;
    private static final float MAX_REFLECTIVITY = 1.5f;

    private static final Vec3d[] RAY_DIRECTIONS = new Vec3d[REVERB_RAYS];
    static {
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < REVERB_RAYS; i++) {
            double y = 1.0 - (i + 0.5) / REVERB_RAYS * 2.0;
            double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = golden * i;
            RAY_DIRECTIONS[i] = new Vec3d(Math.cos(theta) * r, y, Math.sin(theta) * r);
        }
    }

    /** Sound group → material, seeded from Sound Physics Remastered's defaults (as Dream Displays does). */
    private static final Map<BlockSoundGroup, Material> MATERIALS = buildMaterialTable();

    public static Environment probe(SourcePlane plane, Vec3d ear) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            World world = mc.world;
            Entity entity = mc.player;
            if (world == null || entity == null) return Environment.OPEN_AIR;
            Vec3d center = new Vec3d(plane.centerX(), plane.centerY(), plane.centerZ());
            float occlusion = traceOcclusion(world, entity, ear, plane, center);
            float[] reverb = traceReverb(world, entity, center);
            return new Environment(occlusion, reverb[0], reverb[1], reverb[2]);
        } catch (Throwable t) {
            return Environment.OPEN_AIR;
        }
    }

    private static boolean isTransparent(BlockState state) {
        return state.isIn(BlockTags.LEAVES);
    }

    /**
     * How much the blocks between you and the screen muffle it, as 0..1 of {@link #MAX_WALL_DB}
     * (1 = silent). Every block a ray passes through takes away a few decibels depending on what
     * it's made of (stone ~9 dB, wool more, glass and leaves very little); the five rays to
     * different parts of the screen are combined by how much sound gets through, so a gap still
     * lets some in, but a solid hillside doesn't.
     */
    private static float traceOcclusion(World world, Entity entity, Vec3d ear, SourcePlane plane, Vec3d center) {
        Vec3d u = new Vec3d(plane.uAxisX(), plane.uAxisY(), plane.uAxisZ()).multiply(plane.width() * OCCLUSION_SAMPLE_INSET);
        Vec3d v = new Vec3d(plane.vAxisX(), plane.vAxisY(), plane.vAxisZ()).multiply(plane.height() * OCCLUSION_SAMPLE_INSET);
        Vec3d[] targets = {center, center.add(u), center.subtract(u), center.add(v), center.subtract(v)};
        double through = 0;
        for (Vec3d target : targets) through += Math.pow(10, -wallDb(world, ear, target) / 20.0);
        through /= targets.length;
        double db = through <= 1e-6 ? MAX_WALL_DB : Math.min(MAX_WALL_DB, -20 * Math.log10(through));
        return (float) (db / MAX_WALL_DB);
    }

    /** Loudest a wall can take away: at this much the screen is silent. */
    public static final float MAX_WALL_DB = 60f;
    private static final float DB_PER_SOLID_BLOCK = 9f;

    /** Walks the blocks on the straight line from ear to target (Amanatides–Woo) and adds up their damping. */
    static float wallDb(World world, Vec3d from, Vec3d to) {
        return VoxelWalk.walk(from, to, MAX_WALL_DB, DB_PER_SOLID_BLOCK, (x, y, z) -> {
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) return 0f;
            if (isTransparent(state)) return 0.15f;
            if (state.getBlock() instanceof dev.windowscraft.block.MonitorBlock) return 0f; // a screen's own thin body
            if (state.isOpaqueFullCube(world, pos)) return materialFor(state).occlusion();
            if (!state.getCollisionShape(world, pos).isEmpty()) return materialFor(state).occlusion() * 0.5f; // slabs, doors, panes…
            return 0f; // flowers, torches, grass…
        });
    }

    /** Returns {decaySeconds, wetGain, damping}. */
    private static float[] traceReverb(World world, Entity entity, Vec3d center) {
        int hits = 0;
        double pathSum = 0, reflSum = 0;
        for (Vec3d dir : RAY_DIRECTIONS) {
            double[] r = castReverbRay(world, entity, center, dir);
            if (r != null) {
                hits++;
                pathSum += r[0];
                reflSum += r[1];
            } else {
                pathSum += REVERB_MAX_DISTANCE;
            }
        }
        if (hits == 0) return new float[]{0f, 0f, 0f};
        float enclosure = hits / (float) REVERB_RAYS;
        float meanFreePath = (float) (pathSum / REVERB_RAYS);
        float reflNorm = Math.max(0f, Math.min(1f, (float) (reflSum / hits / MAX_REFLECTIVITY)));
        float size = Math.max(0f, Math.min(1f, meanFreePath / (float) REVERB_MAX_DISTANCE));
        float decay = (0.25f + 2.5f * size) * (0.35f + 0.65f * reflNorm);
        float wet = Math.max(0f, Math.min(1f, enclosure * (0.3f + 0.7f * reflNorm) * 0.6f));
        return new float[]{decay, wet, 1f - reflNorm};
    }

    private static double[] castReverbRay(World world, Entity entity, Vec3d origin, Vec3d dir) {
        Vec3d start = origin;
        double traveled = 0;
        for (int step = 0; step < MAX_REVERB_PIERCE_STEPS; step++) {
            double remaining = REVERB_MAX_DISTANCE - traveled;
            if (remaining <= 0) return null;
            Vec3d end = start.add(dir.multiply(remaining));
            BlockHitResult hit = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, entity));
            if (hit.getType() != HitResult.Type.BLOCK) return null;
            double segment = hit.getPos().distanceTo(start);
            BlockState state = world.getBlockState(hit.getBlockPos());
            if (isTransparent(state)) {
                traveled += segment + OCCLUSION_STEP_EPSILON;
                start = hit.getPos().add(dir.multiply(OCCLUSION_STEP_EPSILON));
                continue;
            }
            return new double[]{traveled + segment, materialFor(state).reflectivity()};
        }
        return null;
    }

    private static Material materialFor(BlockState state) {
        try {
            Material m = MATERIALS.get(state.getSoundGroup());
            return m != null ? m : Material.DEFAULT;
        } catch (Throwable t) {
            return Material.DEFAULT;
        }
    }

    private static Map<BlockSoundGroup, Material> buildMaterialTable() {
        Map<BlockSoundGroup, Material> map = new HashMap<>();
        Material stone = new Material(1.5f, 1.0f);
        for (BlockSoundGroup g : new BlockSoundGroup[]{BlockSoundGroup.STONE, BlockSoundGroup.DEEPSLATE, BlockSoundGroup.TUFF,
                BlockSoundGroup.CALCITE, BlockSoundGroup.BASALT, BlockSoundGroup.AMETHYST_BLOCK, BlockSoundGroup.BONE,
                BlockSoundGroup.NETHER_BRICKS, BlockSoundGroup.NETHERITE}) map.put(g, stone);
        map.put(BlockSoundGroup.METAL, new Material(1.25f, 1.0f));
        map.put(BlockSoundGroup.COPPER, new Material(1.25f, 1.0f));
        map.put(BlockSoundGroup.NETHERRACK, new Material(1.1f, 1.0f));
        map.put(BlockSoundGroup.GLASS, new Material(0.75f, 0.1f));
        map.put(BlockSoundGroup.WOOD, new Material(0.4f, 1.0f));
        map.put(BlockSoundGroup.NETHER_STEM, new Material(0.4f, 1.0f));
        map.put(BlockSoundGroup.GRAVEL, new Material(0.3f, 1.0f));
        map.put(BlockSoundGroup.GRASS, new Material(0.3f, 1.0f));
        map.put(BlockSoundGroup.SAND, new Material(0.2f, 1.0f));
        map.put(BlockSoundGroup.SOUL_SAND, new Material(0.2f, 1.0f));
        map.put(BlockSoundGroup.SOUL_SOIL, new Material(0.2f, 1.0f));
        map.put(BlockSoundGroup.CORAL, new Material(0.2f, 1.0f));
        map.put(BlockSoundGroup.SNOW, new Material(0.15f, 0.1f));
        map.put(BlockSoundGroup.POWDER_SNOW, new Material(0.15f, 0.1f));
        map.put(BlockSoundGroup.WOOL, new Material(0.1f, 1.5f));
        map.put(BlockSoundGroup.HONEY, new Material(0.1f, 0.5f));
        map.put(BlockSoundGroup.MOSS_BLOCK, new Material(0.1f, 0.75f));
        map.put(BlockSoundGroup.WET_GRASS, new Material(0.2f, 0.1f));
        return map;
    }

    private AcousticsProbe() {}
}
