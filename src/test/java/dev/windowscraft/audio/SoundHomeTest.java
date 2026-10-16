package dev.windowscraft.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A program on an in-game monitor is heard from one wall only, and never from your desk. */
class SoundHomeTest {
    @Test
    void usingItOnYourDeskLeavesItsSoundOnTheWall() {
        SoundHome.seen("test-desk-A", Set.of(9001L));
        SoundHome.usedOn(9001L, "test-desk-A");
        SoundHome.usedOn(9001L, null); // clicked on your desk
        assertTrue(SoundHome.playsHere("test-desk-A", 9001L));
        SoundHome.gone("test-desk-A");
    }

    @Test
    void onTwoMonitorsItPlaysWhereItWasLastUsedOnOne() {
        SoundHome.seen("test-two-A", Set.of(9002L));
        SoundHome.seen("test-two-B", Set.of(9002L));
        assertTrue(SoundHome.playsHere("test-two-A", 9002L)); // not used yet: the first by name
        assertFalse(SoundHome.playsHere("test-two-B", 9002L));
        SoundHome.usedOn(9002L, "test-two-B");
        SoundHome.usedOn(9002L, null); // your desk doesn't take it back
        assertFalse(SoundHome.playsHere("test-two-A", 9002L));
        assertTrue(SoundHome.playsHere("test-two-B", 9002L));
        SoundHome.gone("test-two-B");
        assertTrue(SoundHome.playsHere("test-two-A", 9002L)); // only one left
        SoundHome.gone("test-two-A");
    }
}
