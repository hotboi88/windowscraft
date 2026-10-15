package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundReserveTest {
    private static final long S = 1_000_000_000L;

    @Test
    void growsWhenLateAndGivesItBackAfterACalmSpell() {
        SoundReserve r = new SoundReserve(120, 0);
        assertTrue(r.late(10 * S));
        assertTrue(r.late(11 * S));
        assertEquals(220, r.ms());
        assertEquals(0, r.stepBackIfCalm(100 * S)); // not calm long enough (2 min after the last late sound)
        assertEquals(50, r.stepBackIfCalm(131 * S));
        assertEquals(170, r.ms());
        assertEquals(0, r.stepBackIfCalm(200 * S)); // the next step needs its own calm spell…
        assertEquals(50, r.stepBackIfCalm(251 * S));
        assertEquals(120, r.ms());
        assertEquals(0, r.stepBackIfCalm(10_000 * S)); // …and never below the start
    }

    @Test
    void neverAboveTheMost() {
        SoundReserve r = new SoundReserve(120, 0);
        for (int i = 0; i < 10; i++) r.late(i * S);
        assertEquals(SoundReserve.MAX_MS, r.ms());
        assertFalse(r.late(20 * S));
    }

    @Test
    void aStepBackThatWasTooEagerWaitsLongerNextTime() {
        SoundReserve r = new SoundReserve(120, 0);
        r.late(0);
        r.late(0);
        assertEquals(50, r.stepBackIfCalm(120 * S)); // 220 → 170
        r.late(130 * S); // late again soon after: back to 220, and the next calm spell is 4 min
        assertEquals(220, r.ms());
        assertEquals(0, r.stepBackIfCalm(130 * S + 239 * S));
        assertEquals(50, r.stepBackIfCalm(130 * S + 240 * S));
    }

    @Test
    void lateLongAfterAStepBackDoesNotMakeItWaitLonger() {
        SoundReserve r = new SoundReserve(120, 0);
        r.late(0);
        assertEquals(50, r.stepBackIfCalm(120 * S)); // 170 → 120
        r.late(120 * S + 600 * S); // 10 min later: just a new hiccup
        assertEquals(50, r.stepBackIfCalm(720 * S + 120 * S)); // the usual 2 min
    }
}
