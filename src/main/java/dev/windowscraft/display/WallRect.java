package dev.windowscraft.display;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A flat rectangle of wall blocks and the face they show the screen on. {@code min}/{@code max}
 * are the inclusive corner blocks; along the face normal they share one coordinate.
 *
 * <p>Or a gaming monitor block ({@code turn} 0..7, {@code min == max}): its screen faces yaw
 * {@code turn × 45°} (0 south, 2 west, 4 north, 6 east, the odd ones in between), and {@code face}
 * is the side it mostly faces. Walls have {@code turn == -1}.
 */
public record WallRect(Direction face, BlockPos min, BlockPos max, int turn, MonitorPose pose) {
    /** The screen of a gaming monitor block, in its model's own pixels (made facing north): x 1..15, y 5.125..13 (16:9: a
     *  16:9 picture covers it edge to edge — up to 0.15.10 it was 6..13, 2:1, with dark bars beside the picture), set
     *  {@link #RECESS} back from the frame's front at z 7 — behind its lip, like a real monitor's panel. */
    private static final double M_LEFT = 15 / 16.0, M_RIGHT = 1 / 16.0, M_TOP = 13 / 16.0, M_BOTTOM = 5.125 / 16.0;

    /** How far a gaming monitor's screen sits behind the frame around it (half a model pixel). */
    public static final double RECESS = 0.5 / 16.0;

    private static final double M_FRONT = 7 / 16.0 + RECESS - 1 / 1024.0;

    /** How far the picture sits off the block face: practically on it (1/1024 of a block). Keeping
     *  it in front of the concrete is the renderer's job (it pulls the picture toward your eye along
     *  your line of sight, and adds a depth bias), so there's no gap to see from the side. */
    public static final double SURFACE_OFFSET = 1 / 1024.0;

    public WallRect {
        if (!face.getAxis().isHorizontal()) throw new IllegalArgumentException("displays must face sideways");
        if (turn > 7) throw new IllegalArgumentException("turn " + turn);
        if (pose != null && turn < 0) throw new IllegalArgumentException("a pose is for monitor blocks");
    }

    public WallRect(Direction face, BlockPos min, BlockPos max, int turn) { this(face, min, max, turn, null); }

    /** A wall. */
    public WallRect(Direction face, BlockPos min, BlockPos max) { this(face, min, max, -1); }

    /** A free-floating gaming monitor (joined to another one) at {@code pos}, its panel where {@code pose} says. */
    public static WallRect posed(BlockPos pos, MonitorPose pose) {
        int t = pose.nearestTurn();
        return new WallRect(mostlyFacing(t), pos.toImmutable(), pos.toImmutable(), t, pose);
    }

    /** A gaming monitor block at {@code pos}, turned {@code turn} × 45°. */
    public static WallRect monitor(BlockPos pos, int turn) {
        return new WallRect(mostlyFacing(turn), pos.toImmutable(), pos.toImmutable(), Math.floorMod(turn, 8));
    }

