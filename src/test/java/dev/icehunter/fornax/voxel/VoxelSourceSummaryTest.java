package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxelSourceSummaryTest {
    @Test
    void authoredEmissionIsInventoriedEvenWhenIntrinsicLightIsZero() {
        var face = new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01112233);
        var entry = VoxelSourceSummary.Entry.from(List.of(face));
        var inventory = new VoxelSourceSummary.Accumulator(0x123456789abcdef0L);
        inventory.add(true, 0, entry);
        // Generation low/high, no intrinsic emitter, one authored cell/face, no unknown, one cell.
        assertArrayEquals(new int[]{0x9abcdef0, 0x12345678, 0, 1, 1, 0, 0, 1},
                inventory.finish(false).words());
    }

    @Test
    void unsupportedPositiveEvidenceIsUnknownAndNeverCountedAsASupportedFace() {
        var face = new MaterialSourceIndex.Summary(MaterialSourceIndex.CROPPED_UV,
                1, 0, 1, 0, 0x01112233);
        var inventory = new VoxelSourceSummary.Accumulator(7);
        inventory.add(true, 12, VoxelSourceSummary.Entry.from(List.of(face)));
        assertArrayEquals(new int[]{7, 0, 1, 0, 0, 1, 0, 1}, inventory.finish(false).words());
    }

    @Test
    void absentMapsAreKnownAbsenceButUnavailableAtlasAndPaletteOverflowStayUnknown() {
        var inventory = new VoxelSourceSummary.Accumulator(0);
        inventory.add(true, 0, VoxelSourceSummary.Entry.from(List.of(
                MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP))));
        inventory.add(true, 0, VoxelSourceSummary.Entry.from(List.of(
                MaterialSourceIndex.unavailable(MaterialSourceIndex.NO_ATLAS))));
        inventory.add(true, 0, null);
        inventory.add(false, 0, null);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 2, 1, 3}, inventory.finish(true).words());
    }

    @Test
    void wordsAreFreshCopiesAndDefaultResultsCarryAnEmptyInventory() {
        var summary = new VoxelSourceSummary(3, 1, 2, 3, 4, 0, 5);
        int[] words = summary.words();
        words[0] = 42;
        assertEquals(3, summary.words()[0]);
        var result = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        assertEquals(VoxelSourceSummary.EMPTY, result.sourceSummary());
    }
}
