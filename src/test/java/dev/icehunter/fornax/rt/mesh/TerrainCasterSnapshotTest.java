package dev.icehunter.fornax.rt.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the renderer needs a client. Pinned is the selection geometry: a box test that is off by
 * the overhang drops a caster whose only geometry hangs outside its section, and its shadow goes
 * missing.
 */
class TerrainCasterSnapshotTest {

    @Test
    void theOverhangIsFornaxChunkVertexsPositionRange() {
        // a_Position is 16-bit UNORM over [-8, 24): a section's vertices can sit 8 blocks outside it.
        assertEquals(-8, TerrainCasterSnapshot.OVERHANG_MIN_BLOCKS);
        assertEquals(24, TerrainCasterSnapshot.OVERHANG_MAX_BLOCKS);
    }

    @Test
    void theQueryReachMatchesTheMetalTier() {
        // The same 96 blocks MeshMetalProvider.QUERY_REACH_BLOCKS uses: one number per engine.
        assertEquals(96f, TerrainCasterSnapshot.QUERY_REACH_BLOCKS);
    }

    @Test
    void sectionBoundsIncludeTheOverhangOnEverySideRelativeToTheCamera() {
        // Section (2, -1, 3) spans blocks [32,48) x [-16,0) x [48,64); camera at (10, 5, 20).
        double[] b = TerrainCasterSnapshot.sectionBounds(2, -1, 3, 10, 5, 20);
        assertArrayEquals(new double[] {32 - 8 - 10, -16 - 8 - 5, 48 - 8 - 20, 32 + 24 - 10, -16 + 24 - 5, 48 + 24 - 20}, b);
    }

    @Test
    void regionBoundsCoverEverySectionsOverhangNotJustTheRegionsOwnBlocks() {
        // A 2x1x1-section region at chunk (4, 0, 0): blocks [64,96) on X. The outermost section's
        // overhang reaches 8 past that on both ends.
        double[] b = TerrainCasterSnapshot.regionBounds(4, 0, 0, 2, 1, 1, 0, 0, 0);
        assertEquals(64 - 8, b[0]);
        assertEquals(96 + 8, b[3]);
        assertEquals(-8, b[1]);
        assertEquals(16 + 8, b[4]);
    }

    @Test
    void theCameraWindowIsAClosedCubeOfTheGivenRadius() {
        var window = TerrainCasterSnapshot.cameraWindow(96f);
        assertTrue(window.intersects(-10, -10, -10, 10, 10, 10));
        assertTrue(window.intersects(90, 0, 0, 120, 16, 16), "a box straddling the face is in");
        assertTrue(window.intersects(96, 0, 0, 120, 16, 16), "touching the face counts");
        assertFalse(window.intersects(97, 0, 0, 120, 16, 16));
        assertFalse(window.intersects(0, -200, 0, 16, -97, 16));
    }

    @Test
    void aUnionAcceptsWhatEitherFilterAccepts() {
        TerrainCasterSnapshot.RegionFilter left = (a, b, c, d, e, f) -> a < 0;
        TerrainCasterSnapshot.RegionFilter right = (a, b, c, d, e, f) -> a > 100;
        var union = TerrainCasterSnapshot.union(left, right);
        assertTrue(union.intersects(-1, 0, 0, 0, 0, 0));
        assertTrue(union.intersects(101, 0, 0, 0, 0, 0));
        assertFalse(union.intersects(50, 0, 0, 0, 0, 0));
    }
}