    private static Direction mostlyFacing(int turn) {
        return switch (Math.floorMod(turn, 8) / 2) { // (odd turns: the side a quarter-turn clockwise from them)
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    public boolean isMonitor() { return turn >= 0; }

    /** The face as written in network messages and saved files: "north", or "north@5" for a monitor block. */
    public String faceString() {
        if (pose != null) return face.asString() + "@" + turn + "~" + pose.encode(); // ("north@5~0.5,…": a free-floating one)
        return isMonitor() ? face.asString() + "@" + turn : face.asString();
    }

    /** The other way round; null if it isn't one. */
    public static WallRect parse(String face, BlockPos min, BlockPos max) {
        int at = face.indexOf('@');
        Direction d = Direction.byName(at < 0 ? face : face.substring(0, at));
        if (d == null || !d.getAxis().isHorizontal()) return null;
        if (at < 0) return new WallRect(d, min, max);
        int tilde = face.indexOf('~', at);
        if (tilde > 0) {
            MonitorPose p = MonitorPose.parse(face.substring(tilde + 1));
            return p != null && min.equals(max) ? posed(min, p) : null;
        }
        try {
            int t = Integer.parseInt(face.substring(at + 1));
            return t >= 0 && t <= 7 && min.equals(max) ? monitor(min, t) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The way the screen faces (unit length, level). */
    public Vec3d normal() {
        if (!isMonitor()) return Vec3d.of(face.getVector());
        if (pose != null) return pose.normal();
        double a = Math.toRadians(turn * 45);
        return new Vec3d(-Math.sin(a), 0, Math.cos(a));
    }

    /** "Right" for someone looking at the screen (unit length, level). */
    public Vec3d rightVector() {
        if (!isMonitor()) return Vec3d.of(right().getVector());
        if (pose != null) return pose.right();
        Vec3d n = normal();
        return new Vec3d(n.z, 0, -n.x); // (facing south, you look north at it: right is east)
    }

    /** "Up" along the screen (straight up, unless it's a tilted monitor). */
    public Vec3d upVector() { return pose != null ? pose.up() : new Vec3d(0, 1, 0); }

    /** How wide the screen really is, in blocks. */
    public double width() { return isMonitor() ? M_LEFT - M_RIGHT : widthBlocks(); }

    /** How tall the screen really is, in blocks. */
    public double height() { return isMonitor() ? M_TOP - M_BOTTOM : heightBlocks(); }

    /** The direction that is "right" for someone looking at the screen. */
    public Direction right() {
        return face.rotateYCounterclockwise();
    }

    public int widthBlocks() {
        if (isMonitor()) return 1;
        return face.getAxis() == Direction.Axis.Z ? max.getX() - min.getX() + 1 : max.getZ() - min.getZ() + 1;
    }

    public int heightBlocks() {
        return max.getY() - min.getY() + 1;
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    /** Does this rectangle cover all of {@code o} (same face)? */
    public boolean contains(WallRect o) {
        return face == o.face && isMonitor() == o.isMonitor() && contains(o.min) && contains(o.max);
    }

    public boolean overlaps(WallRect o) {
        if (isMonitor() || o.isMonitor()) return min.equals(o.min) && isMonitor() == o.isMonitor();
        return face == o.face
                && min.getX() <= o.max.getX() && max.getX() >= o.min.getX()
                && min.getY() <= o.max.getY() && max.getY() >= o.min.getY()
                && min.getZ() <= o.max.getZ() && max.getZ() >= o.min.getZ();
    }

    public Vec3d center() {
        Vec3d[] c = corners(1, 1);
        return c[0].add(c[2]).multiply(0.5);
    }

    /**
     * The picture's corners {top-left, top-right, bottom-right, bottom-left} in world space,
     * letterboxed so content of the given aspect ratio isn't stretched. Pass (1, 1) for the full
     * wall.
     */
    public Vec3d[] corners(int contentW, int contentH) {
        if (isMonitor()) return monitorCorners(contentW, contentH);
        double plane;
        Direction.Axis axis = face.getAxis();
        int faceBlock = axis == Direction.Axis.Z ? min.getZ() : min.getX();
        plane = face.getDirection() == Direction.AxisDirection.POSITIVE
                ? faceBlock + 1 + SURFACE_OFFSET
                : faceBlock - SURFACE_OFFSET;

        // extent along the horizontal axis of the wall
        double lo = axis == Direction.Axis.Z ? min.getX() : min.getZ();
        double hi = (axis == Direction.Axis.Z ? max.getX() : max.getZ()) + 1;
        boolean rightIsPositive = right().getDirection() == Direction.AxisDirection.POSITIVE;
        double left = rightIsPositive ? lo : hi, rightEdge = rightIsPositive ? hi : lo;
        double top = max.getY() + 1, bottom = min.getY();

        // letterbox
        double w = widthBlocks(), h = heightBlocks();
        double fw = 1, fh = 1;
        if (contentW > 0 && contentH > 0 && !(contentW == 1 && contentH == 1)) {
            double aspect = contentW / (double) contentH;
            if (w / h > aspect) fw = (h * aspect) / w; else fh = (w / aspect) / h;
        }
        double l = lerp(left, rightEdge, (1 - fw) / 2), r = lerp(left, rightEdge, 1 - (1 - fw) / 2);
        double t = lerp(top, bottom, (1 - fh) / 2), b = lerp(top, bottom, 1 - (1 - fh) / 2);
        return new Vec3d[]{at(axis, l, t, plane), at(axis, r, t, plane), at(axis, r, b, plane), at(axis, l, b, plane)};
    }

    /** A monitor block's screen: worked out facing north in its own model, then turned like the block. */
    private Vec3d[] monitorCorners(int contentW, int contentH) {
        double w = width(), h = height(), fw = 1, fh = 1;
        if (contentW > 0 && contentH > 0 && !(contentW == 1 && contentH == 1)) {
            double aspect = contentW / (double) contentH;
            if (w / h > aspect) fw = (h * aspect) / w; else fh = (w / aspect) / h;
        }
        if (pose != null) { // in the panel's own terms: x right, y up, the screen's front
            double z = MonitorPose.FRONT - RECESS + SURFACE_OFFSET; // (behind the frame's lip)
            double l = lerp(MonitorPose.SCREEN_L, MonitorPose.SCREEN_R, (1 - fw) / 2), r = lerp(MonitorPose.SCREEN_L, MonitorPose.SCREEN_R, 1 - (1 - fw) / 2);
            double t = lerp(MonitorPose.SCREEN_T, MonitorPose.SCREEN_B, (1 - fh) / 2), b = lerp(MonitorPose.SCREEN_T, MonitorPose.SCREEN_B, 1 - (1 - fh) / 2);
            return new Vec3d[]{pose.at(min, l, t, z), pose.at(min, r, t, z), pose.at(min, r, b, z), pose.at(min, l, b, z)};
        }
        double l = lerp(M_LEFT, M_RIGHT, (1 - fw) / 2), r = lerp(M_LEFT, M_RIGHT, 1 - (1 - fw) / 2);
        double t = lerp(M_TOP, M_BOTTOM, (1 - fh) / 2), b = lerp(M_TOP, M_BOTTOM, 1 - (1 - fh) / 2);
        return new Vec3d[]{turned(l, t), turned(r, t), turned(r, b), turned(l, b)};
    }

    /** A point of the north-facing model (x, y; on the screen's plane), where it is once the block is turned. */
    private Vec3d turned(double x, double y) {
        double d = Math.toRadians((turn - 4) * 45);
        double px = x - 0.5, pz = M_FRONT - 0.5, cos = Math.cos(d), sin = Math.sin(d);
        return new Vec3d(min.getX() + 0.5 + px * cos - pz * sin, min.getY() + y, min.getZ() + 0.5 + px * sin + pz * cos);
    }

    private static Vec3d at(Direction.Axis faceAxis, double along, double y, double plane) {
        return faceAxis == Direction.Axis.Z ? new Vec3d(along, y, plane) : new Vec3d(plane, y, along);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
