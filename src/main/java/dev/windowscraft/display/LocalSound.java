package dev.windowscraft.display;

import dev.windowscraft.audio.AudioHub;
import dev.windowscraft.audio.PcmRing;
import dev.windowscraft.audio.SpatialAudioPlayer;
import java.io.IOException;
import java.util.UUID;

/**
 * "Hear it in the game" for the person sharing: the app's captured sound played from the wall,
 * through the same 3D chain other players hear it with.
 */
final class LocalSound {
    final UUID id = UUID.randomUUID();
    private final AudioHub hub;
    private final PcmRing tap;
    private final SpatialAudioPlayer player;
    private final Thread thread;

    LocalSound(AudioHub hub) {
        this.hub = hub;
        this.tap = hub.openTap();
        this.player = new SpatialAudioPlayer(id, "played-yours");
        this.thread = new Thread(() -> {
            try {
                player.play(tap.asInputStream());
            } catch (IOException ignored) {
            } finally {
                player.close();
            }
        }, "WindowsCraft local sound");
        thread.setDaemon(true);
        thread.start();
    }

    void setVolume(double v) { player.setVolume(v); }
    SpatialAudioPlayer player() { return player; }

    void stop() {
        hub.closeTap(tap); // ends play()
    }
}
