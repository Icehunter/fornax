package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioural contract for the CHUNK_LOAD-fed pending set, Vulkan- and Minecraft-free like the
 * plan tests: no level, no engine buffer, no thread of its own. */
class PrecipCoarseClipmapPendingChunksTest {

    @Test
    void drainReturnsOnlyChunksWhoseFootprintSitsInsideTheWindow() {
        PrecipCoarseClipmapPendingChunks pending = new PrecipCoarseClipmapPendingChunks();
        // Window base cell 0, 128 cells wide, 4 cells per chunk: chunks 0..31 are inside.
        pending.offer(0, 0);   // inside: first cell 0
        pending.offer(31, 5);  // inside: first cell 124, last cell 127
        pending.offer(32, 0);  // outside: first cell 128, one past the window
        pending.offer(-1, 0);  // outside: first cell -4, before the window

        List<PrecipCoarseClipmapPendingChunks.ChunkKey> drained = pending.drain(0, 0);

        assertEquals(2, drained.size());
        assertTrue(drained.contains(new PrecipCoarseClipmapPendingChunks.ChunkKey(0, 0)));
        assertTrue(drained.contains(new PrecipCoarseClipmapPendingChunks.ChunkKey(31, 5)));
        assertEquals(0, pending.size(), "every offered chunk was popped, in or out of the window");
    }

    @Test
    void offeringTheSameChunkTwiceDoesNotDuplicateOrReorderIt() {
        PrecipCoarseClipmapPendingChunks pending = new PrecipCoarseClipmapPendingChunks();
        pending.offer(1, 1);
        pending.offer(2, 2);
        pending.offer(1, 1);

        assertEquals(2, pending.size());
        List<PrecipCoarseClipmapPendingChunks.ChunkKey> drained = pending.drain(0, 0);
        assertEquals(List.of(new PrecipCoarseClipmapPendingChunks.ChunkKey(1, 1),
                new PrecipCoarseClipmapPendingChunks.ChunkKey(2, 2)), drained,
                "the repeat offer kept 1,1 at its original, older position");
    }

    @Test
    void drainPopsAtMostTheFrameCapOldestFirstAndLeavesTheRestQueued() {
        PrecipCoarseClipmapPendingChunks pending = new PrecipCoarseClipmapPendingChunks();
        int total = PrecipCoarseClipmapPendingChunks.DRAIN_PER_FRAME + 10;
        // Packed into a single window's own 32x32-chunk footprint, so none is filtered as
        // out-of-window and the count below tests the drain cap alone.
        for (int i = 0; i < total; i++) {
            pending.offer(i % 32, i / 32);
        }
        assertEquals(total, pending.size());

        List<PrecipCoarseClipmapPendingChunks.ChunkKey> first = pending.drain(0, 0);
        assertEquals(PrecipCoarseClipmapPendingChunks.DRAIN_PER_FRAME, first.size());
        assertEquals(new PrecipCoarseClipmapPendingChunks.ChunkKey(0, 0), first.get(0),
                "oldest offered chunk drains first");
        assertEquals(total - PrecipCoarseClipmapPendingChunks.DRAIN_PER_FRAME, pending.size());
    }

    @Test
    void offeringPastTheCapDropsOnlyTheSingleOldestEntry() {
        PrecipCoarseClipmapPendingChunks pending = new PrecipCoarseClipmapPendingChunks();
        for (int i = 0; i < PrecipCoarseClipmapPendingChunks.MAX_PENDING; i++) {
            pending.offer(i, 0);
        }
        assertEquals(PrecipCoarseClipmapPendingChunks.MAX_PENDING, pending.size());

        pending.offer(PrecipCoarseClipmapPendingChunks.MAX_PENDING, 0);

        assertEquals(PrecipCoarseClipmapPendingChunks.MAX_PENDING, pending.size(), "the cap holds");
        assertFalse(pending.contains(0, 0), "chunk 0 was the oldest and was evicted");
        assertTrue(pending.contains(1, 0), "chunk 1 is now the oldest survivor");
        assertTrue(pending.contains(PrecipCoarseClipmapPendingChunks.MAX_PENDING, 0), "the newest offer was kept");
    }

    @Test
    void clearDropsEverythingPending() {
        PrecipCoarseClipmapPendingChunks pending = new PrecipCoarseClipmapPendingChunks();
        pending.offer(0, 0);
        pending.offer(1, 1);
        pending.clear();

        assertEquals(0, pending.size());
        assertEquals(List.of(), pending.drain(0, 0));
    }
}
