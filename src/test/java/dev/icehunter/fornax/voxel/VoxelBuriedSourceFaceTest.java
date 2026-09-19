package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.icehunter.fornax.voxel.VoxelSourceWindowTest.word;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A face with something against it lights that thing's inside, which nothing in the world sees. */
class VoxelBuriedSourceFaceTest {
    private static final int AIR = 0, SOURCE = 1, STONE = 2, GLASS = 3;

    private static SectionPalette.Entry entry(VoxelShapeKind kind, boolean transmissive) {
        return new SectionPalette.Entry(kind, List.of(), new int[6], 0, transmissive, 0);
    }

    /** One section whose cells are named by palette index, every source cell fully lit. */
    private static ByteBuffer publish(byte[] cells) {
        var evidence = new VoxelSourceEvidence.Builder();
        var faces = Collections.nCopies(6, MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP));
        evidence.add(false, 0, List.of());
        evidence.add(true, 15, faces);
        evidence.add(true, 0, faces, true);
        evidence.add(true, 0, faces, true);
        for (byte cell : cells) evidence.addCell(cell != AIR, cell);
        var palette = new SectionPalette(List.of(entry(VoxelShapeKind.EMPTY, false),
                entry(VoxelShapeKind.FULL, false), entry(VoxelShapeKind.FULL, false),
                entry(VoxelShapeKind.FULL, true)));
        var summary = new VoxelSourceSummary.Accumulator(7);
        summary.add(true, 15, VoxelSourceSummary.Entry.from(faces));
        var result = new SectionHarvester.Result(cells, palette, new byte[4096],
                summary.finish(false), VoxelHarvestLifecycle.generation(), evidence.finish(false),
                new VoxelSourcePolicy(1L << SOURCE, 0, 4, true));
        var window = VoxelSourceWindowTest.inventory();
        window.commit(0, result, VoxelSourceWindowTest.token(0, 0, 0, 9, 11));
        return window.preparePublication().bytes();
    }

    private static int cell(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /** A row is one run of one face, so which faces reach the world is the rows put together. */
    private static int offeredFaces(ByteBuffer bytes) {
        int union = 0;
        for (int row = 0; row < word(bytes, 2); row++) {
            union |= word(bytes, VoxelSourceWindow.CELL_BASE + row * VoxelSourceWindow.CELL_WORDS + 4) & 63;
        }
        return union;
    }

    /** The origin x of the row carrying one face, or Integer.MIN_VALUE when no row does. */
    private static int originOf(ByteBuffer bytes, int face) {
        for (int row = 0; row < word(bytes, 2); row++) {
            int base = VoxelSourceWindow.CELL_BASE + row * VoxelSourceWindow.CELL_WORDS;
            if ((word(bytes, base + 4) & 1 << face) != 0) return word(bytes, base);
        }
        return Integer.MIN_VALUE;
    }

    @Test void aLoneSourceKeepsAllSixFaces() {
        byte[] cells = new byte[4096];
        cells[cell(4, 4, 4)] = SOURCE;
        var bytes = publish(cells);
        assertEquals(6, word(bytes, 2), "one run per face");
        assertEquals(63, offeredFaces(bytes));
    }

    @Test void twoSourcesSideBySideDropTheFacesTheyShine1IntoEachOther() {
        byte[] cells = new byte[4096];
        cells[cell(4, 4, 4)] = SOURCE;
        cells[cell(5, 4, 4)] = SOURCE;
        var bytes = publish(cells);
        // Four faces pair into runs two cells wide; west and east are left one cell each, and the
        // two faces the cells press together are gone.
        assertEquals(6, word(bytes, 2));
        assertEquals(63, offeredFaces(bytes));
        assertEquals(4, originOf(bytes, 4), "west belongs to the lower cell");
        assertEquals(5, originOf(bytes, 5), "east belongs to the upper cell");
    }

    @Test void anOpaqueCubeBuriesTheFaceAgainstItAndGlassDoesNot() {
        byte[] cells = new byte[4096];
        cells[cell(4, 4, 4)] = SOURCE;
        cells[cell(4, 3, 4)] = STONE;
        cells[cell(4, 5, 4)] = GLASS;
        var bytes = publish(cells);
        assertEquals(5, word(bytes, 2));
        assertEquals(63 & ~(1 << 0), offeredFaces(bytes),
                "the down face is against stone, the up face is against glass");
    }

    @Test void aSourceWalledInOnEverySideIsNotASourceAtAll() {
        byte[] cells = new byte[4096];
        cells[cell(4, 4, 4)] = SOURCE;
        cells[cell(3, 4, 4)] = STONE; cells[cell(5, 4, 4)] = STONE;
        cells[cell(4, 3, 4)] = STONE; cells[cell(4, 5, 4)] = STONE;
        cells[cell(4, 4, 3)] = STONE; cells[cell(4, 4, 5)] = STONE;
        assertEquals(0, word(publish(cells), 2), "no rows published");
    }

    @Test void aSourceOnTheSectionEdgeKeepsTheFaceItCannotSeePast() {
        byte[] cells = new byte[4096];
        cells[cell(0, 4, 4)] = SOURCE;
        cells[cell(1, 4, 4)] = STONE;
        var bytes = publish(cells);
        assertEquals(5, word(bytes, 2));
        assertEquals(63 & ~(1 << 5), offeredFaces(bytes),
                "east is buried; west leaves the section and is left alone");
    }
}
