package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassTimerConvertTest {
    @Test
    void ticksToMsConvertsUsingPeriod() {
        // (2000 - 1000) ticks * 0.5 ns/tick = 500 ns = 5.0e-4 ms.
        assertEquals(5.0e-4, PassTimer.ticksToMs(1000, 2000, 0.5f), 1e-12);
    }

    @Test
    void ticksToMsStillComputesWithZeroPeriod() {
        // Guarded by isEnabled() at call sites, not here -- this is a pure conversion seam.
        assertEquals(0.0, PassTimer.ticksToMs(1000, 2000, 0f), 1e-12);
    }
}
