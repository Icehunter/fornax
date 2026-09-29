package dev.icehunter.fornax.voxel;

/** Pack-selected source membership, independent of raw material/world evidence and geometry.
 * Four words cover every byte-addressable palette entry without changing the GPU record. */
public record VoxelSourcePolicy(long low, long high, long upperLow, long upperHigh,
                                int paletteSize, boolean complete) {
    public static final VoxelSourcePolicy ALL = new VoxelSourcePolicy(-1L, -1L, -1L, -1L,
            VoxelPaletteLayout.MAX_ENTRIES, true);

    public VoxelSourcePolicy(long low, long high, int paletteSize, boolean complete) {
        this(low, high, 0L, 0L, paletteSize, complete);
    }

    public VoxelSourcePolicy {
        if (paletteSize < 0 || paletteSize > VoxelPaletteLayout.MAX_ENTRIES)
            throw new IllegalArgumentException("source policy exceeds the harvested palette");
    }

    public boolean allows(int entry) {
        if (entry < 0 || entry >= paletteSize) return false;
        long word = switch (entry / Long.SIZE) {
            case 0 -> low;
            case 1 -> high;
            case 2 -> upperLow;
            default -> upperHigh;
        };
        return (word & (1L << (entry % Long.SIZE))) != 0;
    }

    public static final class Builder {
        private final long[] words = new long[4]; // ceil(240 selectable entries / 64 bits).
        private final int capacity;
        private int paletteSize;
        private boolean complete = true;

        public Builder() { this(SectionHarvester.maxPaletteEntries()); }
        Builder(int capacity) {
            if (capacity < 1 || capacity > VoxelPaletteLayout.MAX_ENTRIES)
                throw new IllegalArgumentException("invalid source policy capacity");
            this.capacity = capacity;
        }

        public void add(boolean allowed) {
            if (paletteSize >= capacity)
                throw new IllegalStateException("source policy exceeds the harvested palette");
            if (allowed) words[paletteSize / Long.SIZE] |= 1L << (paletteSize % Long.SIZE);
            paletteSize++;
        }

        /** Geometry variants retain the source policy of the state they came from. */
        void copy(int entry) {
            if (entry < 0 || entry >= paletteSize) throw new IllegalArgumentException("missing source policy entry");
            add((words[entry / Long.SIZE] & (1L << (entry % Long.SIZE))) != 0);
        }

        /** An unmapped cell aliases palette zero; its source policy must stay unknown. */
        public void markIncomplete() { complete = false; }
        public VoxelSourcePolicy finish(boolean overflow) {
            return new VoxelSourcePolicy(words[0], words[1], words[2], words[3], paletteSize, complete && !overflow);
        }
    }
}
