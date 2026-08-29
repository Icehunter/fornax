package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;

/**
 * Vulkan-free state for the coarse precipitation uploader.
 *
 * <p>A planned update is deliberately not published until {@link #commit(UploadPlan, Object)}.
 * This separates sampling work from GPU completion: a failed full reset remains a full reset on the
 * next frame and cannot make a consumer trust the old device contents under a new world window.
 */
final class PrecipCoarseClipmapUploadPlan {
    static final int ROWS_PER_FRAME = 8;
    static final int ROW_BYTES = PrecipCoarseClipmapBuffer.GRID * Integer.BYTES;

    private Object committedLevel;
    private int committedBaseCellX;
    private int committedBaseCellZ;
    private int rowCursor;
    private boolean initialized;

    UploadPlan plan(Object level, int baseCellX, int baseCellZ) {
        if (requiresFullReset(level, baseCellX, baseCellZ)) {
            return new UploadPlan(true, baseCellX, baseCellZ, new int[0],
                    PrecipCoarseClipmapBuffer.BYTE_SIZE);
        }
        int[] slotRows = new int[ROWS_PER_FRAME];
        for (int row = 0; row < ROWS_PER_FRAME; row++) {
            slotRows[row] = (baseCellZ + ((rowCursor + row) & (PrecipCoarseClipmapBuffer.GRID - 1)))
                    & (PrecipCoarseClipmapBuffer.GRID - 1);
        }
        return new UploadPlan(false, baseCellX, baseCellZ, slotRows,
                (long) ROWS_PER_FRAME * ROW_BYTES);
    }

    boolean isReadyFor(Object level, int baseCellX, int baseCellZ) {
        return !requiresFullReset(level, baseCellX, baseCellZ);
    }

    void commit(UploadPlan plan, Object level) {
        committedLevel = level;
        committedBaseCellX = plan.baseCellX();
        committedBaseCellZ = plan.baseCellZ();
        initialized = true;
        rowCursor = plan.fullReset() ? 0
                : (rowCursor + ROWS_PER_FRAME) & (PrecipCoarseClipmapBuffer.GRID - 1);
    }

    void clear() {
        committedLevel = null;
        initialized = false;
        rowCursor = 0;
    }

    int rowCursor() {
        return rowCursor;
    }

    private boolean requiresFullReset(Object level, int baseCellX, int baseCellZ) {
        if (!initialized || committedLevel != level) {
            return true;
        }
        int normalStepCells = PrecipCoarseClipmapBuffer.ANCHOR_SNAP_BLOCKS
                / PrecipCoarseClipmapBuffer.CELL_STRIDE;
        // Widen before subtracting so an extreme-coordinate teleport cannot overflow into a small
        // adjacent move and expose a bounded-tag alias under a newly committed window.
        return Math.abs((long) baseCellX - committedBaseCellX) > normalStepCells
                || Math.abs((long) baseCellZ - committedBaseCellZ) > normalStepCells;
    }

    record UploadPlan(boolean fullReset, int baseCellX, int baseCellZ, int[] slotRows, long bytes) {}
}
