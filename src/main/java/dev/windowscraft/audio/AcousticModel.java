/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio;

/** Plain data types shared by the acoustics engine and the voxel probe (Dream Displays' audio API models). */
public final class AcousticModel {
    /** Rendering tier, from disabled to the full environmental chain. */
    public enum Quality { OFF, BASIC, ADVANCED }

    /** Acoustic space between source and listener. */
    public record Environment(float occlusion, float reverbDecaySeconds, float reverbWetGain, float reverbDamping) {
        public static final Environment OPEN_AIR = new Environment(0f, 0f, 0f, 0f);
    }

    /** Coefficients per block material. */
    public record Material(float reflectivity, float occlusion) {
        public static final Material DEFAULT = new Material(0.5f, 1.0f);
    }

    /** World-space listener pose (position + forward/up basis). */
    public record ListenerPose(double x, double y, double z, double forwardX, double forwardY, double forwardZ,
                               double upX, double upY, double upZ) {
        public static final ListenerPose IDENTITY = new ListenerPose(0, 0, 0, 0, 0, -1, 0, 1, 0);
    }

    /** World-space planar sound source; u axis points to the viewer's right, one block = one metre. */
    public record SourcePlane(double centerX, double centerY, double centerZ, double normalX, double normalY, double normalZ,
                              double uAxisX, double uAxisY, double uAxisZ, double vAxisX, double vAxisY, double vAxisZ,
                              double width, double height) {}

    /** Latest geometry and mix state for one source, published every tick. */
    public record SourceState(SourcePlane plane, float userVolume, boolean muted, Environment environment) {}

    private AcousticModel() {}
}
