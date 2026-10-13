/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Minimal double-precision 3-vector for source / listener geometry math. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public Vec3 plus(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 minus(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 times(double s) { return new Vec3(x * s, y * s, z * s); }
    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
    public Vec3 cross(Vec3 o) { return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public double length() { return Math.sqrt(dot(this)); }
    public Vec3 normalized() {
        double len = length();
        return len < 1e-9 ? ZERO : new Vec3(x / len, y / len, z / len);
    }
}
