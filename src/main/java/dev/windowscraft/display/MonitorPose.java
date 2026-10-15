package dev.windowscraft.display;

import java.util.Locale;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Where a free-floating gaming monitor's panel is: its centre (in its block, 0..1 — a little past
 * the block is fine) and how it's turned (a rotation of the panel made facing south: x right,
 * y up, z out of the screen). Monitors joined to another one get this when placed, so they can
 * hang at any angle off their neighbour's edge, without a stand.
 */
public record MonitorPose(double cx, double cy, double cz, double qx, double qy, double qz, double qw) {
    /** The panel: 16 × 10 × 2 model pixels. */
    public static final double HALF_W = 0.5, HALF_H = 5 / 16.0, FRONT = 1 / 16.0;
    /** The screen on it (model pixels from the panel's centre): x −7..7, y −3.875..4 (16:9). */
    public static final double SCREEN_L = -7 / 16.0, SCREEN_R = 7 / 16.0, SCREEN_B = -3.875 / 16.0, SCREEN_T = 4 / 16.0; // (16:9, as the texture's screen)

    /** A monitor standing on its foot, turned {@code turn} × 45° (like the block model). */
    public static MonitorPose standing(int turn) {
        Quaterniond q = new Quaterniond().rotationY(-Math.toRadians(Math.floorMod(turn, 8) * 45));
        return new MonitorPose(0.5, 9 / 16.0, 0.5, q.x, q.y, q.z, q.w);
    }

    public Quaterniond rotation() { return new Quaterniond(qx, qy, qz, qw); }

    /** A direction in the panel's own terms (x right, y up, z out of the screen), in the world. */
    public Vec3d turn(double x, double y, double z) {
        Vector3d v = rotation().transform(new Vector3d(x, y, z));
        return new Vec3d(v.x, v.y, v.z);
    }

    public Vec3d right() { return turn(1, 0, 0); }
    public Vec3d up() { return turn(0, 1, 0); }
    public Vec3d normal() { return turn(0, 0, 1); }

    /** A point of the panel (its own terms, blocks from its centre), in the world. */
    public Vec3d at(BlockPos pos, double x, double y, double z) {
        return new Vec3d(pos.getX() + cx, pos.getY() + cy, pos.getZ() + cz).add(turn(x, y, z));
    }

    /** Close enough to be the same pose (it went through a text form on the way). */
    public boolean near(MonitorPose o) {
        if (o == null) return false;
        double dot = Math.abs(qx * o.qx + qy * o.qy + qz * o.qz + qw * o.qw);
        return Math.abs(cx - o.cx) < 2e-3 && Math.abs(cy - o.cy) < 2e-3 && Math.abs(cz - o.cz) < 2e-3 && dot > 1 - 1e-5;
    }

    public String encode() {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%.5f", cx, cy, cz, qx, qy, qz, qw);
    }

