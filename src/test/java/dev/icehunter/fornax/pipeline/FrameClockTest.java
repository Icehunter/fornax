package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameClockTest {
    private static final long MS = 1_000_000L;

    @Test
    void notReadyUntilTwoMarks() {
        FrameClock clock = new FrameClock();
        assertFalse(clock.ready());
        clock.markFrame(0);
        assertFalse(clock.ready());
        clock.markFrame(16 * MS);
        assertTrue(clock.ready());
    }

    @Test
    void steadyIntervalsConvergeToThatInterval() {
        FrameClock clock = new FrameClock();
        for (int i = 0; i <= 60; i++) {
            clock.markFrame(i * 16 * MS);
        }
        assertEquals(16 * MS, clock.emaIntervalNanos(), 16 * MS * 0.01);
        assertEquals(0.016f, clock.deltaTimeSeconds(), 0.001f);
    }

    @Test
    void outlierIntervalsAreClampedNotAbsorbed() {
        FrameClock clock = new FrameClock();
        clock.markFrame(0);
        clock.markFrame(16 * MS);
        // 2-second alt-tab hitch must not poison the EMA
        clock.markFrame(16 * MS + 2_000 * MS);
        assertTrue(clock.emaIntervalNanos() < 50 * MS,
                "EMA was " + clock.emaIntervalNanos());
    }

    @Test
    void resetClearsReadiness() {
        FrameClock clock = new FrameClock();
        clock.markFrame(0);
        clock.markFrame(16 * MS);
        clock.reset();
        assertFalse(clock.ready());
        assertEquals(0, clock.emaIntervalNanos());
    }
}
