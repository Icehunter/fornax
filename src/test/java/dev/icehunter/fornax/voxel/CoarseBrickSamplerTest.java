package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins WHICH block a coarse tier's point sample reads for a given voxel. Split out pure because the
 * Level-reading half cannot be unit tested headlessly (same gap DirectSectionReader's own class doc
 * records) -- the coordinate math is the part that can silently be wrong, so it is the part tested.
 *
 * <p>{@link CoarseBrickSampler#sampleBlockOffset} is single-axis (see that method's own doc for why
 * it dropped the {@code int[]} return) -- a 3D offset is three independent calls, one per axis, so
 * every test below exercises one axis at a time rather than assembling a 3-element array.
 */
class CoarseBrickSamplerTest {
    @Test
    void tierZeroSamplesEveryBlockExactly() {
        // At tier 0 a voxel IS a block, so the sample offset is the voxel coordinate itself -- the
        // coarse path must degenerate to today's exact behaviour, not an approximation of it.
        assertEquals(0, CoarseBrickSampler.sampleBlockOffset(0, 0));
        assertEquals(5, CoarseBrickSampler.sampleBlockOffset(0, 5));
        assertEquals(9, CoarseBrickSampler.sampleBlockOffset(0, 9));
        assertEquals(15, CoarseBrickSampler.sampleBlockOffset(0, 15));
    }

    @Test
    void coarseTiersSampleTheVoxelCentreNotItsCorner() {
        // Tier 1: each voxel spans 2 blocks, so voxel 0 covers blocks 0-1 and the centre-biased
        // sample is block 1 (offset = voxel*2 + 2/2). Sampling the CORNER (offset 0) would bias
        // every sample toward one side of the brick and systematically miss geometry on the other.
        assertEquals(1, CoarseBrickSampler.sampleBlockOffset(1, 0));
        assertEquals(7, CoarseBrickSampler.sampleBlockOffset(1, 3));
        assertEquals(3, CoarseBrickSampler.sampleBlockOffset(1, 1));

        // Tier 3: 8 blocks per voxel, centre offset 4.
        assertEquals(4, CoarseBrickSampler.sampleBlockOffset(3, 0));
        assertEquals(12, CoarseBrickSampler.sampleBlockOffset(3, 1));
    }

    @Test
    void everySampleStaysInsideTheBrick() {
        // The last voxel of the last tier must not address past the brick's own block extent --
        // an off-by-one here reads a neighbouring brick's blocks and corrupts the tier silently.
        for (int tier = 0; tier < VoxelCascade.MAX_TIERS; tier++) {
            int brick = VoxelCascade.brickBlocks(tier);
            for (int voxel = 0; voxel < VoxelCascade.VOXELS_PER_BRICK_AXIS; voxel++) {
                int offset = CoarseBrickSampler.sampleBlockOffset(tier, voxel);
                assertTrue(offset >= 0 && offset < brick,
                        "tier " + tier + " voxel " + voxel + " offset " + offset
                                + " outside brick extent " + brick);
            }
        }
    }

    @Test
    void distinctVoxelsNeverSampleTheSameBlock() {
        // Two voxels resolving to one block would silently halve effective resolution. The scalar API
        // makes this a genuine full sweep of the one axis the method operates on, not the X-only proxy
        // for a 3-axis result the old int[]-returning test needed.
        for (int tier = 0; tier < VoxelCascade.MAX_TIERS; tier++) {
            Set<Integer> seen = new HashSet<>();
            for (int voxel = 0; voxel < VoxelCascade.VOXELS_PER_BRICK_AXIS; voxel++) {
                int offset = CoarseBrickSampler.sampleBlockOffset(tier, voxel);
                assertTrue(seen.add(offset),
                        "tier " + tier + " voxel " + voxel + " collided with an earlier sample");
            }
            assertEquals(VoxelCascade.VOXELS_PER_BRICK_AXIS, seen.size());
        }
    }
}
