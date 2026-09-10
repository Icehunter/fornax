package dev.icehunter.fornax.voxel;

/** Pack-selected source membership, independent of raw material/world evidence and geometry.
 * Two words cover the harvested palette cap without adding per-cell data or GPU ABI fields. */
public record VoxelSourcePolicy(long low, long high, int paletteSize, boolean complete) {
    public static final VoxelSourcePolicy ALL = new VoxelSourcePolicy(-1L, -1L,
            SectionHarvester.MAX_PALETTE_ENTRIES, true);

    public VoxelSourcePolicy {
        if (paletteSize < 0 || paletteSize > SectionHarvester.MAX_PALETTE_ENTRIES)
            throw new IllegalArgumentException("source policy exceeds the harvested palette");
    }

    public boolean allows(int entry) {
        return entry >= 0 && entry < paletteSize
                && ((entry < Long.SIZE ? low : high) & (1L << (entry % Long.SIZE))) != 0;
    }

    public static final class Builder {
        private long low, high;
        private int paletteSize;
        private boolean complete = true;

        public void add(boolean allowed) {
            if (paletteSize >= SectionHarvester.MAX_PALETTE_ENTRIES)
                throw new IllegalStateException("source policy exceeds the harvested palette");
            if (allowed) {
                if (paletteSize < Long.SIZE) low |= 1L << paletteSize;
                else high |= 1L << (paletteSize - Long.SIZE);
            }
            paletteSize++;
        }

        /** Geometry variants retain the source policy of the state they came from. */
        void copy(int entry) {
            if (entry < 0 || entry >= paletteSize) throw new IllegalArgumentException("missing source policy entry");
            add(((entry < Long.SIZE ? low : high) & (1L << (entry % Long.SIZE))) != 0);
        }

        /** An unmapped cell aliases palette zero; its source policy must stay unknown. */
        public void markIncomplete() { complete = false; }
        public VoxelSourcePolicy finish(boolean overflow) {
            return new VoxelSourcePolicy(low, high, paletteSize, complete && !overflow);
        }
    }
}
