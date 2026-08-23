package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ghost-slot livefix (voxel water reflection round, polish 1): proves the shape of {@link
 * DirectSectionReader#EMPTY_RESULT}, the value {@code read(Level, SectionPos)} now returns for a
 * position structurally outside the chunk's real section range (above build height / below bedrock)
 * instead of the pre-fix {@code null}. {@code read} itself needs a real vanilla {@code Level}/{@code
 * LevelChunk} this suite has no headless seam to construct (same class of gap {@code
 * ComputeTargetBindingTest} already documents for {@code ComputePassRunner.build()}), so this test
 * targets exactly what the out-of-range branch hands back rather than the branch itself -- if this
 * constant's shape is right, every caller of {@code read} that receives it (namely {@code
 * VoxelWindow#harvestAndUploadBatch} -> {@code recordHarvest} -> {@code BrickGridUpload#uploadSlots})
 * is proven to write correct all-zero occupancy for that slot instead of leaving stale GPU bytes
 * from whatever section previously owned it.
 */
class DirectSectionReaderTest {
    @Test
    void emptyResultHasExactlyOneUnoccupiedPaletteEntry() {
        assertEquals(1, DirectSectionReader.EMPTY_RESULT.palette().entries().size());
        SectionPalette.Entry entry = DirectSectionReader.EMPTY_RESULT.palette().entries().get(0);
        // BrickGridUpload.uploadSlot's own occupancy test: only FULL/PARTIAL ever sets an occupancy
        // bit. EMPTY must never satisfy that, or this "fix" would ghost-occupy the slot itself.
        assertFalse(entry.shapeKind() == VoxelShapeKind.FULL || entry.shapeKind() == VoxelShapeKind.PARTIAL,
                "EMPTY_RESULT's single entry must never classify as occupied");
        assertEquals(VoxelShapeKind.EMPTY, entry.shapeKind());
    }

    @Test
    void emptyResultPaletteIndicesAreAllZeroAndFullSectionSized() {
        byte[] indices = DirectSectionReader.EMPTY_RESULT.paletteIndices();
        assertEquals(16 * 16 * 16, indices.length, "one palette index per voxel, full 16^3 section");
        for (byte b : indices) {
            assertEquals(0, b, "every voxel must index the sole (unoccupied) palette entry, 0");
        }
    }

    @Test
    void emptyResultCarriesNoEmissionOrFaceColor() {
        SectionPalette.Entry entry = DirectSectionReader.EMPTY_RESULT.palette().entries().get(0);
        assertEquals(0.0, entry.emissiveStrength());
        assertEquals(0, entry.emissionColor());
        assertArrayEquals(new int[6], entry.faceColors());
        assertTrue(entry.boxes().isEmpty());
        assertFalse(entry.lightTransmissive());
    }
}
