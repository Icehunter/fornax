package dev.icehunter.fornax.voxel;

import java.util.Arrays;
import java.util.Objects;

/** Lists face candidates from one harvested section snapshot, with a fixed cap and a fixed order.
 * Keys are local cellIndex*6+Direction.get3DDataValue(); cellIndex is x | z<<4 | y<<8.
 * Candidates include buried faces and faces whose authored material is zero. They still need a
 * later exposure check and energy evaluation, and are not a light list or transport input. */
public final class VoxelEmitterCandidates {
    // Experimental default from the local coloured-light design's global candidate budget.
    // Enumeration callers may provide a smaller/larger cap; nothing is allocated during harvest.
    public static final int DEFAULT_CAPACITY = 4096;
    private static final int SECTION_CELLS = 16 * 16 * 16;
    public enum Status { AVAILABLE, UNAVAILABLE, PALETTE_OVERFLOW, INCOMPLETE_PALETTE }

    public record Candidates(Status status, int eligible, int unsupported, int[] keys) {
        public Candidates {
            Objects.requireNonNull(status, "status");
            if (eligible < 0 || unsupported < 0 || keys.length > eligible)
                throw new IllegalArgumentException("invalid voxel candidate counts");
            keys = keys.clone();
        }
        @Override public int[] keys() { return this.keys.clone(); }
        public int stored() { return this.keys.length; }
        public int overflow() { return this.eligible - stored(); }
    }

    private static final Candidates UNAVAILABLE = new Candidates(Status.UNAVAILABLE, 0, 0, new int[0]);
    private VoxelEmitterCandidates() { }

    public static Candidates enumerate(SectionHarvester.Result snapshot) {
        return enumerate(snapshot.paletteIndices(), snapshot.sourceEvidence(), DEFAULT_CAPACITY);
    }

    public static Candidates enumerate(SectionHarvester.Result snapshot, int capacity) {
        return enumerate(snapshot.paletteIndices(), snapshot.sourceEvidence(), capacity);
    }

    public static Candidates enumerate(byte[] cells, VoxelSourceEvidence evidence) {
        return enumerate(cells, evidence, DEFAULT_CAPACITY);
    }

    public static Candidates enumerate(byte[] cells, VoxelSourceEvidence evidence, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("voxel candidate capacity must not be negative");
        if (!evidence.available()) return UNAVAILABLE;
        if (evidence.paletteOverflow())
            return new Candidates(Status.PALETTE_OVERFLOW, 0, evidence.unsupportedFaces(), new int[0]);
        if (evidence.incompletePalette())
            return new Candidates(Status.INCOMPLETE_PALETTE, 0, evidence.unsupportedFaces(), new int[0]);
        if (cells.length != SECTION_CELLS)
            throw new IllegalArgumentException("voxel candidate snapshot must contain 4096 cell indices");
        int[] keys = new int[Math.min(capacity, SECTION_CELLS * 6)];
        int eligible = 0, stored = 0, unsupported = 0;
        for (int cell = 0; cell < cells.length; cell++) {
            int entry = cells[cell] & 0xff;
            if (entry >= evidence.paletteSize())
                throw new IllegalArgumentException("voxel candidate cell index exceeds the evidence palette");
            int mask = evidence.eligibleMask(entry);
            eligible += Integer.bitCount(mask);
            unsupported += Integer.bitCount(evidence.unknownMask(entry));
            for (int face = 0; face < 6 && stored < keys.length; face++) {
                if ((mask & 1 << face) != 0) keys[stored++] = cell * 6 + face;
            }
        }
        return new Candidates(Status.AVAILABLE, eligible, unsupported, Arrays.copyOf(keys, stored));
    }
}
