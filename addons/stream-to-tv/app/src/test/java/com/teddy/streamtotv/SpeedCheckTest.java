package com.teddy.streamtotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeedCheckTest {
    // A 4.2 s segment of a 1.8 Mbit/s stream.
    private static final long SEGMENT = 945_000, PLAY = 4_200;

    @Test
    public void downloadingSlowerThanPlayingIsSlow() {
        SpeedCheck c = new SpeedCheck();
        for (int i = 0; i < SpeedCheck.WINDOW - 1; i++) assertFalse(c.add(SEGMENT, 4_700, PLAY));
        assertTrue(c.add(SEGMENT, 4_700, PLAY));
        assertTrue(c.slow());
        assertEquals(1.6, c.downloadMbps(), 0.05);
        assertEquals(1.8, c.streamMbps(), 0.05);
    }

    @Test
    public void oneSlowSegmentIsNotAVerdict() {
        SpeedCheck c = new SpeedCheck();
        for (int i = 0; i < SpeedCheck.WINDOW - 1; i++) c.add(SEGMENT, 900, PLAY);
        c.add(SEGMENT, 12_000, PLAY);
        assertFalse(c.slow());
    }

    @Test
    public void itRecoversOnlyWellBelowTheLine() {
        SpeedCheck c = new SpeedCheck();
        for (int i = 0; i < SpeedCheck.WINDOW; i++) c.add(SEGMENT, 4_700, PLAY);
        for (int i = 0; i < SpeedCheck.WINDOW; i++) c.add(SEGMENT, 4_000, PLAY);
        assertTrue(c.slow());
        boolean changed = false;
        for (int i = 0; i < SpeedCheck.WINDOW; i++) changed |= c.add(SEGMENT, 1_000, PLAY);
        assertTrue(changed);
        assertFalse(c.slow());
    }

    @Test
    public void resetStartsOver() {
        SpeedCheck c = new SpeedCheck();
        for (int i = 0; i < SpeedCheck.WINDOW; i++) c.add(SEGMENT, 9_000, PLAY);
        c.reset();
        assertFalse(c.slow());
        assertEquals(0, c.downloadMbps(), 0);
        assertFalse(c.add(SEGMENT, 0, 0));
    }
}
