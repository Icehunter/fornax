package dev.icehunter.fornax.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordinals are a wire format, not an implementation detail: they travel in the G channel of
 * {@code rtTerrainShadowDepth} and in word 7 of a ray-query hit record, and a pack compares them.
 * Reordering the enum would relabel every answer already on a GPU with no compiler complaint, so
 * each value is pinned here against the contract in
 * {@code docs/local/2026-09-16-rt-cascade-architecture.md} section 2.1.
 */
class RayTierTest {

    @Test
    void ordinalsAreTheWireValuesNoneSoftwareVoxelHardwareVoxelHardwareMesh() {
        assertEquals(0, RayTier.NONE.ordinal());
        assertEquals(1, RayTier.SOFTWARE_VOXEL.ordinal());
        assertEquals(2, RayTier.HARDWARE_VOXEL.ordinal());
        assertEquals(3, RayTier.HARDWARE_MESH.ordinal());
    }

    /**
     * Appending a tier is allowed and is why readers compare with {@code >=}. This asserts the count
     * so an append is a deliberate edit here rather than a silent widening of the wire range, and
     * so it is noticed alongside the GLSL constants that mirror it.
     */
    @Test
    void thereAreExactlyFourTiersAndAnAppendMustBeDeliberate() {
        assertEquals(4, RayTier.values().length);
    }

    /**
     * The two axes the enum flattens. A reader asking "was this hardware traversal" uses the
     * ordering rather than a second field, and that only works while the hardware tiers sort above
     * the software one.
     */
    @Test
    void hardwareTiersSortAboveTheSoftwareTierSoARangeComparisonRecoversTheTraversal() {
        assertTrue(RayTier.HARDWARE_VOXEL.ordinal() >= RayTier.SOFTWARE_VOXEL.ordinal());
        assertTrue(RayTier.HARDWARE_MESH.ordinal() >= RayTier.HARDWARE_VOXEL.ordinal());
    }
}
