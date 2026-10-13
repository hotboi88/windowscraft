/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * media/audio module, Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio.dsp;

/** Geometry helpers for a two-emitter (L / R) planar-screen area source. */
public final class EmitterLayout {
    /** Emitter carrying the left channel: 25% in from the left edge. */
    public static Vec3 leftEmitter(Vec3 center, Vec3 uAxis, double width) { return center.minus(uAxis.times(width * 0.25)); }

    /** Emitter carrying the right channel: 25% in from the right edge. */
    public static Vec3 rightEmitter(Vec3 center, Vec3 uAxis, double width) { return center.plus(uAxis.times(width * 0.25)); }

    /** OpenAL-style inverse-distance-clamped attenuation: flat inside refDistance, rolling off past it. */
    public static double distanceGain(double distance, double refDistance, double rolloff) {
        double ref = Math.max(refDistance, 0.1);
        return ref / (ref + rolloff * Math.max(0.0, distance - ref));
    }

    public static double distanceGain(double distance, double refDistance) { return distanceGain(distance, refDistance, 0.7); }

    /** ~1 in front of the screen, floored at backFloor directly behind it. */
    public static double directivityGain(Vec3 normal, Vec3 toListenerDir, double backFloor, double exponent) {
        double cosTheta = StereoPanner.clamp(normal.dot(toListenerDir), -1.0, 1.0);
        double front = Math.pow((cosTheta + 1.0) / 2.0, exponent);
        return backFloor + (1.0 - backFloor) * front;
    }

    public static double directivityGain(Vec3 normal, Vec3 toListenerDir) { return directivityGain(normal, toListenerDir, 0.6, 1.0); }

    private EmitterLayout() {}
}
