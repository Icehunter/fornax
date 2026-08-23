package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accumulator is pure and Minecraft-free precisely so these can run without a game or a GPU.
 *
 * <p>The property worth the most here is frame-rate independence: a wetness that converges faster on
 * a fast machine is a rendering difference caused by hardware, which is close to impossible to spot
 * by eye and trivial to assert.
 */
class WetnessAccumulatorTest {

    /** Steps {@code seconds} of simulated time at a fixed frame rate. */
    private static float run(float rainLevel, float seconds, int fps, float startWetness) {
        WetnessAccumulator a = new WetnessAccumulator();
        a.reset(startWetness);
        float dt = 1.0f / fps;
        for (int i = 0; i < (int) (seconds * fps); i++) {
            a.step(rainLevel, dt);
        }
        return a.wetness();
    }

    @Test
    void startsDry() {
        assertEquals(0.0f, new WetnessAccumulator().wetness());
    }

    @Test
    void risesTowardOneWhileRaining() {
        float after = run(1.0f, 30.0f, 60, 0.0f);
        assertTrue(after > 0.9f, "30s of rain should leave a surface nearly saturated, got " + after);
        assertTrue(after <= 1.0f, "must never exceed 1, got " + after);
    }

    @Test
    void decaysTowardZeroAfterRainStops() {
        float after = run(0.0f, 120.0f, 60, 1.0f);
        assertTrue(after < 0.15f, "2min of drying should leave it nearly dry, got " + after);
        assertTrue(after >= 0.0f, "must never fall below 0, got " + after);
    }

    @Test
    void dryingIsSlowerThanWetting() {
        // The asymmetry is the point of the class -- equal rates read as the world breathing.
        float wetGain = run(1.0f, 10.0f, 60, 0.5f) - 0.5f;
        float dryLoss = 0.5f - run(0.0f, 10.0f, 60, 0.5f);
        assertTrue(wetGain > dryLoss * 2.0f,
                "wetting must be markedly faster than drying; gained " + wetGain + " vs lost " + dryLoss);
    }

    @Test
    void isFrameRateIndependent() {
        // The reason step() uses 1 - exp(-dt/tau) rather than a naive per-frame lerp.
        float at30 = run(1.0f, 20.0f, 30, 0.0f);
        float at60 = run(1.0f, 20.0f, 60, 0.0f);
        float at240 = run(1.0f, 20.0f, 240, 0.0f);
        assertEquals(at30, at240, 0.005f, "30fps vs 240fps must agree: " + at30 + " vs " + at240);
        assertEquals(at60, at240, 0.005f, "60fps vs 240fps must agree: " + at60 + " vs " + at240);
    }

    @Test
    void isMonotonicWithinAPhase() {
        WetnessAccumulator a = new WetnessAccumulator();
        float previous = -1.0f;
        for (int i = 0; i < 600; i++) {
            a.step(1.0f, 1.0f / 60.0f);
            assertTrue(a.wetness() >= previous, "wetness dipped while raining at step " + i);
            previous = a.wetness();
        }
    }

    @Test
    void neverOvershootsItsTarget() {
        // Converging past the target and easing back would show as a flicker at the top of a storm.
        WetnessAccumulator a = new WetnessAccumulator();
        for (int i = 0; i < 5000; i++) {
            a.step(0.4f, 1.0f / 60.0f);
            assertTrue(a.wetness() <= 0.4f + 1e-4f, "overshot 0.4, reached " + a.wetness());
        }
        assertEquals(0.4f, a.wetness(), 0.01f, "should settle AT the target");
    }

    @Test
    void aStallDoesNotSnapWetnessAcrossTheGap() {
        // One enormous delta (alt-tab, breakpoint) must not teleport the world's weather.
        WetnessAccumulator stalled = new WetnessAccumulator();
        stalled.step(1.0f, 300.0f);
        assertTrue(stalled.wetness() < 0.10f,
                "a 5-minute frame must be clamped, not integrated whole; got " + stalled.wetness());
    }

    @Test
    void ignoresNonPositiveAndNaNDeltas() {
        WetnessAccumulator a = new WetnessAccumulator();
        a.reset(0.5f);
        a.step(1.0f, 0.0f);
        a.step(1.0f, -1.0f);
        a.step(1.0f, Float.NaN);
        assertEquals(0.5f, a.wetness(), 0.0f, "no delta means no change, and NaN must never propagate");
    }

    @Test
    void clampsOutOfRangeRainLevels() {
        WetnessAccumulator a = new WetnessAccumulator();
        for (int i = 0; i < 3000; i++) {
            a.step(5.0f, 1.0f / 60.0f);
        }
        assertEquals(1.0f, a.wetness(), 1e-3f, "a rain level above 1 must still saturate at 1");
    }

    @Test
    void resetSkipsTheEasing() {
        WetnessAccumulator a = new WetnessAccumulator();
        a.reset(1.0f);
        assertEquals(1.0f, a.wetness(), 0.0f, "reset is for discontinuities -- dimension change, world load");
    }
}
