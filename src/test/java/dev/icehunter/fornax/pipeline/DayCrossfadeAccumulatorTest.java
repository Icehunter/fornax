package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure and Minecraft-free precisely so these can run without a game or a GPU. The property worth
 * the most here is that the crossfade actually completes (unlike an exponential ease, which only
 * ever approaches its target) and does so in real time regardless of how that time is sliced into
 * frames.
 */
class DayCrossfadeAccumulatorTest {

    private static float runAfterDayChange(float seconds, int fps) {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(5.0f, 0.0f); // settle on day 5, as the very first frame would
        a.step(6.0f, 0.0f); // the day changes
        float dt = 1.0f / fps;
        for (int i = 0; i < (int) (seconds * fps); i++) {
            a.step(6.0f, dt);
        }
        return a.progress();
    }

    @Test
    void firstEverFrameSettlesImmediately() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(3.0f, 1.0f / 60.0f);
        assertEquals(1.0f, a.progress(), 0.0f, "no prior day to fade from on the very first frame");
    }

    @Test
    void aDayChangeRestartsTheRampAtZero() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(3.0f, 5.0f);
        assertEquals(1.0f, a.progress(), 0.0f, "settled before the day changes");
        a.step(4.0f, 0.0f);
        assertEquals(0.0f, a.progress(), 0.0f, "the instant a new day arrives, progress restarts");
    }

    @Test
    void ramCompletesFully() {
        float after = runAfterDayChange(DayCrossfadeAccumulator.CROSSFADE_SECONDS, 60);
        assertEquals(1.0f, after, 1e-4f, "unlike an exponential ease, this must actually reach 1");
    }

    @Test
    void isMonotonicDuringTheRamp() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(1.0f, 0.0f);
        a.step(2.0f, 0.0f);
        float previous = -1.0f;
        for (int i = 0; i < 3000; i++) {
            a.step(2.0f, 1.0f / 60.0f);
            assertTrue(a.progress() >= previous, "progress dipped mid-ramp at step " + i);
            previous = a.progress();
        }
    }

    @Test
    void isFrameRateIndependent() {
        float half = DayCrossfadeAccumulator.CROSSFADE_SECONDS * 0.5f;
        float at30 = runAfterDayChange(half, 30);
        float at60 = runAfterDayChange(half, 60);
        float at240 = runAfterDayChange(half, 240);
        assertEquals(at30, at240, 0.01f, "30fps vs 240fps must agree: " + at30 + " vs " + at240);
        assertEquals(at60, at240, 0.01f, "60fps vs 240fps must agree: " + at60 + " vs " + at240);
    }

    @Test
    void neverOvershoots() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(1.0f, 0.0f);
        a.step(2.0f, 0.0f);
        for (int i = 0; i < 10000; i++) {
            a.step(2.0f, 1.0f / 60.0f);
            assertTrue(a.progress() <= 1.0f, "overshot 1, reached " + a.progress());
        }
    }

    @Test
    void continuesAcrossAPause() {
        // A paused world clock must not freeze this ramp: only sun/moon rotation stops, per the
        // engine's own invariant (see DayCrossfadeAccumulator's class doc).
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(1.0f, 0.0f);
        a.step(2.0f, 0.0f);
        a.step(2.0f, DayCrossfadeAccumulator.CROSSFADE_SECONDS * 0.5f);
        float mid = a.progress();
        assertTrue(mid > 0.0f && mid < 1.0f, "expected a partial ramp, got " + mid);
        a.step(2.0f, DayCrossfadeAccumulator.CROSSFADE_SECONDS * 0.5f);
        assertEquals(1.0f, a.progress(), 1e-4f, "real time keeps advancing the ramp");
    }

    @Test
    void ignoresNonPositiveAndNaNDeltas() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(1.0f, 0.0f);
        a.step(2.0f, 0.0f);
        a.step(2.0f, 0.0f);
        a.step(2.0f, -1.0f);
        a.step(2.0f, Float.NaN);
        assertEquals(0.0f, a.progress(), 0.0f, "no positive delta means no progress");
    }

    @Test
    void resetSkipsTheRamp() {
        DayCrossfadeAccumulator a = new DayCrossfadeAccumulator();
        a.step(1.0f, 0.0f);
        a.reset(7.0f);
        assertEquals(1.0f, a.progress(), 0.0f, "reset is for discontinuities: dimension change, world load");
    }
}
