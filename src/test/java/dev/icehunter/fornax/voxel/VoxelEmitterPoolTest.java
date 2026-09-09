package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelEmitterPoolTest {
    static SectionHarvester.Result result(long atlas, int... occupied) {
        var builder = new VoxelSourceEvidence.Builder();
        builder.add(false, 0, List.of());
        builder.add(true, 7, java.util.Collections.nCopies(6,
                MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP)));
        byte[] cells = new byte[4096];
        for (int cell : occupied) cells[cell] = 1;
        for (byte cell : cells) builder.addCell(cell != 0, cell);
        int[] uv = new int[VoxelFaceTexture.ENTRY_WORDS];
        for (int face = 0; face < 6; face++) {
            uv[face * 7] = 0x01010203;
            for (int word = 1; word < 7; word++) uv[face * 7 + word] = Float.floatToRawIntBits(word / 16f);
        }
        var entries = new ArrayList<SectionPalette.Entry>();
        entries.add(new SectionPalette.Entry(VoxelShapeKind.EMPTY, List.of(), new int[6], 0, false, 0));
        entries.add(new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(), new int[6], 0, false, 0,
                false, new float[4], 0, 0, uv));
        return new SectionHarvester.Result(cells, new SectionPalette(entries), new byte[4096],
                new VoxelSourceSummary(atlas, occupied.length, 0, 0, 0, 0, occupied.length),
                VoxelHarvestLifecycle.generation(), builder.finish(false));
    }

    private static VoxelSectionState.Snapshot token(int x, int geometry, int content) {
        return new VoxelSectionState.Snapshot(x, 2, -3, 5, geometry, content);
    }

    private static int word(ByteBuffer bytes, int index) { return bytes.getInt(index * Integer.BYTES); }

    @Test void boundedRecordsCarryExactOwnerGenerationsLocalKeyRawIntrinsicAndSevenUvWords() {
        assertEquals(4096, VoxelEmitterPool.CAPACITY);
        assertEquals(16, VoxelEmitterPool.HEADER_WORDS);
        assertEquals(16, VoxelEmitterPool.RECORD_WORDS);
        assertEquals(262208, VoxelEmitterPool.BYTE_SIZE); // 64-byte header + 4096*64 bytes.
        long atlas = 0x1_00000002L;
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, atlas);
        pool.commit(0, result(atlas, 17, 4095), token(-4, 9, 11));
        pool.advance(4096);
        var stats = pool.stats();
        assertEquals(2, stats.stored());
        assertEquals(12, stats.eligible());
        assertEquals(10, stats.deferred());
        assertFalse(stats.rebuilding());
        var publication = pool.preparePublication();
        assertNotNull(publication);
        var bytes = publication.bytes();
        assertEquals(1, word(bytes, 0));
        assertEquals(2, word(bytes, 1));
        assertEquals(2, word(bytes, 2));
        assertEquals(2, word(bytes, 3)); // Deferred, no active refill at capacity.
        assertEquals(12, word(bytes, 4));
        assertEquals(10, word(bytes, 6));
        assertEquals(2, word(bytes, 12));
        assertEquals(1, word(bytes, 13));
        assertEquals(5, word(bytes, 14));
        assertEquals(-4, word(bytes, 16));
        assertEquals(2, word(bytes, 17));
        assertEquals(-3, word(bytes, 18));
        assertEquals(5, word(bytes, 19));
        assertEquals(9, word(bytes, 20));
        assertEquals(2, word(bytes, 21));
        assertEquals(1, word(bytes, 22));
        assertEquals(17 * 6, word(bytes, 23));
        assertEquals(7 | 16, word(bytes, 24));
        assertEquals(0x01010203, word(bytes, 25));
        for (int i = 1; i < 7; i++) assertEquals(Float.floatToRawIntBits(i / 16f), word(bytes, 25 + i));
    }

    @Test void sparseSnapshotResumesWithinTheCellBudgetAndAtCapacityDoesNoFurtherScanning() {
        var pool = new VoxelEmitterPool(1);
        pool.reset(5, 7);
        pool.commit(0, result(7, 4095), token(0, 1, 1));
        assertEquals(4095, pool.advance(4095));
        assertEquals(0, pool.stats().stored());
        assertTrue(pool.stats().rebuilding());
        assertEquals(1, pool.advance(1));
        assertEquals(1, pool.stats().stored());
        assertEquals(0, pool.advance(4096));
        assertEquals(5, pool.stats().deferred());
    }

    @Test void sectionsTakeTurnsInStableOwnerOrderWithoutPerSectionCandidateArrays() {
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        pool.commit(1, result(7, 0), token(1, 1, 1));
        pool.commit(2, result(7, 0), token(-1, 2, 2));
        assertEquals(2, pool.advance(2));
        var bytes = pool.preparePublication().bytes();
        assertEquals(-1, word(bytes, 16));
        assertEquals(1, word(bytes, 32));
        assertEquals(2, pool.stats().committedSlots());
    }

    @Test void onlyChangedSnapshotsPublishAndLightOnlyCommitKeepsAdmissionAndCursor() {
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        var first = result(7, 0);
        pool.commit(0, first, token(0, 1, 1));
        pool.advance(4096);
        var publication = pool.preparePublication();
        pool.markPublished(publication);
        assertNull(pool.preparePublication());
        pool.commit(0, first.withLightmap(new byte[4096]), token(0, 1, 2));
        assertNull(pool.preparePublication());
        assertEquals(0, pool.advance(4096));
        assertEquals(1, pool.stats().publications());
        pool.commit(0, result(7, 10), token(0, 2, 3));
        assertEquals(0, pool.stats().stored());
        pool.advance(4096);
        assertEquals(10 * 6, word(pool.preparePublication().bytes(), 23));
    }

    @Test void invalidationAndGenerationResetRemoveRecordsAndCannotAcknowledgeAnOldPreparedSnapshot() {
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        pool.commit(0, result(7, 0), token(0, 1, 1));
        pool.advance(4096);
        var old = pool.preparePublication();
        pool.invalidate(List.of(0));
        pool.markPublished(old);
        assertNotNull(pool.preparePublication());
        assertEquals(0, pool.stats().stored());
        assertEquals(0, pool.stats().eligible());
        pool.reset(6, 8);
        pool.commit(0, result(7, 0), token(0, 1, 1));
        assertEquals(0, pool.stats().committedSlots(), "old storage/atlas snapshots cannot enter the new pool");
        assertEquals(0, word(pool.preparePublication().bytes(), 2));
    }
    @Test void emptyUnavailableAndOverflowSnapshotsNeverProduceFaceRows() {
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        var empty = DirectSectionReader.EMPTY_RESULT;
        pool.commit(0, new SectionHarvester.Result(empty.paletteIndices(), empty.palette(),
                new byte[4096], new VoxelSourceSummary(7, 0, 0, 0, 0, 0, 0),
                VoxelHarvestLifecycle.generation(), VoxelSourceEvidence.EMPTY), token(0, 1, 1));
        var full = result(7, 0);
        pool.commit(1, new SectionHarvester.Result(full.paletteIndices(), full.palette(),
                full.lightmap(), full.sourceSummary(), VoxelHarvestLifecycle.generation(),
                VoxelSourceEvidence.UNAVAILABLE), token(1, 2, 2));
        var builder = new VoxelSourceEvidence.Builder();
        builder.add(false, 0, List.of());
        builder.add(true, 7, java.util.Collections.nCopies(6,
                MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP)));
        builder.addCell(true, 1);
        pool.commit(2, new SectionHarvester.Result(full.paletteIndices(), full.palette(),
                full.lightmap(), full.sourceSummary(), VoxelHarvestLifecycle.generation(),
                builder.finish(true)), token(2, 3, 3));
        assertEquals(3, pool.stats().committedSlots());
        assertEquals(0, pool.advance(4096));
        assertEquals(0, pool.stats().stored());
        assertEquals(0, pool.stats().eligible());
        assertEquals(6, pool.stats().unsupported());
        assertFalse(pool.stats().rebuilding());
    }

}
