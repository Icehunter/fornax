package dev.icehunter.fornax.voxel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A flat sheet of emitters is one light, not one per cell. */
class VoxelFaceRunsTest {
    private static final int UP = 1;

    private final byte[] faces = new byte[4096];
    private final byte[] entries = new byte[4096];
    private final boolean[][] fullFace = new boolean[3][6];

    VoxelFaceRunsTest() {
        java.util.Arrays.fill(fullFace[1], true);
        java.util.Arrays.fill(fullFace[2], true);
        // Entry 0 is a partial shape, so it never joins a run.
    }

    private static int cell(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    private void emit(int x, int y, int z, int entry, int face) {
        faces[cell(x, y, z)] |= (byte) (1 << face);
        entries[cell(x, y, z)] = (byte) entry;
    }

    private List<VoxelFaceRuns.Run> cover() {
        return VoxelFaceRuns.cover(faces, entries, fullFace);
    }

    @Test void aFlatSheetBecomesOneRun() {
        for (int x = 2; x < 12; x++) for (int z = 3; z < 13; z++) emit(x, 5, z, 1, UP);
        var runs = cover();
        assertEquals(1, runs.size());
        assertEquals(cell(2, 5, 3), runs.getFirst().cell());
        assertEquals(10, runs.getFirst().spanU());
        assertEquals(10, runs.getFirst().spanV());
    }

    @Test void aHoleSplitsTheSheetAndEveryCellIsStillCoveredExactlyOnce() {
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) emit(x, 5, z, 1, UP);
        faces[cell(2, 5, 1)] = 0;
        var runs = cover();
        int covered = 0;
        boolean[] seen = new boolean[4096];
        for (var run : runs) {
            assertEquals(UP, run.face());
            for (int du = 0; du < run.spanU(); du++) for (int dv = 0; dv < run.spanV(); dv++) {
                int c = run.cell() + du + (dv << 4);
                assertTrue((faces[c] >> UP & 1) != 0, "a run covered a cell that does not emit");
                assertTrue(!seen[c], "a cell was covered twice");
                seen[c] = true;
                covered++;
            }
        }
        assertEquals(15, covered);
        assertTrue(runs.size() > 1 && runs.size() <= 4, "a hole splits the sheet, it does not shatter it");
    }

    @Test void twoDifferentBlocksNeverShareARun() {
        for (int x = 0; x < 4; x++) emit(x, 5, 0, x < 2 ? 1 : 2, UP);
        var runs = cover();
        assertEquals(2, runs.size());
        assertEquals(2, runs.getFirst().spanU());
        assertEquals(2, runs.get(1).spanU());
    }

    @Test void twoPlanesOfTheSameBlockNeverShareARun() {
        for (int x = 0; x < 4; x++) { emit(x, 5, 0, 1, UP); emit(x, 6, 0, 1, UP); }
        var runs = cover();
        assertEquals(2, runs.size());
        for (var run : runs) assertEquals(4, run.spanU());
    }

    @Test void aPartialFaceStandsAlone() {
        for (int x = 0; x < 4; x++) emit(x, 5, 0, 0, UP);
        var runs = cover();
        assertEquals(4, runs.size());
        for (var run : runs) { assertEquals(1, run.spanU()); assertEquals(1, run.spanV()); }
    }

    @Test void aCubeOfEmittersRunsEachOfItsSixFacesOnItsOwn() {
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
            for (int face = 0; face < 6; face++) emit(x, 5, z, 1, face);
        }
        var runs = cover();
        // Four cells across on each of two axes for the two Y faces; the four side directions
        // each cover one row of one plane at a time.
        assertEquals(2 + 4 * 4, runs.size());
        assertEquals(2, runs.stream().filter(r -> r.spanU() == 4 && r.spanV() == 4).count());
    }

    @Test void aRunStopsAtTheSectionEdge() {
        for (int x = 12; x < 16; x++) emit(x, 5, 15, 1, UP);
        var runs = cover();
        assertEquals(1, runs.size());
        assertEquals(4, runs.getFirst().spanU());
        assertEquals(1, runs.getFirst().spanV());
    }

    @Test void nothingEmittingCoversNothing() {
        assertEquals(List.of(), cover());
    }
}
