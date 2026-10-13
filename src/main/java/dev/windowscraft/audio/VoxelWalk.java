package dev.windowscraft.audio;

import net.minecraft.util.math.Vec3d;

/** Walks the blocks on a straight line (Amanatides–Woo), adding up how much each one damps sound. */
final class VoxelWalk {
    /** Damping of one block, in "solid stone blocks" (1 = stone). */
    interface Damping { float of(int x, int y, int z); }

    static float walk(Vec3d from, Vec3d to, float maxDb, float dbPerBlock, Damping damping) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        if (len < 1e-3) return 0f;
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int ex = (int) Math.floor(to.x), ey = (int) Math.floor(to.y), ez = (int) Math.floor(to.z);
        int sx = d.x > 0 ? 1 : -1, sy = d.y > 0 ? 1 : -1, sz = d.z > 0 ? 1 : -1;
        double tdx = d.x == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x), tdy = d.y == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y),
                tdz = d.z == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
        double tmx = d.x == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
        double tmy = d.y == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
        double tmz = d.z == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
        float db = 0f;
        for (int guard = 0; guard < 600; guard++) {
            // step into the next block (the block you stand in doesn't count)
            if (tmx < tmy && tmx < tmz) { if (tmx > 1) break; x += sx; tmx += tdx; }
            else if (tmy < tmz) { if (tmy > 1) break; y += sy; tmy += tdy; }
            else { if (tmz > 1) break; z += sz; tmz += tdz; }
            if (x == ex && y == ey && z == ez) break; // the screen's own spot
            db += damping.of(x, y, z) * dbPerBlock;
            if (db >= maxDb) return maxDb;
        }
        return db;
    }

    private VoxelWalk() {}
}
