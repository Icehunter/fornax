package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkyFrameStateTest {

    @Test
    void skyboxFlagTracksCancellationOnly() {
        SkyFrameState.commitSky(true);
        assertEquals(1.0f, SkyFrameState.skyboxFlag(), 0.0f);
        SkyFrameState.commitSky(false);
        assertEquals(0.0f, SkyFrameState.skyboxFlag(), 0.0f);
    }

    @Test
    void commitCloudsSetsFlag() {
        SkyFrameState.commitClouds(true);
        assertEquals(1.0f, SkyFrameState.cloudsFlag(), 0.0f);
    }

    @Test
    void commitCloudsNotCancelledLeavesFlagZero() {
        SkyFrameState.commitClouds(false);
        assertEquals(0.0f, SkyFrameState.cloudsFlag(), 0.0f);
    }

    @Test
    void commitSkyResetsCloudsLaneEachFrame() {
        SkyFrameState.commitClouds(true);
        // The sky mixin runs FIRST each frame -- its commitSky() must reset the clouds lane before
        // the clouds mixin runs later in the same frame and sets it again (or leaves it at the
        // reset 0 when vanilla clouds draw instead).
        SkyFrameState.commitSky(true);
        assertEquals(0.0f, SkyFrameState.cloudsFlag(), 0.0f);
    }

    @Test
    void commitSkyResetsCloudsLaneEvenWhenNotCancelling() {
        // commitSky is now called down BOTH branches of the sky mixin, so the clouds reset happens
        // every frame regardless of whether the pack owns the sky. The old shape had a separate
        // commitInactive() for the non-cancelling branch and had to reset the lane in both.
        SkyFrameState.commitClouds(true);
        SkyFrameState.commitSky(false);
        assertEquals(0.0f, SkyFrameState.cloudsFlag(), 0.0f);
    }

    // --- The sky's DATA lanes no longer live here -----------------------------------------------
    // Sky colour, sunrise colour, star brightness, sun direction, moon phase, rain level and sun
    // angle used to be committed onto this class by LevelRendererSkyPassMixin, and were covered
    // here by argbDecodesToUnitRangeFloats, sunDirectionMatchesAngleConvention and
    // commitInactiveZeroFillsEverything. They moved out for the same reason the eye-in-water flag
    // and the wind clock did below, only worse: that commit ran ONLY down the branch that cancels
    // vanilla's sky, which requires the pack's SKY_PROCEDURAL option, so every other pack read
    // zeroes for all of it. SkyProbe now reads them live from the camera's environment attribute
    // probe every frame in every dimension. Its pure conversions (ARGB unpacking, the
    // (-sin a, cos a, 0) sun-direction convention) keep their unit coverage in SkyProbeTest; the
    // probe read itself dereferences Minecraft.getInstance() and has no headless harness, same as
    // the two live lanes below.

    // --- u_WaterState.x (Water Round C Task 4) ------------------------------------------------
    // commitEyeInWater/eyeInWater used to live on SkyFrameState and were covered by two tests
    // here (commitEyeInWaterTracksFlag, commitEyeInWaterSurvivesSkyCommitAndInactive). Fix-wave
    // finding 1 moved the flag's source out of this class entirely: GlobalUniformsWriteMixin now
    // computes it live every frame from Minecraft.getInstance().gameRenderer.mainCamera()
    // .getFluidInCamera() (see that mixin's water-tail comment), so there is no SkyFrameState
    // carrier left to unit-test here. That mixin reads a static Minecraft.getInstance() singleton
    // unavailable outside a running game instance -- like every other mixin in this codebase, it
    // has no unit-test harness; its behavior is proven by the manual live-verification pass this
    // fix wave's report documents (submerged Overworld -> Nether portal transit, flag drops to 0
    // immediately), not by a JUnit test.

    // --- u_SkyState.w wind clock (2026-07-15 wind-clock-freeze fix) -------------------------
    // windClockVal/windClock() used to live on SkyFrameState, set via commitClouds's second
    // parameter, and were covered by windClock assertions folded into the tests above. The fix
    // moved the clock's source out of this class entirely for the same reason the eye-in-water
    // flag moved out above: it was ONLY ever set from LevelRendererCloudsPassMixin's addCloudsPass
    // HEAD injection, which vanilla skips whenever CloudStatus.OFF or the cloud color's alpha is 0
    // -- freezing wave animation with clouds off. GlobalUniformsWriteMixin now computes it live
    // every frame from Minecraft.getInstance().level.getGameTime() plus the render partial-tick
    // (see that mixin's own doc comment), independent of whether the clouds pass runs at all. Same
    // "no static-singleton unit-test harness" situation as the eye-in-water flag -- no JUnit
    // coverage for the live computation itself, proven live instead (clock keeps advancing with
    // Clouds set to Off in the pack's video settings).
}
