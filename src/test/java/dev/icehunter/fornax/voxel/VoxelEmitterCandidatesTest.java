package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelEmitterCandidatesTest {
    private static final MaterialSourceIndex.Summary DARK = new MaterialSourceIndex.Summary(0, 1, 1, 0, 0, 0);
    private static final MaterialSourceIndex.Summary POSITIVE = new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01112233);

    private record Snapshot(byte[] cells, VoxelSourceEvidence evidence) { }

    private static Snapshot snapshot(int intrinsic, List<MaterialSourceIndex.Summary> faces,
                                     boolean overflow, int... occupiedCells) {
        var builder = new VoxelSourceEvidence.Builder();
        builder.add(false, 0, List.of());
        builder.add(true, intrinsic, faces);
        byte[] cells = new byte[4096];
        for (int cell : occupiedCells) cells[cell] = 1;
        for (byte cell : cells) builder.addCell(cell != 0, cell & 0xff);
        return new Snapshot(cells, builder.finish(overflow));
    }

    private static List<MaterialSourceIndex.Summary> faces(MaterialSourceIndex.Summary summary) {
        return java.util.Collections.nCopies(6, summary);
    }

    @Test void repeatedPaletteCellsKeepDistinctStableCellAndDirectionKeys() {
        // Cell layout is x | z<<4 | y<<8; a key adds the direction ID on top of that.
        int second = 3 | 5 << 4 | 7 << 8;
        var snapshot = snapshot(0, faces(POSITIVE), false, 0, second);
        var candidates = VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence(), 12);
        assertEquals(VoxelEmitterCandidates.Status.AVAILABLE, candidates.status());
        assertEquals(12, candidates.eligible());
        assertEquals(12, candidates.stored());
        assertEquals(0, candidates.overflow());
        for (Direction direction : Direction.values()) {
            int id = direction.get3DDataValue();
            assertEquals(id, candidates.keys()[id]);
            assertEquals(second * 6 + id, candidates.keys()[6 + id]);
        }
        int[] changed = candidates.keys();
        changed[0] = -1;
        assertEquals(0, candidates.keys()[0], "enumerated keys are immutable");
    }

    @Test void rawIntrinsicAndDirectionalMasksSurviveWithoutMaterialRgb() {
        var selected = new ArrayList<>(faces(DARK));
        selected.set(Direction.NORTH.get3DDataValue(), POSITIVE);
        selected.set(Direction.SOUTH.get3DDataValue(), MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP));
        var snapshot = snapshot(7, selected, false, 17);
        assertEquals(7, snapshot.evidence().intrinsicEmission(1));
        assertEquals(1 << Direction.NORTH.get3DDataValue(), snapshot.evidence().authoredMask(1));
        assertEquals(1 << Direction.SOUTH.get3DDataValue(), snapshot.evidence().missingMask(1));
        assertEquals(0, snapshot.evidence().unknownMask(1));
        assertEquals(6, snapshot.evidence().eligibleFaces());
        selected.set(Direction.NORTH.get3DDataValue(), DARK);
        assertEquals(1 << Direction.NORTH.get3DDataValue(), snapshot.evidence().authoredMask(1));
        // One packed int per palette entry; no source ARGB or per-cell objects are kept.
        assertEquals(2 * Integer.BYTES, snapshot.evidence().paletteBytes());
    }

    @Test void missingMapAllowsIntrinsicEvidenceButNeverCreatesEmissionAlone() {
        var absent = faces(MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP));
        var intrinsic = snapshot(15, absent, false, 1);
        var dark = snapshot(0, absent, false, 1);
        assertEquals(6, intrinsic.evidence().eligibleFaces());
        assertEquals(0, intrinsic.evidence().unsupportedFaces());
        assertEquals(0, dark.evidence().eligibleFaces());
    }

    @Test void positiveAuthoredAlphaIsACandidateAtIntrinsicZeroRegardlessOfMaterialRgb() {
        var a = snapshot(0, faces(POSITIVE), false, 2);
        var b = snapshot(0, faces(new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01abcdef)), false, 2);
        assertEquals(6, a.evidence().eligibleFaces());
        assertArrayEquals(VoxelEmitterCandidates.enumerate(a.cells(), a.evidence()).keys(),
                VoxelEmitterCandidates.enumerate(b.cells(), b.evidence()).keys());
    }

    @ParameterizedTest @ValueSource(ints = {2, 4, 8, 16, 32, 64, 5, 17})
    void unknownSourceOrGeometryFlagsExcludeEvenPositiveIntrinsicAndAuthoredEvidence(int flag) {
        var snapshot = snapshot(15, faces(POSITIVE.withFlags(flag)), false, 3);
        assertEquals(0, snapshot.evidence().eligibleFaces());
        assertEquals(6, snapshot.evidence().unsupportedFaces());
        assertEquals(0x3f, snapshot.evidence().unknownMask(1));
        var candidates = VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence());
        assertEquals(0, candidates.stored());
        assertEquals(6, candidates.unsupported());
    }

    @Test void paletteOverflowRejectsTheWholeSnapshotRatherThanTrustingRemappedZero() {
        var snapshot = snapshot(15, faces(POSITIVE), true, 4);
        var candidates = VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence());
        assertEquals(VoxelEmitterCandidates.Status.PALETTE_OVERFLOW, candidates.status());
        assertEquals(0, candidates.eligible());
        assertEquals(0, candidates.stored());
        assertEquals(6, candidates.unsupported(), "every nonempty face is unknown in a rejected snapshot");
    }

    @Test void aMissingCellLookupCannotBecomeValidEvidenceThroughPaletteZero() {
        var builder = new VoxelSourceEvidence.Builder();
        builder.add(true, 15, faces(POSITIVE));
        builder.addCell(true, -1); // Cell state changed after palette enumeration, without a cap hit.
        var evidence = builder.finish(false);
        var candidates = VoxelEmitterCandidates.enumerate(new byte[4096], evidence);
        assertEquals(VoxelEmitterCandidates.Status.INCOMPLETE_PALETTE, candidates.status());
        assertEquals(0, candidates.eligible());
        assertEquals(0, candidates.stored());
        assertEquals(6, candidates.unsupported());
    }

    @Test void unreadableZeroEvidenceIsUnknownAndAllZeroAuthoredMaterialRetainsIntrinsicDiscovery() {
        var unreadable = snapshot(0, faces(MaterialSourceIndex.unavailable(MaterialSourceIndex.UNREADABLE)), false, 5);
        assertEquals(0, unreadable.evidence().eligibleFaces());
        assertEquals(6, unreadable.evidence().unsupportedFaces());
        var intrinsic = snapshot(12, faces(DARK), false, 5);
        assertEquals(6, intrinsic.evidence().eligibleFaces(), "source-energy evaluation must apply authored-zero suppression later");
    }

    @Test void maximumPaletteIndexAndInvalidInputsNeverWrapOrAlias() {
        var builder = new VoxelSourceEvidence.Builder();
        for (int entry = 0; entry < SectionHarvester.MAX_PALETTE_ENTRIES - 1; entry++) builder.add(false, 0, List.of());
        builder.add(true, 0, faces(POSITIVE));
        builder.addCell(true, SectionHarvester.MAX_PALETTE_ENTRIES - 1);
        var evidence = builder.finish(false);
        byte[] cells = new byte[4096];
        cells[31] = (byte) (SectionHarvester.MAX_PALETTE_ENTRIES - 1);
        assertEquals(31 * 6, VoxelEmitterCandidates.enumerate(cells, evidence).keys()[0]);
        cells[31] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> VoxelEmitterCandidates.enumerate(cells, evidence));
        assertThrows(IllegalArgumentException.class, () -> VoxelEmitterCandidates.enumerate(new byte[2], evidence));
        assertThrows(IllegalArgumentException.class, () -> builder.add(false, 0, List.of()));
    }

    @Test void capacityLimitsStoredKeysWithoutHidingTheEligibleOrOverflowCounts() {
        int[] occupied = new int[4096];
        Arrays.setAll(occupied, i -> i);
        var snapshot = snapshot(0, faces(POSITIVE), false, occupied);
        var candidates = VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence());
        assertEquals(4096, VoxelEmitterCandidates.DEFAULT_CAPACITY);
        assertEquals(4096 * 6, candidates.eligible());
        assertEquals(4096, candidates.stored());
        assertEquals(4096 * 5, candidates.overflow());
        assertEquals(4095, candidates.keys()[4095]);
        var none = VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence(), 0);
        assertEquals(0, none.stored());
        assertEquals(4096 * 6, none.overflow());
        assertThrows(IllegalArgumentException.class,
                () -> VoxelEmitterCandidates.enumerate(snapshot.cells(), snapshot.evidence(), -1));
    }

    @Test void unavailableEvidenceDoesNotScanOrAllocateCandidateStorage() {
        assertFalse(VoxelSourceEvidence.UNAVAILABLE.available());
        var candidates = VoxelEmitterCandidates.enumerate(new byte[0], VoxelSourceEvidence.UNAVAILABLE);
        assertEquals(VoxelEmitterCandidates.Status.UNAVAILABLE, candidates.status());
        assertEquals(0, candidates.stored());
        assertEquals(0, candidates.unsupported());
    }

    @Test void builderSnapshotsAreImmutableAndRejectInvalidRawLevelsAndDirections() {
        var builder = new VoxelSourceEvidence.Builder();
        builder.add(true, 3, faces(POSITIVE));
        builder.addCell(true, 0);
        var first = builder.finish(false);
        builder.add(false, 0, List.of());
        builder.addCell(true, 0);
        assertEquals(1, first.paletteSize());
        assertEquals(6, first.eligibleFaces());
        assertThrows(IllegalArgumentException.class, () -> builder.add(true, 16, faces(DARK)));
        assertThrows(IllegalArgumentException.class, () -> builder.add(true, 1, List.of(DARK)));
        assertTrue(first.available());
    }
}
