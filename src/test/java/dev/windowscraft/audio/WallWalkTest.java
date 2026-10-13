package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/** The block walk between your ears and the screen counts each block in between exactly once. */
class WallWalkTest {
    @Test
    void countsEveryBlockInBetweenOnce() {
        // stone at x = 3..5 (a 3-block-thick wall), you at x = 0.5, the screen at x = 10.5
        VoxelWalk.Damping wall = (x, y, z) -> x >= 3 && x <= 5 ? 1f : 0f;
        assertEquals(27f, VoxelWalk.walk(new Vec3d(0.5, 64.6, 0.5), new Vec3d(10.5, 64.6, 0.5), 60f, 9f, wall), 1e-3);
        // diagonally through it: still three blocks' worth along the way (more cells, since it's thicker that way)
        float diag = VoxelWalk.walk(new Vec3d(0.5, 64.6, 0.5), new Vec3d(10.5, 64.6, 7.3), 60f, 9f, wall);
        assertEquals(true, diag >= 27f && diag <= 60f, "diagonal " + diag);
        // nothing in between: nothing lost; your own block and the screen's spot never count
        VoxelWalk.Damping ends = (x, y, z) -> x == 0 || x == 10 ? 1f : 0f;
        assertEquals(0f, VoxelWalk.walk(new Vec3d(0.5, 64.6, 0.5), new Vec3d(10.5, 64.6, 0.5), 60f, 9f, ends), 1e-3);
        // a hillside caps at the maximum
        assertEquals(60f, VoxelWalk.walk(new Vec3d(0.5, 64.6, 0.5), new Vec3d(40.5, 60.2, 3.5), 60f, 9f, (x, y, z) -> 1f), 1e-3);
    }
}
