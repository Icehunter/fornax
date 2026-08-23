package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the cascade's tier geometry. Every other piece of the cascade derives its sizes from this
 * class, so these numbers are the single place the 16/32/64/128-block ladder is asserted.
 */
class VoxelCascadeTest {
    @Test
    void brickSizeDoublesPerTier() {
        assertEquals(16, VoxelCascade.brickBlocks(0));
        assertEquals(32, VoxelCascade.brickBlocks(1));
        assertEquals(64, VoxelCascade.brickBlocks(2));
        assertEquals(128, VoxelCascade.brickBlocks(3));
    }

    @Test
    void blocksPerVoxelDoublesPerTier() {
        // Every tier's brick holds the same 16^3 voxels, so a voxel spans brickBlocks/16 blocks.
        assertEquals(1, VoxelCascade.blocksPerVoxel(0));
        assertEquals(2, VoxelCascade.blocksPerVoxel(1));
        assertEquals(4, VoxelCascade.blocksPerVoxel(2));
        assertEquals(8, VoxelCascade.blocksPerVoxel(3));
    }

    @Test
    void reachIsEightSlotRadiiOfBricks() {
        // TIER_DIAMETER 17 -> slot radius 8; reach = 8 * brickBlocks = 8/16/32/64 chunks.
        assertEquals(128, VoxelCascade.reachBlocks(0));
        assertEquals(256, VoxelCascade.reachBlocks(1));
        assertEquals(512, VoxelCascade.reachBlocks(2));
        assertEquals(1024, VoxelCascade.reachBlocks(3));
    }

    @Test
    void slotRadiusIsPublicAndDerivedFromTierDiameter() {
        // VoxelWindow.WindowState.of (Task 4) takes a radius, not a diameter -- must be public so it
        // does not have to re-derive (TIER_DIAMETER - 1) / 2 itself.
        assertEquals(8, VoxelCascade.SLOT_RADIUS);
        assertEquals((VoxelCascade.TIER_DIAMETER - 1) / 2, VoxelCascade.SLOT_RADIUS);
    }

    @Test
    void tierCountCoversRenderDistanceAndNoMore() {
        assertEquals(1, VoxelCascade.tierCountFor(8));   // T0 reaches exactly 8 chunks
        assertEquals(2, VoxelCascade.tierCountFor(12));  // T0 short at 8, T1 covers 16
        assertEquals(2, VoxelCascade.tierCountFor(16));
        assertEquals(3, VoxelCascade.tierCountFor(32));
        assertEquals(4, VoxelCascade.tierCountFor(64));
    }

    @Test
    void tierCountClampsToTheTierLadderAtBothEnds() {
        assertEquals(1, VoxelCascade.tierCountFor(1));
        assertEquals(1, VoxelCascade.tierCountFor(0));    // degenerate/headless
        assertEquals(1, VoxelCascade.tierCountFor(-5));   // never negative or zero tiers
        assertEquals(MAX, VoxelCascade.tierCountFor(999)); // beyond T3, clamp rather than grow
    }

    private static final int MAX = VoxelCascade.MAX_TIERS;

    @Test
    void tierForPicksTheFinestTierContainingThePoint() {
        // With all 4 tiers allocated: finest tier whose reach covers the distance wins.
        assertEquals(0, VoxelCascade.tierFor(0, 4));
        assertEquals(0, VoxelCascade.tierFor(128, 4));   // exactly T0's edge still T0
        assertEquals(1, VoxelCascade.tierFor(129, 4));
        assertEquals(1, VoxelCascade.tierFor(256, 4));
        assertEquals(2, VoxelCascade.tierFor(257, 4));
        assertEquals(3, VoxelCascade.tierFor(1024, 4));
    }

    @Test
    void tierForReportsMinusOneBeyondTheAllocatedTiers() {
        // Only 2 tiers allocated (render distance 12): anything past T1's 256 blocks is uncovered,
        // and the caller must fall back rather than read a tier that does not exist.
        assertEquals(-1, VoxelCascade.tierFor(257, 2));
        assertEquals(-1, VoxelCascade.tierFor(1025, 4));
    }

    @Test
    void negativeAndOutOfRangeTiersThrow() {
        // Negative tier must throw, not silently return tier-0 geometry.
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.brickBlocks(-1));
        // Tier >= MAX_TIERS must throw.
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.brickBlocks(MAX));
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.brickBlocks(MAX + 1));
    }

    @Test
    void tierForNegativeReturnThrowsWhenPassedToGeometryMethods() {
        // Crucial: tierFor returns -1 to indicate "no coverage". If a caller forgets
        // the null check and passes -1 to a geometry method, it must crash, not silently
        // return tier-0 data, which would hide the caller bug.
        int noCoverage = VoxelCascade.tierFor(10000, 4);
        assertEquals(-1, noCoverage, "tierFor should return -1 for out-of-reach distance");
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.brickBlocks(noCoverage));
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.blocksPerVoxel(noCoverage));
        assertThrows(IllegalArgumentException.class, () -> VoxelCascade.reachBlocks(noCoverage));
    }
}
