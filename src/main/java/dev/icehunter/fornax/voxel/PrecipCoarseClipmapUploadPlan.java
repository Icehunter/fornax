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
    static final int ROW_BYTES = PrecipCoarseClipmapBuffer.GRID * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
    /** The one normal per-frame window step; also requiresFullReset's threshold for that step. */
    static final int NORMAL_STEP_CELLS =
            PrecipCoarseClipmapBuffer.ANCHOR_SNAP_BLOCKS / PrecipCoarseClipmapBuffer.CELL_STRIDE;

    private Object committedLevel;
    private int committedBaseCellX;
    private int committedBaseCellZ;
    private int rowCursor;
    private boolean initialized;

    UploadPlan plan(Object level, int baseCellX, int baseCellZ) {
        if (requiresFullReset(level, baseCellX, baseCellZ)) {
            return new UploadPlan(true, baseCellX, baseCellZ, new int[0],
                    PrecipCoarseClipmapBuffer.BYTE_SIZE, new int[0], new int[0]);
        }
        int[] slotRows = new int[ROWS_PER_FRAME];
        for (int row = 0; row < ROWS_PER_FRAME; row++) {
            slotRows[row] = (baseCellZ + ((rowCursor + row) & (PrecipCoarseClipmapBuffer.GRID - 1)))
                    & (PrecipCoarseClipmapBuffer.GRID - 1);
        }
        // A window slide exposes cells the cyclic sweep above has not reached yet; those cells
        // stay tag-invalid, and read as lit through a consumer's unknown-is-open fallback, until
        // the sweep's own sixteen-frame lap reaches them. Sampling the exposed strip this same
        // frame closes that gap at once. Bounded by the same NORMAL_STEP_CELLS requiresFullReset
        // enforces: at most 4 columns plus 4 rows, ~1024 cells, once per 16 blocks of travel on
        // either axis, well under the steady sweep's own 16 KiB.
        int[] exposedColumns = exposedSlots(committedBaseCellX, baseCellX);
        int[] exposedRows = exposedSlots(committedBaseCellZ, baseCellZ);
        long bytes = (long) ROWS_PER_FRAME * ROW_BYTES
                + (long) exposedColumns.length * PrecipCoarseClipmapBuffer.GRID * PrecipCoarseClipmapBuffer.BYTES_PER_CELL
                + (long) exposedRows.length * ROW_BYTES;
        return new UploadPlan(false, baseCellX, baseCellZ, slotRows, bytes, exposedColumns, exposedRows);
    }

    /**
     * The storage slots a window slide newly exposes on one axis, in walk order from the trailing
     * edge; empty when the base did not move on this axis. Toroidal, the same as every other slot
     * here, so a run can wrap past the grid seam.
     */
    private static int[] exposedSlots(int committedBase, int newBase) {
        int delta = newBase - committedBase;
        if (delta == 0) {
            return new int[0];
        }
        int width = Math.abs(delta);
        int startCell = delta > 0 ? committedBase + PrecipCoarseClipmapBuffer.GRID : newBase;
        int[] slots = new int[width];
        for (int i = 0; i < width; i++) {
            slots[i] = (startCell + i) & (PrecipCoarseClipmapBuffer.GRID - 1);
        }
        return slots;
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
        // Widen before subtracting so an extreme-coordinate teleport cannot overflow into a small
        // adjacent move and expose a bounded-tag alias under a newly committed window.
        return Math.abs((long) baseCellX - committedBaseCellX) > NORMAL_STEP_CELLS
                || Math.abs((long) baseCellZ - committedBaseCellZ) > NORMAL_STEP_CELLS;
    }

    record UploadPlan(boolean fullReset, int baseCellX, int baseCellZ, int[] slotRows, long bytes,
                       int[] exposedColumns, int[] exposedRows) {}
}
