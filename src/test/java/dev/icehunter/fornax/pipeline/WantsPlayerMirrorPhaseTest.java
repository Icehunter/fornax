package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DeferredGeometryPipelines#wantsPlayerMirrorPhase}: all four inputs must hold, and
 * any one missing refuses the phase. Checked directly here rather than only observed during a
 * frame, the same discipline {@code wantsDeferredParticleGroup} and {@code
 * wantsForwardParticleGroup} follow. Also covers the related falling-edge check, {@link
 * DeferredGeometryPipelines#shouldClearMirrorOnFallingEdge}.
 */
class WantsPlayerMirrorPhaseTest {

    @Test
    void allFourConditionsMustHold() {
        assertTrue(DeferredGeometryPipelines.wantsPlayerMirrorPhase(true, true, true, true));
    }

    @Test
    void noPackActiveRefuses() {
        assertFalse(DeferredGeometryPipelines.wantsPlayerMirrorPhase(false, true, true, true));
    }

    @Test
    void slotNotClaimedRefuses() {
        assertFalse(DeferredGeometryPipelines.wantsPlayerMirrorPhase(true, false, true, true));
    }

    @Test
    void notConsumedByAnyPassRefuses() {
        assertFalse(DeferredGeometryPipelines.wantsPlayerMirrorPhase(true, true, false, true));
    }

    @Test
    void invalidProbeRefuses() {
        assertFalse(DeferredGeometryPipelines.wantsPlayerMirrorPhase(true, true, true, false));
    }

    @Test
    void fallingEdgeClearsExactlyOnTheTransition() {
        assertTrue(DeferredGeometryPipelines.shouldClearMirrorOnFallingEdge(false, true),
                "gate just turned off after drawing last frame: must clear");
        assertFalse(DeferredGeometryPipelines.shouldClearMirrorOnFallingEdge(false, false),
                "gate has been off for a while: already cleared, must not clear again");
        assertFalse(DeferredGeometryPipelines.shouldClearMirrorOnFallingEdge(true, false),
                "gate just turned on: the caster itself clears, not this");
        assertFalse(DeferredGeometryPipelines.shouldClearMirrorOnFallingEdge(true, true),
                "gate stayed on: the caster clears every frame it draws");
    }
}
