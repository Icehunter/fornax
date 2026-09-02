package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;
import org.junit.jupiter.api.Test;

/** Behavioural contract for the Vulkan-free state published only after a successful upload. */
class PrecipCoarseClipmapUploadPlanTest {

    @Test
    void firstFrameAndFailedResetRemainUnreadyUntilAFullUploadCommits() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();

        PrecipCoarseClipmapUploadPlan.UploadPlan first = state.plan(level, -64, 88);
        assertTrue(first.fullReset());
        assertEquals(PrecipCoarseClipmapBuffer.BYTE_SIZE, first.bytes());
        assertFalse(state.isReadyFor(level, -64, 88));

        PrecipCoarseClipmapUploadPlan.UploadPlan retry = state.plan(level, -64, 88);
        assertTrue(retry.fullReset(), "a failed upload must retry the complete reset");
        state.commit(retry, level);
        assertTrue(state.isReadyFor(level, -64, 88));
    }

    @Test
    void teleportUsesLongDeltaAndDoesNotPublishItsNewWindowBeforeCommit() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        PrecipCoarseClipmapUploadPlan.UploadPlan initial = state.plan(level, 0, 0);
        state.commit(initial, level);

        PrecipCoarseClipmapUploadPlan.UploadPlan teleport = state.plan(level, Integer.MAX_VALUE / 4,
                Integer.MIN_VALUE / 4);
        assertTrue(teleport.fullReset());
        assertFalse(state.isReadyFor(level, teleport.baseCellX(), teleport.baseCellZ()));
        assertTrue(state.isReadyFor(level, 0, 0), "the old fully uploaded window remains trustworthy");
    }

    @Test
    void steadyStatePlansExactlyEightToroidalRowsAndSixteenKiB() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        PrecipCoarseClipmapUploadPlan.UploadPlan reset = state.plan(level, 5, -3);
        state.commit(reset, level);

        PrecipCoarseClipmapUploadPlan.UploadPlan steady = state.plan(level, 5, -3);
        assertFalse(steady.fullReset());
        // Eight rows of 128 cells at 16 bytes each.
        assertEquals(8 * 128 * 16, steady.bytes());
        assertArrayEquals(new int[] {125, 126, 127, 0, 1, 2, 3, 4}, steady.slotRows());
        state.commit(steady, level);
        assertEquals(8, state.rowCursor());
    }

    @Test
    void failedSteadyUploadDoesNotAdvanceItsCursor() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        state.commit(state.plan(level, 0, 0), level);

        PrecipCoarseClipmapUploadPlan.UploadPlan first = state.plan(level, 0, 0);
        PrecipCoarseClipmapUploadPlan.UploadPlan retry = state.plan(level, 0, 0);
        assertArrayEquals(first.slotRows(), retry.slotRows());
        assertEquals(0, state.rowCursor());
    }
}
