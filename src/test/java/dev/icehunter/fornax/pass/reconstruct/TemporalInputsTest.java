package dev.icehunter.fornax.pass.reconstruct;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.pass.reconstruct.TemporalInputs.Unavailable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "may this pass consume that data" contract in {@link TemporalInputs}.
 *
 * <p>Written for a regression that shipped three times in one family -- sky reprojection, particle
 * deferral, and shaders-off ghosting -- where a pass consumed a buffer nothing had written and no
 * test could see it, because the question was spelled inline as a null check against live GPU
 * handles. The case that matters here is {@link #allocatedButInactiveIsUnavailable()}: every handle
 * present, nothing writing them.
 */
class TemporalInputsTest {
    /**
     * THE regression. Turning the master shaders toggle off with a pack still selected leaves the
     * G-buffer and sceneHistory both allocated -- {@code GBufferManager} has no teardown path and
     * {@code ShadersEnabledFlip} does not unload the pack -- while {@code GraphRunner.prepare()}
     * early-returns and writes no motion vectors. An allocation-only guard passes here, and the
     * reconstruct blends 90% of a frozen frame over vanilla's own rendering.
     */
    @Test
    void allocatedButInactiveIsUnavailable() {
        assertEquals(Unavailable.GRAPH_INACTIVE,
                TemporalInputs.unavailable(false, true, true));
    }

    @Test
    void activeWithBothTargetsIsAvailable() {
        assertNull(TemporalInputs.unavailable(true, true, true));
    }

    @Test
    void missingGbufferIsUnavailable() {
        assertEquals(Unavailable.NO_GBUFFER, TemporalInputs.unavailable(true, false, true));
    }

    @Test
    void missingSceneHistoryIsUnavailable() {
        assertEquals(Unavailable.NO_SCENE_HISTORY, TemporalInputs.unavailable(true, true, false));
    }

    /**
     * Activity is reported ahead of allocation, not the other way round: with the graph inactive the
     * allocation state is meaningless (stale-but-present is the whole hazard), so a log line naming a
     * missing target would send a reader after the wrong thing.
     */
    @Test
    void inactivityIsReportedAheadOfMissingTargets() {
        assertEquals(Unavailable.GRAPH_INACTIVE, TemporalInputs.unavailable(false, false, false));
        assertEquals(Unavailable.GRAPH_INACTIVE, TemporalInputs.unavailable(false, false, true));
        assertEquals(Unavailable.GRAPH_INACTIVE, TemporalInputs.unavailable(false, true, false));
    }

    /**
     * The frame-level decision GameRendererMixin#fornax$ssaaBeginFrame makes, restated: with shaders
     * off, no method that consumes graph data may take the off-screen swap, whatever the targets look
     * like. OFF and SSAA are unaffected -- SSAA's box downsample reads only the colour target, so
     * vanilla-plus-supersampling stays available with no pack at all.
     */
    @Test
    void everyGraphConsumingMethodDeclinesTheSwapWithShadersOff() {
        // The other half of the mixin's composite guard. Asserted per-method rather than in a loop
        // over values() so adding a method to the enum fails this test instead of silently passing
        // an empty iteration -- a new temporal method that forgets needsGraphResources() would
        // reintroduce exactly this bug.
        assertTrue(AaMethod.TAA.needsGraphResources());
        assertTrue(AaMethod.TAAU.needsGraphResources());
        assertTrue(AaMethod.METALFX.needsGraphResources());
        assertNotNull(TemporalInputs.unavailable(false, true, true),
                "shaders off with both targets still allocated must decline the off-screen swap");

        // OFF and SSAA are unaffected: SSAA's box downsample reads only the colour target, so
        // vanilla-plus-supersampling stays available with no pack at all.
        assertFalse(AaMethod.OFF.needsGraphResources());
        assertFalse(AaMethod.SSAA.needsGraphResources());
        assertEquals(5, AaMethod.values().length, "a new aaMethod must be classified here too");
    }

    /** Every reason carries a non-empty phrase; the log line is built by concatenation. */
    @Test
    void everyReasonHasText() {
        for (Unavailable u : Unavailable.values()) {
            assertNotNull(u.reason(), u + " needs a reason");
            assertFalse(u.reason().isBlank(), u + " needs a reason");
        }
    }
}
