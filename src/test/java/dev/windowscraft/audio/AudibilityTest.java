package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.windowscraft.audio.AcousticModel.Environment;
import dev.windowscraft.audio.AcousticModel.ListenerPose;
import dev.windowscraft.audio.AcousticModel.Quality;
import dev.windowscraft.audio.AcousticModel.SourcePlane;
import dev.windowscraft.audio.AcousticModel.SourceState;
import org.junit.jupiter.api.Test;

/** At 500 %: loud up close, quieter behind a wall, silent far away or behind a hillside. */
class AudibilityTest {
    static double level(double distance, float wallDb) {
        AcousticsEngine e = new AcousticsEngine(48000);
        e.setQuality(Quality.ADVANCED);
        AudioRenderChain c = new AudioRenderChain(48000, e);
        float occ = wallDb / AcousticsProbe.MAX_WALL_DB;
        c.updateState(new SourceState(new SourcePlane(0, 0, -distance, 0, 0, 1, 1, 0, 0, 0, 1, 0, 4, 2), 5f, false,
                new Environment(occ, 0, 0, 0)));
        e.setListener(ListenerPose.IDENTITY);
        float[] in = ClickTest.music(48000 * 8, 0.3, 0);
        double sum = 0;
        int n = 0;
        for (int b = 0; b < in.length; b += 960) {
            byte[] buf = ClickTest.pcm(in, in, b, 960);
            c.process(buf, buf.length, 1.0);
            if (b > 48000 * 6) for (int i = 0; i < 960; i++) { double s = ClickTest.sample(buf, i, 0); sum += s * s; n++; }
        }
        return 20 * Math.log10(Math.sqrt(sum / n) + 1e-12);
    }

    @Test
    void wallsAndDistanceSilenceIt() {
        double close = level(4, 0), oneWall = level(4, 9), far = level(70, 0), buried = level(20, 60), midHill = level(48, 40);
        System.out.printf("500%%: close %.0f dB, behind one stone wall %.0f dB, 70 blocks away %.0f dB, behind a hillside %.0f dB, 3 chunks + 4-5 blocks of stone %.0f dB%n",
                close, oneWall, far, buried, midHill);
        assertTrue(close > -12, "loud up close");
        assertTrue(oneWall < close - 6 && oneWall > -40, "one wall: quieter and muffled, still there");
        assertTrue(far < -100, "silent beyond 4 chunks");
        assertTrue(buried < -100, "silent behind a hillside");
        assertTrue(midHill < -55, "next to inaudible 3 chunks away behind several blocks");
    }
}
