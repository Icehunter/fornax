package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterTransitionTrackerTest {

    @Test
    void enteringWaterStartsANegativeTransitionEnvelope() {
        WaterTransitionTracker tracker = new WaterTransitionTracker();
        Object level = new Object();

        assertEquals(0.0f, tracker.update(level, false, 10.0), 1e-6f);
        assertEquals(-1.0f, tracker.update(level, true, 10.1), 1e-6f);
    }

    @Test
    void entryAndExitBothFinishWithinOneSecond() {
        WaterTransitionTracker tracker = new WaterTransitionTracker();
        Object level = new Object();

        tracker.update(level, false, 20.0);
        tracker.update(level, true, 20.1);
        assertEquals(-0.5f, tracker.update(level, true, 20.6), 1e-6f);
        assertEquals(0.0f, tracker.update(level, true, 21.1), 1e-6f);

        assertEquals(1.0f, tracker.update(level, false, 22.0), 1e-6f);
        assertEquals(0.5f, tracker.update(level, false, 22.5), 1e-6f);
        assertEquals(0.0f, tracker.update(level, false, 23.0), 1e-6f);
    }

    @Test
    void changingWorldClearsAnInFlightTransition() {
        WaterTransitionTracker tracker = new WaterTransitionTracker();
        Object first = new Object();
        Object second = new Object();

        tracker.update(first, false, 30.0);
        tracker.update(first, true, 30.1);
        assertEquals(0.0f, tracker.update(second, false, 30.2), 1e-6f);
        assertEquals(0.0f, tracker.update(second, false, 30.3), 1e-6f);
    }
}
