package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** CPU inventory of source summaries whose existing transfer fence completed. All access holds the
 * upload lock; no GPU readback is needed, and queued CPU harvests never enter the totals. */
public final class VoxelSourceInventory {
    // Header matches the per-slot eight-word alignment: generation low/high, ABI, five reserved.
    public static final int HEADER_WORDS = 8;
    public static final int HEADER_BYTES = HEADER_WORDS * Integer.BYTES;
    public static final int ABI_VERSION = 1;

    public record Stats(int committedSlots, long intrinsicCandidateCells, long authoredCandidateCells,
                        long authoredCandidateFaces, long unknownCells, long nonemptyCells,
                        long overflowSlots, long committedUploads, long staleUploads, long clearedSlots,
                        long eligibleFaces, long unsupportedFaces) {
        public Stats(int committedSlots, long intrinsicCandidateCells, long authoredCandidateCells,
                     long authoredCandidateFaces, long unknownCells, long nonemptyCells,
                     long overflowSlots, long committedUploads, long staleUploads, long clearedSlots) {
            this(committedSlots, intrinsicCandidateCells, authoredCandidateCells, authoredCandidateFaces,
                    unknownCells, nonemptyCells, overflowSlots, committedUploads, staleUploads, clearedSlots, 0, 0);
        }
    }

    // Only two counts join the summary here; this inventory keeps no palette evidence arrays.
    private record Committed(VoxelSourceSummary summary, int eligibleFaces, int unsupportedFaces) { }
    private final Map<Integer, Committed> committed = new HashMap<>();
    private long intrinsic, authoredCells, authoredFaces, unknown, nonempty, overflow, eligible, unsupported;
    private long uploads, stale, cleared;

    public static long bufferBytes(int diameter) {
        if (diameter <= 0) throw new IllegalArgumentException("Voxel source diameter must be positive");
        long slots = Math.multiplyExact(Math.multiplyExact((long) diameter, diameter), diameter);
        return Math.addExact(HEADER_BYTES, Math.multiplyExact(slots, VoxelSourceSummary.BYTES_PER_SLOT));
    }

    public static long slotByteOffset(int slot) {
        if (slot < 0) throw new IllegalArgumentException("Voxel source slot must not be negative");
        return HEADER_BYTES + (long) slot * VoxelSourceSummary.BYTES_PER_SLOT;
    }

    public static void packHeader(long atlasGeneration, ByteBuffer destination) {
        destination.clear();
        destination.limit(HEADER_BYTES);
        for (int word = 0; word < HEADER_WORDS; word++) destination.putInt(word * Integer.BYTES, 0);
        destination.putInt(0, (int) atlasGeneration);
        destination.putInt(Integer.BYTES, (int) (atlasGeneration >>> Integer.SIZE));
        destination.putInt(2 * Integer.BYTES, ABI_VERSION);
    }

    public void commit(int slot, VoxelSourceSummary summary, VoxelSourceEvidence evidence) {
        var current = new Committed(summary, evidence.eligibleFaces(), evidence.unsupportedFaces());
        Committed previous = committed.put(slot, current);
        if (previous != null) add(previous, -1);
        add(current, 1);
        uploads++;
    }

    public void commit(int slot, VoxelSourceSummary summary) {
        commit(slot, summary, VoxelSourceEvidence.UNAVAILABLE);
    }

    public void invalidate(Collection<Integer> slots) {
        for (int slot : slots) {
            Committed previous = committed.remove(slot);
            if (previous != null) {
                add(previous, -1);
                cleared++;
            }
        }
    }

    public void dropped() { stale++; }

    public void reset() {
        committed.clear();
        intrinsic = authoredCells = authoredFaces = unknown = nonempty = overflow = eligible = unsupported = 0;
        uploads = stale = cleared = 0;
    }

    public Stats stats() {
        return new Stats(committed.size(), intrinsic, authoredCells, authoredFaces, unknown, nonempty,
                overflow, uploads, stale, cleared, eligible, unsupported);
    }

    private void add(Committed current, int sign) {
        VoxelSourceSummary summary = current.summary();
        eligible += (long) sign * current.eligibleFaces();
        unsupported += (long) sign * current.unsupportedFaces();
        intrinsic += (long) sign * summary.intrinsicCandidateCells();
        authoredCells += (long) sign * summary.authoredCandidateCells();
        authoredFaces += (long) sign * summary.authoredCandidateFaces();
        unknown += (long) sign * summary.unknownCells();
        nonempty += (long) sign * summary.nonemptyCells();
        // Source-summary ABI bit zero denotes palette overflow; the remaining flag bits are reserved.
        overflow += (long) sign * (summary.flags() & 1);
    }
}
