package dev.windowscraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GamePadTest {
    /** As the relay carries it: the four numbers as text and back. */
    private static Payloads.GamePad overTheRelay(Payloads.GamePad p) {
        String line = "C " + p.x() + " " + p.y() + " " + p.a() + " " + p.f();
        String[] f = line.split(" ");
        return Payloads.GamePad.of(Float.parseFloat(f[1]), Float.parseFloat(f[2]), Integer.parseInt(f[3]), Float.parseFloat(f[4]));
    }

    @Test
    void neutralStaysNeutral() {
        Payloads.GamePad n = overTheRelay(Payloads.GamePad.NEUTRAL);
        assertEquals(0, n.buttons());
        assertEquals(0, n.leftTrigger());
        assertEquals(0, n.rightTrigger());
        for (float v : new float[]{n.lx(), n.ly(), n.rx(), n.ry()}) assertEquals(0, Payloads.GamePad.axis(v));
    }

    @Test
    void everyButtonAndBothTriggersArrive() {
        Payloads.GamePad p = overTheRelay(new Payloads.GamePad(0xF7FF, 255, 128, 0, 0, 0, 0));
        assertEquals(0xF7FF, p.buttons());
        assertEquals(255, p.leftTrigger());
        assertEquals(128, p.rightTrigger());
        assertTrue(p.a() < 0, "the right trigger's top bit makes it negative: still carried");
    }

    @Test
    void sticksArriveCloseEnough() {
        float[][] cases = {{1, -1, 1, -1}, {-1, 1, -1, 1}, {0.5f, -0.25f, 0.3f, -0.7f}, {0.001f, 0, -0.999f, 0.0005f}};
        for (float[] c : cases) {
            Payloads.GamePad p = overTheRelay(new Payloads.GamePad(0, 0, 0, c[0], c[1], c[2], c[3]));
            assertEquals(Payloads.GamePad.axis(c[0]), Payloads.GamePad.axis(p.lx()), 1);
            assertEquals(Payloads.GamePad.axis(c[1]), Payloads.GamePad.axis(p.ly()), 1);
            // the right stick in 12 bits: within one step (16 of XInput's 65536)
            assertEquals(Payloads.GamePad.axis(c[2]), Payloads.GamePad.axis(p.rx()), 17);
            assertEquals(Payloads.GamePad.axis(c[3]), Payloads.GamePad.axis(p.ry()), 17);
        }
        Payloads.GamePad full = overTheRelay(new Payloads.GamePad(0, 0, 0, 1, -1, 1, -1));
        assertEquals(32767, Payloads.GamePad.axis(full.rx()));
        assertEquals(-32767, Payloads.GamePad.axis(full.ry()));
    }

    @Test
    void nonsenseIsHarmless() {
        Payloads.GamePad p = Payloads.GamePad.of(Float.NaN, 7f, 0, -5f);
        assertEquals(0, Payloads.GamePad.axis(p.lx()));
        assertEquals(32767, Payloads.GamePad.axis(p.ly()));
        assertEquals(-32767, Payloads.GamePad.axis(p.rx()));
    }
}
