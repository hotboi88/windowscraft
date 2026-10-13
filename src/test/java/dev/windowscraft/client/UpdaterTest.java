package dev.windowscraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UpdaterTest {
    @Test
    void comparesVersions() {
        assertTrue(Updater.newer("0.14.0+mc1.21.1", "0.13.3+mc1.21.1"));
        assertTrue(Updater.newer("0.13.10", "0.13.9"));
        assertTrue(Updater.newer("1.0", "0.99.99"));
        assertFalse(Updater.newer("0.13.3+mc1.21.1", "0.13.3+mc1.21.1"));
        assertFalse(Updater.newer("0.13.2", "0.13.3"));
        assertFalse(Updater.newer("0.13", "0.13.0"));
    }

    @Test
    void quotesPathsForPowerShell() {
        assertEquals("'" + Path.of("/tmp/it's here").toAbsolutePath() + "'".replace("'", "''").substring(0, 0) + "'", Updater.q(Path.of("/tmp/it's here")).replace("''", "'"));
        assertTrue(Updater.q(Path.of("/tmp/it's")).contains("it''s"));
    }
}