    /** The other way round; null if it isn't one. */
    public static MonitorPose parse(String s) {
        try {
            String[] p = s.split(",");
            if (p.length != 7) return null;
            double[] v = new double[7];
            for (int i = 0; i < 7; i++) {
                v[i] = Double.parseDouble(p[i]);
                if (!Double.isFinite(v[i])) return null;
            }
            double n = Math.sqrt(v[3] * v[3] + v[4] * v[4] + v[5] * v[5] + v[6] * v[6]);
            if (n < 0.5 || Math.abs(v[0] - 0.5) > 2 || Math.abs(v[1] - 0.5) > 2 || Math.abs(v[2] - 0.5) > 2) return null;
            return new MonitorPose(v[0], v[1], v[2], v[3] / n, v[4] / n, v[5] / n, v[6] / n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The nearest of the 8 block turns to the way it faces (0 south, 2 west, 4 north, 6 east). */
    public int nearestTurn() {
        Vec3d n = normal();
        if (n.horizontalLengthSquared() < 1e-6) n = up().multiply(-Math.signum(n.y)); // (facing straight up or down)
        double a = Math.toDegrees(Math.atan2(-n.x, n.z));
        return Math.floorMod((int) Math.round(a / 45), 8);
    }

    // ---- joining one to another ----------------------------------------------------------------

    /** Which edge of the other monitor a new one joins. */
    public enum Edge { RIGHT, LEFT, TOP, BOTTOM }

    /**
     * A monitor placed in {@code child}, joined to the edge of the one in {@code parentPos} that
     * faces it: hinged on that edge (the screens' fronts meet there), and swung to face {@code eye}
     * as well as it can (or kept flat in line with the other one when {@code flat}). Null if the new
     * block isn't beside, above or below that monitor's screen.
     */
    public static MonitorPose join(MonitorPose parent, BlockPos parentPos, BlockPos child, Vec3d eye, boolean flat) {
        Vec3d pc = parent.at(parentPos, 0, 0, 0);
        Vec3d d = Vec3d.ofCenter(child).subtract(pc);
        double dr = d.dotProduct(parent.right()), du = d.dotProduct(parent.up()), dn = d.dotProduct(parent.normal());
        double side = Math.max(Math.abs(dr), Math.abs(du));
        if (side < 0.45 || Math.abs(dn) > side * 1.2) return null; // in front or behind it: a monitor of its own
        Edge e = Math.abs(dr) >= Math.abs(du) ? (dr > 0 ? Edge.RIGHT : Edge.LEFT) : (du > 0 ? Edge.TOP : Edge.BOTTOM);
        return join(parent, parentPos, child, e, eye, flat);
    }

    public static MonitorPose join(MonitorPose parent, BlockPos parentPos, BlockPos child, Edge e, Vec3d eye, boolean flat) {
        boolean sideways = e == Edge.RIGHT || e == Edge.LEFT;
        double s = e == Edge.RIGHT || e == Edge.TOP ? 1 : -1;
        // the hinge: the middle of that edge, on the screen's front; it turns about the edge's line
        Vec3d hinge = sideways ? parent.at(parentPos, s * HALF_W, 0, FRONT) : parent.at(parentPos, 0, s * HALF_H, FRONT);
        Vec3d axis = sideways ? parent.up() : parent.right();
        Quaterniond base = parent.rotation();
        double best = 0;
        if (!flat && eye != null) {
            double bestScore = -2;
            for (int deg = -75; deg <= 75; deg++) {
                double a = Math.toRadians(deg);
                MonitorPose p = hinged(base, axis, a, hinge, sideways, s, child);
                Vec3d to = eye.subtract(p.at(child, 0, 0, 0));
                if (to.lengthSquared() < 1e-6) continue;
                double score = to.normalize().dotProduct(p.normal());
                if (score > bestScore + 1e-9) { bestScore = score; best = a; }
            }
            // whole steps of 5° (and a hair off straight is straight)
            double deg = Math.round(Math.toDegrees(best) / 5.0) * 5.0;
            best = Math.abs(deg) < 7.5 ? 0 : Math.toRadians(deg);
        }
        MonitorPose p = hinged(base, axis, best, hinge, sideways, s, child);
        Vec3d c = new Vec3d(p.cx, p.cy, p.cz);
        if (c.x < -0.6 || c.x > 1.6 || c.y < -0.6 || c.y > 1.6 || c.z < -0.6 || c.z > 1.6) return null; // too far from its block
        return p;
    }

    private static MonitorPose hinged(Quaterniond base, Vec3d axis, double angle, Vec3d hinge, boolean sideways, double s, BlockPos child) {
        // turning the far edge toward the screen's front (toward you) is positive, on either side
        double signed = sideways ? -s * angle : s * angle;
        Quaterniond q = new Quaterniond().rotateAxis(signed, axis.x, axis.y, axis.z).mul(base);
        Vector3d off = q.transform(sideways ? new Vector3d(s * HALF_W, 0, -FRONT) : new Vector3d(0, s * HALF_H, -FRONT));
        Vec3d c = hinge.add(off.x, off.y, off.z).subtract(child.getX(), child.getY(), child.getZ());
        q.normalize();
        return new MonitorPose(c.x, c.y, c.z, q.x, q.y, q.z, q.w);
    }
}
