package dev.windowscraft.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CopySizeTest {
    private static final long S = 1_000_000_000L;

    @Test
    void fullSizeUntilItLooksSmallForAMoment() {
        CopySize c = new CopySize();
        assertEquals(0, c.update(2160, 1e9, false, 0)); // (nothing measured yet)
        assertEquals(0, c.update(2160, 500, false, 1 * S)); // small enough for 1080 (2 × 500 ≤ 1080), not yet for long
        assertEquals(0, c.update(2160, 500, false, 2 * S));
        assertEquals(1, c.update(2160, 500, false, 3 * S + 1)); // 2 s later: half
        assertEquals(1, c.update(2160, 500, false, 4 * S));
    }

    @Test
    void sharperStraightAwayWhenYouComeCloser() {
        CopySize c = new CopySize();
        c.update(2160, 200, false, 0);
        assertEquals(2, c.update(2160, 200, false, 3 * S)); // 540 ≥ 2 × 200, 270 isn't
        assertEquals(2, c.update(2160, 380, false, 4 * S)); // 540 ≥ 1.4 × 380: still fine
        assertEquals(1, c.update(2160, 400, false, 4 * S + 1)); // 540 < 1.4 × 400: up at once (1080 ≥ 2 × 400)
        assertEquals(0, c.update(2160, 900, false, 5 * S)); // close: full size
    }

    @Test
    void neverSmallerThanTwiceWhatYouSee() {
        CopySize c = new CopySize();
        c.update(1080, 100, false, 0);
        assertEquals(2, c.update(1080, 100, false, 3 * S)); // 270 ≥ 200, 135 isn't
        CopySize far = new CopySize();
        far.update(2160, 10, false, 0);
        assertEquals(CopySize.MAX, far.update(2160, 10, false, 3 * S)); // (at most 3 halvings)
    }

    @Test
    void fullWhenItMustBe() {
        CopySize c = new CopySize();
        c.update(2160, 200, false, 0);
        assertEquals(2, c.update(2160, 200, false, 3 * S));
        assertEquals(0, c.update(2160, 200, true, 4 * S)); // you sat down / the setting is off
        assertEquals(0, c.update(2160, 200, false, 5 * S)); // and smaller again only after a moment
        assertEquals(2, c.update(2160, 200, false, 7 * S + 1));
        assertEquals(0, c.update(0, 200, false, 8 * S)); // the size isn't known
    }

    @Test
    void aWaverAroundTheLineDoesNotFlip() {
        CopySize c = new CopySize();
        c.update(2160, 540, false, 0);
        assertEquals(1, c.update(2160, 540, false, 3 * S));
        for (int i = 0; i < 20; i++) assertEquals(1, c.update(2160, i % 2 == 0 ? 560 : 520, false, (4 + i) * S));
    }
}
