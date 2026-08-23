package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces, offline, the reported bug: swimming under terrain (a shore, a hill, a monument wall)
 * made the caustic web visibly slide down from the top over about a third of a second, "as if the
 * light had changed, but it hasn't". Root cause was {@code u_WaterState.z} lerping toward the
 * underside of the roof because the topmost-water scan has no water left above it there — see the
 * class doc on {@link WaterSurfaceTracker}.
 */
class WaterSurfaceTrackerTest {

    /** Runs {@code frames} submerged updates at a fixed reading and returns the final altitude. */
    private static float runSubmerged(WaterSurfaceTracker t, float raw, boolean openToSky, int frames) {
        float last = 0.0f;
        for (int i = 0; i < frames; i++) {
            last = t.updateSubmerged(raw, openToSky);
        }
        return last;
    }

    @Test
    void staysAtSeaLevelWhenSwimmingUnderARoof() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();

        // Converge on the real surface in open water first.
        float atSurface = runSubmerged(t, 63.0f, true, 60);
        assertEquals(63.0f, atSurface, 0.001f);

        // Swim under an overhang: the column scan now collapses to the ceiling's altitude, with
        // no water above it to be open to sky.
        float underRoof = runSubmerged(t, 53.0f, false, 30);

        // The old filter would have eased ~90% of the way toward 53 over 30 frames -- roughly 55.
        // The fix holds the last open-sky reading exactly.
        assertEquals(63.0f, underRoof, 0.001f, "held altitude must not chase the roof");
    }

    @Test
    void swimmingBackOutIsSilent() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();
        runSubmerged(t, 63.0f, true, 60);
        runSubmerged(t, 53.0f, false, 30);

        // Back into the open: resuming the SAME open-sky reading should not move at all -- there is
        // no discontinuity to smooth away, because the held value already equals it.
        float back = runSubmerged(t, 63.0f, true, 5);
        assertEquals(63.0f, back, 0.001f, "re-emerging at the same altitude must not pop or drift");
    }

    @Test
    void aRealSurfaceStillTracksAndConverges() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();
        runSubmerged(t, 63.0f, true, 60);

        // The tide (or a real change in the open-sky surface) actually moves -- the filter must
        // still follow it, just eased rather than snapped.
        float mid = t.updateSubmerged(65.0f, true);
        assertTrue(mid > 63.0f && mid < 65.0f, "one frame after a real change should be mid-ease, got " + mid);

        float converged = runSubmerged(t, 65.0f, true, 120);
        assertEquals(65.0f, converged, 0.01f);
    }

    @Test
    void firstDiveUnderARoofHasNoBaselineToHoldSoItTakesTheRawReading() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();

        float first = t.updateSubmerged(53.0f, false);
        assertEquals(53.0f, first, 0.001f, "with nothing better known yet, the raw reading is used");
    }

    @Test
    void worldChangeDropsTheBaselineSoTheNextDiveSnaps() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();
        runSubmerged(t, 63.0f, true, 60);

        t.reset();

        // A fresh dive in a different world/dimension must not ease from the old world's altitude.
        float afterReset = t.updateSubmerged(40.0f, true);
        assertEquals(40.0f, afterReset, 0.001f);
    }

    @Test
    void dryArmStillEasesFromItsBaseline() {
        WaterSurfaceTracker t = new WaterSurfaceTracker();

        float first = t.updateDry(true, 63.0f);
        assertEquals(63.0f, first, 0.001f, "first reading with no baseline snaps");

        float mid = t.updateDry(true, 70.0f);
        assertTrue(mid > 63.0f && mid < 70.0f, "subsequent readings ease toward the new value, got " + mid);

        float noWater = t.updateDry(false, 0.0f);
        assertEquals(0.0f, noWater, 0.001f, "no water found reports 0.0 and drops the baseline");

        float resnapped = t.updateDry(true, 80.0f);
        assertEquals(80.0f, resnapped, 0.001f, "baseline was dropped, so this snaps rather than easing");
    }
}
