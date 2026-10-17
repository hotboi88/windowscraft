package dev.windowscraft.capture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackoffTest {
    private static final long S = 1_000_000_000L;

    @Test
    void nothingIsDueUntilItFails() {
        Backoff b = new Backoff(5, 300);
        assertFalse(b.due(0));
        assertFalse(b.due(10_000 * S));
    }

    @Test
    void waitsDoubleUpToTheMost() {
        Backoff b = new Backoff(5, 300);
        long t = 1_000 * S;
        long[] waits = new long[9];
        for (int i = 0; i < waits.length; i++) {
            waits[i] = b.failed(t);
            assertFalse(b.due(t + waits[i] * S - 1), "not before its time");
            assertTrue(b.due(t + waits[i] * S), "due once its time has come");
            t += waits[i] * S;
        }
        assertArrayEquals(new long[]{5, 10, 20, 40, 80, 160, 300, 300, 300}, waits);
    }

    @Test
    void aGoodRunStartsTheWaitsOver() {
        Backoff b = new Backoff(5, 300);
        b.failed(0);
        b.failed(0);
        b.reset();
        assertFalse(b.due(0));
        assertEquals(5, b.failed(0));
    }

    @Test
    void manyFailuresNeverOverflow() {
        Backoff b = new Backoff(5, 300);
        for (int i = 0; i < 200; i++) assertTrue(b.failed(0) <= 300);
    }
}
