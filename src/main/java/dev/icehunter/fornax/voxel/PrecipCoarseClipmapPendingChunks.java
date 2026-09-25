package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Chunks a CHUNK_LOAD event has reported since the last drain, FIFO and deduplicated.
 *
 * <p>Not thread-safe by itself: it assumes every call lands on the render thread, the same one
 * {@code PrecipCoarseClipmapUpload.onFrame} drains it from. Vanilla and Fabric both fire
 * CHUNK_LOAD from inside client packet handling, which Minecraft's networking layer confines to
 * the render thread the same way every other packet handler is confined there, so that holds for
 * the ordinary path; {@code PrecipCoarseClipmapUpload.onChunkLoaded} is what actually guards this
 * against an off-thread caller (a mod that fires the event some other way), by marshalling to the
 * render thread before ever reaching this class. See that method's own doc for the full reasoning.
 *
 * <p>Vulkan- and Minecraft-free, matching {@link PrecipCoarseClipmapUploadPlan}: its logic is
 * tested directly, with no level or GPU state.
 */
final class PrecipCoarseClipmapPendingChunks {
    /**
     * The window covers 128 cells per axis at 4 cells per chunk: 32x32 = 1024 chunks span the
     * whole window. 4096 is four times that, generous headroom for a fast travel burst to outrun
     * the per-frame drain without letting a stuck drain (or a listener firing with the feature
     * off) grow this without bound; the oldest entry is dropped, never the newest.
     */
    static final int MAX_PENDING = 4096;

    /**
     * Chunks popped per {@code onFrame} drain. Bounds one frame's extra sampling work to a small
     * constant (64 chunks x 16 cells = 1024 cells, an eighth of the whole window) regardless of
     * how many chunks just loaded at once.
     */
    static final int DRAIN_PER_FRAME = 64;

    record ChunkKey(int chunkX, int chunkZ) {}

    private final LinkedHashSet<ChunkKey> pending = new LinkedHashSet<>();

    /**
     * Records a loaded chunk. A chunk already pending is not reordered or duplicated; the cap
     * evicts only the single oldest entry, never more, so one call can never empty an
     * already-healthy queue.
     */
    void offer(int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        if (pending.contains(key)) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            Iterator<ChunkKey> oldest = pending.iterator();
            oldest.next();
            oldest.remove();
        }
        pending.add(key);
    }

    /**
     * Pops up to {@link #DRAIN_PER_FRAME} chunks, oldest first, and returns only those whose
     * whole 4x4-cell footprint sits inside the window at {@code baseCellX, baseCellZ}; the rest
     * are popped and discarded, not requeued. Window edges and chunk edges are both 16-block
     * aligned, so a chunk is never half in and half out.
     */
    List<ChunkKey> drain(int baseCellX, int baseCellZ) {
        List<ChunkKey> inWindow = new ArrayList<>();
        Iterator<ChunkKey> it = pending.iterator();
        int popped = 0;
        while (it.hasNext() && popped < DRAIN_PER_FRAME) {
            ChunkKey key = it.next();
            it.remove();
            popped++;
            if (chunkInWindow(key.chunkX(), baseCellX) && chunkInWindow(key.chunkZ(), baseCellZ)) {
                inWindow.add(key);
            }
        }
        return inWindow;
    }

    /** Drops everything pending. A full reset already resamples the whole window from scratch, so
     * anything queued for it is redundant, and anything for the old window is moot. */
    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }

    /** Whether this exact chunk is pending, ignoring window membership: a query for tests, since
     * a fixed 32x32-chunk window can never hold enough distinct chunks to probe the cap directly
     * through {@link #drain}. */
    boolean contains(int chunkX, int chunkZ) {
        return pending.contains(new ChunkKey(chunkX, chunkZ));
    }

    private static boolean chunkInWindow(int chunkCoord, int baseCell) {
        int cellsPerChunk = 16 / PrecipCoarseClipmapBuffer.CELL_STRIDE;
        int chunkFirstCell = chunkCoord * cellsPerChunk;
        return chunkFirstCell >= baseCell
                && chunkFirstCell + cellsPerChunk <= baseCell + PrecipCoarseClipmapBuffer.GRID;
    }
}
