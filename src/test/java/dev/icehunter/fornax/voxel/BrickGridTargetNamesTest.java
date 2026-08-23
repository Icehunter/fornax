package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tier 0 MUST keep the pre-cascade target names byte-identical: a pack's graph.toml, every
 * voxel-reading shader, and GraphInputResolver all bind those exact strings. A rename would break
 * every one of them at once, so this is pinned rather than left to review.
 */
class BrickGridTargetNamesTest {
    @Test
    void tierZeroKeepsTodaysExactNames() {
        // All seven engine-injected brick-grid targets, not a sample of three -- a pack's graph.toml
        // binds voxelPayload/voxelPalette/voxelFaceSeal just as literally as the three originally
        // pinned here, and voxelBrickIndex is equally load-bearing engine-internally.
        assertEquals("voxelBrickIndex",
                BrickGridUpload.targetName(BrickGridUpload.INDEX_GRID_TARGET, 0));
        assertEquals("voxelOccupancy", BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, 0));
        assertEquals("voxelPayload", BrickGridUpload.targetName(BrickGridUpload.PAYLOAD_TARGET, 0));
        assertEquals("voxelFaceSeal", BrickGridUpload.targetName(BrickGridUpload.FACE_SEAL_TARGET, 0));
        assertEquals("voxelPalette", BrickGridUpload.targetName(BrickGridUpload.PALETTE_TARGET, 0));
        assertEquals("voxelLightVolume",
                BrickGridUpload.targetName(BrickGridUpload.LIGHT_VOLUME_TARGET, 0));
        assertEquals("voxelBrickSummary",
                BrickGridUpload.targetName(BrickGridUpload.BRICK_SUMMARY_TARGET, 0));
    }

    @Test
    void coarseTiersGetASuffix() {
        assertEquals("voxelOccupancy_t1", BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, 1));
        assertEquals("voxelOccupancy_t3", BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, 3));
    }

    @Test
    void everyTierPairIsDistinct() {
        // A collision would make two tiers share one buffer and silently corrupt both.
        for (int a = 0; a < VoxelCascade.MAX_TIERS; a++) {
            for (int b = a + 1; b < VoxelCascade.MAX_TIERS; b++) {
                assertNotEquals(BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, a),
                        BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, b));
            }
        }
    }

    @Test
    void outOfRangeTierThrowsInsteadOfMintingAPhantomBufferName() {
        // A bad tier (e.g. -1 from a caller that forgot to check tierFor's sentinel, or MAX_TIERS from
        // an off-by-one) must crash here rather than produce a syntactically valid string like
        // "voxelOccupancy_t-1" that TargetRegistry.ensureBufferSize would happily key a phantom
        // ~108 MiB buffer set under -- one nothing reads and nothing ever frees.
        assertThrows(IllegalArgumentException.class,
                () -> BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, -1));
        assertThrows(IllegalArgumentException.class,
                () -> BrickGridUpload.targetName(BrickGridUpload.OCCUPANCY_TARGET, VoxelCascade.MAX_TIERS));
    }
}
