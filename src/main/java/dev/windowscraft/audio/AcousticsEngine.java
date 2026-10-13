/*
 * Ported to Java from Dream Displays (https://github.com/arnodoelinger/dreamdisplays),
 * Copyright (c) Arno Dölinger and contributors.
 * Licensed under the GNU Lesser General Public License v3.0; see LICENSE.
 */
package dev.windowscraft.audio;

import dev.windowscraft.audio.AcousticModel.ListenerPose;
import dev.windowscraft.audio.AcousticModel.Quality;
import dev.windowscraft.audio.AcousticModel.SourceState;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one {@link AudioRenderChain} per audible display plus the shared listener pose and settings. */
public final class AcousticsEngine {
    public static final float SAMPLE_RATE = 48000f;
    private static final AcousticsEngine INSTANCE = new AcousticsEngine(SAMPLE_RATE);

    private final float sampleRate;
    private final Map<UUID, AudioRenderChain> chains = new ConcurrentHashMap<>();
    private volatile ListenerPose listener = ListenerPose.IDENTITY;
    private volatile Quality quality = Quality.ADVANCED;
    private volatile boolean binaural = true;
    private volatile boolean normalization = true;

    public AcousticsEngine(float sampleRate) { this.sampleRate = sampleRate; }

    public static AcousticsEngine get() { return INSTANCE; }

    public AudioRenderChain register(UUID id) { return chains.computeIfAbsent(id, k -> new AudioRenderChain(sampleRate, this)); }
    public void unregister(UUID id) { chains.remove(id); }
    public void update(UUID id, SourceState state) {
        AudioRenderChain c = chains.get(id);
        if (c != null) c.updateState(state);
    }

    public void setListener(ListenerPose pose) { listener = pose; }
    public void setQuality(Quality q) { quality = q; }
    public void setBinaural(boolean b) { binaural = b; }
    public void setNormalization(boolean n) { normalization = n; }

    ListenerPose listener() { return listener; }
    Quality quality() { return quality; }
    boolean binaural() { return binaural; }
    boolean normalization() { return normalization; }
}
