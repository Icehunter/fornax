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
        // commitSky is called down both branches of the sky mixin, so the clouds reset happens
        // every frame regardless of whether the pack owns the sky.
        SkyFrameState.commitClouds(true);
        SkyFrameState.commitSky(false);
        assertEquals(0.0f, SkyFrameState.cloudsFlag(), 0.0f);
    }

    // --- The sky's DATA lanes do not live here -----------------------------------------------
    // Sky colour, sunrise colour, star brightness, sun direction, moon phase, rain level and sun
    // angle are not committed onto this class: SkyProbe reads them live from the camera's
    // environment attribute probe every frame in every dimension, independent of whether the pack's
    // SKY_PROCEDURAL option cancels vanilla's sky (a mixin committing onto this class only down
    // that cancelling branch would read zero for every other pack). Its pure conversions (ARGB
    // unpacking, the (-sin a, cos a, 0) sun-direction convention) keep their unit coverage in
    // SkyProbeTest; the probe read itself dereferences Minecraft.getInstance() and has no headless
    // harness, same as the two live lanes below.

    // --- u_WaterState.x (Water Round C Task 4) ------------------------------------------------
    // commitEyeInWater/eyeInWater are not carried on SkyFrameState:
    // GlobalUniformsWriteMixin computes the flag live every frame from
    // Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera() (see that mixin's
    // water-tail comment), so there is no SkyFrameState carrier to unit-test here. That mixin reads
    // a static Minecraft.getInstance() singleton unavailable outside a running game instance --
    // like every other mixin in this codebase, it has no unit-test harness; its behavior is proven
    // by manual live verification (submerged Overworld -> Nether portal transit, flag drops to 0
    // immediately), not by a JUnit test.

    // --- u_SkyState.w wind clock ---------------------------------------------------------------
    // windClockVal/windClock() are not carried on SkyFrameState: the clock was only ever set from
    // LevelRendererCloudsPassMixin's addCloudsPass HEAD injection, which vanilla skips whenever
    // CloudStatus.OFF or the cloud color's alpha is 0 -- freezing wave animation with clouds off.
    // GlobalUniformsWriteMixin computes it live every frame from
    // Minecraft.getInstance().level.getGameTime() plus the render partial-tick (see that mixin's
    // own doc comment), independent of whether the clouds pass runs at all. Same "no
    // static-singleton unit-test harness" situation as the eye-in-water flag -- no JUnit coverage
    // for the live computation itself, proven live instead (clock keeps advancing with Clouds set
    // to Off in the pack's video settings).
}
