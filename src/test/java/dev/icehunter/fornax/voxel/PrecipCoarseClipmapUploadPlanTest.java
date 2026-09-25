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
        assertEquals(0, first.exposedColumns().length, "a reset carries no slide strip of its own");
        assertEquals(0, first.exposedRows().length);
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
        assertEquals(0, teleport.exposedColumns().length, "a teleport's own reset needs no slide strip");
        assertEquals(0, teleport.exposedRows().length);
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
        assertEquals(0, steady.exposedColumns().length, "the base did not move, so no strip is exposed");
        assertEquals(0, steady.exposedRows().length);
        state.commit(steady, level);
        assertEquals(8, state.rowCursor());
    }

    @Test
    void xSlideExposesExactlyItsNewColumnsAndNoRows() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        state.commit(state.plan(level, 0, 0), level);

        // One normal step (16 blocks, 4 cells) on X alone.
        PrecipCoarseClipmapUploadPlan.UploadPlan slide = state.plan(level, 4, 0);
        assertFalse(slide.fullReset());
        assertArrayEquals(new int[] {0, 1, 2, 3}, slide.exposedColumns(),
                "cells 128..131 are new; their slots wrap straight back to 0..3");
        assertEquals(0, slide.exposedRows().length, "Z did not move");
    }

    @Test
    void zSlideExposesExactlyItsNewRowsAndNoColumns() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        state.commit(state.plan(level, 0, 0), level);

        // One normal step backward (16 blocks, 4 cells) on Z alone.
        PrecipCoarseClipmapUploadPlan.UploadPlan slide = state.plan(level, 0, -4);
        assertFalse(slide.fullReset());
        assertArrayEquals(new int[] {124, 125, 126, 127}, slide.exposedRows(),
                "cells -4..-1 are new; -4 mod 128 is 124, so the strip sits at the grid's far edge");
        assertEquals(0, slide.exposedColumns().length, "X did not move");
    }

    @Test
    void diagonalSlideExposesBothAndARunCanWrapTheGridSeam() {
        PrecipCoarseClipmapUploadPlan state = new PrecipCoarseClipmapUploadPlan();
        Object level = new Object();
        // A committed base whose low bits already sit near the seam, so the exposed strip wraps.
        state.commit(state.plan(level, 126, 0), level);

        PrecipCoarseClipmapUploadPlan.UploadPlan slide = state.plan(level, 130, 4);
        assertFalse(slide.fullReset());
        assertArrayEquals(new int[] {126, 127, 0, 1}, slide.exposedColumns(),
                "the strip crosses the 127-to-0 seam; PrecipCoarseClipmapUpload splits this into two ranges per row");
        assertArrayEquals(new int[] {0, 1, 2, 3}, slide.exposedRows());
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
