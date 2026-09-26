package dev.icehunter.fornax.rt.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The grid decides which numbers reach a kernel as floats. A wrong origin is a structure whose
 * geometry is real and a few blocks from where the camera thinks it is, with nothing to report it.
 */
class MeshGridTest {

    @Test
    void theStepIsSixteenSections() {
        assertEquals(256, MeshGrid.STEP_BLOCKS);
    }

    @Test
    void originIsTheGridLineAtOrBelowTheCoordinateIncludingNegatives() {
        assertEquals(0, MeshGrid.origin(0.0));
        assertEquals(0, MeshGrid.origin(255.9));
        assertEquals(256, MeshGrid.origin(256.0));
        assertEquals(-256, MeshGrid.origin(-0.5));
        assertEquals(-512, MeshGrid.origin(-256.1));
        // floor(2000130.7 / 256) = 7813; 7813 * 256 = 2,000,128.
        assertEquals(2_000_128, MeshGrid.origin(2_000_130.7));
    }

    @Test
    void sectionOffsetIsTheSectionsBlockCoordinateRelativeToTheOrigin() {
        assertEquals(0f, MeshGrid.sectionOffset(16, 256));
        assertEquals(-16f, MeshGrid.sectionOffset(15, 256));
        assertEquals(240f, MeshGrid.sectionOffset(31, 256));
        // A far section: the long arithmetic keeps the exact difference before it narrows.
        assertEquals(48f, MeshGrid.sectionOffset(125_003, 2_000_000));
    }

    @Test
    void rebaseSubtractsInDoublesSoAFarCameraKeepsItsFraction() {
        // 2,000,130.7 - 2,000,128 = 2.7: exact in double, and small enough to survive the float.
        assertEquals(2.7f, MeshGrid.rebase(2_000_130.7, 2_000_128), 1e-5f);
        assertEquals(-3.25f, MeshGrid.rebase(-259.25, -256), 1e-6f);
    }
}
