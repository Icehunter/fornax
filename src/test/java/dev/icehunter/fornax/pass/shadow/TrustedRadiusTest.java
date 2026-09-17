package dev.icehunter.fornax.pass.shadow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The radius a pack compares a receiver against before trusting the celestial RT image.
 *
 * <p>More than one tier fills that image in a frame and they do not reach equally far, so the
 * published radius has to be the widest any of them answered at. Taking the last writer instead
 * would let the voxel tier's wide answer be narrowed by a mesh tier that ran after it, or the mesh
 * tier's answer be widened past where it actually traced, and either way a pack reads RT where
 * there is none and gets whatever the image's A channel happened to hold.
 */
class TrustedRadiusTest {

    @AfterEach
    void reset() {
        ShadowFrameState.setRtDistance(0);
    }

    @Test
    void theRadiusIsSquaredBecauseThatIsWhatAReceiverComparesAgainst() {
        ShadowFrameState.setRtDistance(32f);
        assertEquals(1024f, ShadowFrameState.rtDistanceSquared(), 1e-3f);
    }

    @Test
    void aWiderTierWidensTheRadiusAndANarrowerOneLeavesItAlone() {
        ShadowFrameState.setRtDistance(0);
        ShadowFrameState.raiseRtDistance(32f);
        assertEquals(1024f, ShadowFrameState.rtDistanceSquared(), 1e-3f);

        // The voxel tier reaches past the mesh tier's cylinder, so it widens what a pack may trust.
        ShadowFrameState.raiseRtDistance(135f);
        assertEquals(135f * 135f, ShadowFrameState.rtDistanceSquared(), 1e-3f);

        // And a tier that reached less far must not narrow it back, whatever order they ran in.
        ShadowFrameState.raiseRtDistance(32f);
        assertEquals(135f * 135f, ShadowFrameState.rtDistanceSquared(), 1e-3f);
    }

    /** The per-frame clear. Without it a frame where no tier answers keeps the last one's reach. */
    @Test
    void clearingTheTrustedRadiusZeroesIt() {
        ShadowFrameState.raiseRtDistance(64f);
        TerrainShadowResult.invalidateTrustedRadius();
        assertEquals(0f, ShadowFrameState.rtDistanceSquared(), 0f);
    }
}
