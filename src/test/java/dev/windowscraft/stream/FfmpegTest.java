package dev.windowscraft.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FfmpegTest {
    @Test
    void readsTheBuildName() {
        assertEquals("n9.0.2-17-g2a571b6068", Ffmpeg.buildOf("ffmpeg version n9.0.2-17-g2a571b6068-20260930 Copyright (c) 2000-2026 the FFmpeg developers"));
        assertEquals("N-127259-gb91a82d6dd", Ffmpeg.buildOf("ffmpeg version N-127259-gb91a82d6dd-20261009 Copyright (c) 2000-2026 the FFmpeg developers"));
        assertEquals("6.1.1-3ubuntu5", Ffmpeg.buildOf("ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023 the FFmpeg developers"));
        assertNull(Ffmpeg.buildOf("'ffmpeg' is not recognized"));
        assertNull(Ffmpeg.buildOf(null));
    }
}
