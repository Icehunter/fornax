package dev.icehunter.fornax.config;

/** Restart-applied section material capacity, independent of ray/sample budgets. */
public enum VoxelPaletteCapacity {
    // 96 preserves the deployed allocation. 128/192 divide the byte range; 240 leaves
    // room below capture IDs 254/255, which remain explicit failure sentinels.
    ENTRIES_96(96), ENTRIES_128(128), ENTRIES_192(192), ENTRIES_240(240);

    private final int entries;
    VoxelPaletteCapacity(int entries) { this.entries = entries; }
    public int entries() { return entries; }
}
