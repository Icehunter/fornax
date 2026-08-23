package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FrameGenPacer#computeEngaged}'s hysteresis math directly (no live {@code Minecraft}/
 * GLFW window needed -- see that method's own header for why it is split out from {@link
 * FrameGenPacer#update}).
 *
 * <p><b>Every "engaged" case below stays under the ~0.5x displayHz ceiling</b> (see {@link
 * FrameGenPacer}'s class header, "Both thresholds MUST sit strictly below 0.5"): a code-review
 * catch found the FIRST version of this pacer used a straddling 0.45/0.55 band, and while ENGAGED
 * the measured render fps can never exceed roughly {@code 0.5 * displayHz} (double-present under
 * FIFO pins the loop there), so a disengage threshold at or above 0.5 was mathematically
 * unreachable -- one transient dip latched the engaged state for the rest of the session. Every
 * {@code currentlyEngaged=true} fixture here therefore uses an fps at or below {@code 0.5 *
 * DISPLAY_HZ} (60fps at 120Hz) -- anything above that is an fps this pacer can never actually
 * observe while engaged, and asserting behavior against it (as an earlier revision of this test
 * did, at 70fps) would pin a scenario live code cannot reach.
 *
 * <p><b>Not covered here</b>: {@link FrameGenPacer#displayRefreshHz()}'s {@code hz <= 0} -> 60Hz
 * fallback path (and its once-only log) is untested -- it requires a live {@code
 * Minecraft.getInstance().getWindow()}, which this plain JUnit suite has no fixture for and does
 * not attempt to mock. That path is exercised only by manual/live verification (see the
 * ship-quality-round report), not by this test class.
 */
class FrameGenPacerTest {
    private static final int DISPLAY_HZ = 120;
    // 0.40 * 120 = 48fps; 0.48 * 120 = 57.6fps.
    private static final double ENGAGE_THRESHOLD_FPS = FrameGenPacer.ENGAGE_FRACTION * DISPLAY_HZ;
    private static final double DISENGAGE_THRESHOLD_FPS = FrameGenPacer.DISENGAGE_FRACTION * DISPLAY_HZ;

    @Test
    void engagesWhenRenderFpsDropsBelowEngageThreshold() {
        // 40fps is below the 48fps engage threshold.
        assertTrue(FrameGenPacer.computeEngaged(false, 40.0, DISPLAY_HZ));
    }

    @Test
    void staysDisengagedAboveEngageThreshold() {
        // 90fps (the live-measured repro case, 0.75x@120Hz) is well above the 48fps threshold.
        assertFalse(FrameGenPacer.computeEngaged(false, 90.0, DISPLAY_HZ));
    }

    @Test
    void staysEngagedJustBelowDisengageThreshold() {
        // 57fps is just under the 57.6fps disengage threshold and still under the ~60fps
        // (0.5x) ceiling engaged render fps can actually reach -- a live, reachable case.
        assertTrue(FrameGenPacer.computeEngaged(true, 57.0, DISPLAY_HZ));
    }

    @Test
    void disengagesAtTheEngagedStateCeiling() {
        // Reviewer-named regression case: 60fps at 120Hz is exactly 0.5x displayHz -- the ceiling
        // render fps asymptotically approaches while ENGAGED (double-present under FIFO pins the
        // loop there). This is the value the pacer must actually be able to ESCAPE engagement at,
        // since it is close to the highest fps it will ever observe in that state. 60 > 57.6
        // (DISENGAGE_FRACTION * 120), so this must disengage. Under the OLD 0.45/0.55 band this
        // assertion would have failed (60 < 66), which was exactly the latch bug.
        assertFalse(FrameGenPacer.computeEngaged(true, 60.0, DISPLAY_HZ));
    }

    @Test
    void holdsEngagedStateInsideHysteresisBand() {
        // 52fps sits inside (48, 57.6) and under the ~60fps engaged-state ceiling -- a live,
        // reachable point where hysteresis, not the nearer threshold, must decide the outcome.
        double fpsInBand = 52.0;
        assertTrue(FrameGenPacer.computeEngaged(true, fpsInBand, DISPLAY_HZ),
                "engaged state must hold inside the band");
        assertFalse(FrameGenPacer.computeEngaged(false, fpsInBand, DISPLAY_HZ),
                "disengaged state must hold inside the band");
    }

    @Test
    void engageThresholdBoundaryDoesNotEngage() {
        // Strict inequality: exactly AT the engage threshold (48.0fps) must not itself engage.
        assertFalse(FrameGenPacer.computeEngaged(false, ENGAGE_THRESHOLD_FPS, DISPLAY_HZ));
    }

    @Test
    void disengageThresholdBoundaryDoesNotDisengage() {
        // Strict inequality: exactly AT the disengage threshold (57.6fps) must not itself
        // disengage -- and 57.6fps is still under the ~60fps engaged-state ceiling, so this is a
        // point live code can actually land on.
        assertTrue(FrameGenPacer.computeEngaged(true, DISENGAGE_THRESHOLD_FPS, DISPLAY_HZ));
    }

    @Test
    void engageThresholdIsBelowDisengageThreshold() {
        // The hysteresis band only absorbs jitter if engage < disengage; a misconfiguration here
        // (e.g. someone "simplifying" both to one constant) would silently reintroduce flapping.
        assertTrue(FrameGenPacer.ENGAGE_FRACTION < FrameGenPacer.DISENGAGE_FRACTION);
    }

    @Test
    void bothThresholdsStaySafelyBelowTheEngagedStateCeiling() {
        // The load-bearing invariant this whole band redesign exists to satisfy (see class
        // header): DISENGAGE_FRACTION must be strictly below 0.5, or the disengage branch is
        // permanently unreachable once engaged (the original 0.45/0.55 bug this fixes).
        assertTrue(FrameGenPacer.DISENGAGE_FRACTION < 0.5,
                "DISENGAGE_FRACTION >= 0.5 would be unreachable from the engaged state under FIFO");
        assertTrue(FrameGenPacer.ENGAGE_FRACTION < 0.5);
    }
}
