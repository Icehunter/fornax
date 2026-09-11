package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code SectionHarvester.partialCutoutAllowed}, the pure decision {@code buildEntry} uses to
 * admit a PARTIAL cell's cutout material into the same UV-rect words a FULL or CROSS cutout entry
 * uses. {@code buildEntry} itself needs a started-up {@code BlockState} model to run (see
 * {@code SectionHarvesterTest}), so the decision is pulled out and tested on its own.
 */
class SectionHarvesterPartialCutoutTest {
    @Test
    void partialWithCutoutTagAndUpToSixBoxesIsAllowed() {
        // 6 is CUTOUT_MAX_BOXES: the box count that leaves box slots 6/7 free for the UV rect.
        assertTrue(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 1, true));
        assertTrue(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 6, true));
    }

    @Test
    void partialWithCutoutTagAndMoreThanSixBoxesIsNotAllowed() {
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 7, true));
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 8, true));
    }

    @Test
    void partialWithCutoutTagAndZeroBoxesIsNotAllowed() {
        // The rule is 1..CUTOUT_MAX_BOXES, not 0..CUTOUT_MAX_BOXES: a PARTIAL cell with no boxes has
        // no box faces to alpha-test the rect against.
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 0, true));
    }

    @Test
    void partialWithoutTheCutoutTagIsNeverAllowedRegardlessOfBoxCount() {
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.PARTIAL, 1, false));
    }

    @Test
    void fullAndCrossAreUnaffectedHere() {
        // buildEntry decides their cutout bit in its own branches. This decision always answers
        // false for them, so no shape is handled twice.
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.FULL, 0, true));
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.CROSS, 1, true));
    }

    @Test
    void emptyIsNeverAllowed() {
        assertFalse(SectionHarvester.partialCutoutAllowed(VoxelShapeKind.EMPTY, 0, true));
    }
}
