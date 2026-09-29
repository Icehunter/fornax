package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.config.FornaxConfig;

/** The boot-latched capacity. No class initializer snapshots pre-config defaults. */
public final class VoxelPaletteLayout {
    /** One-based byte capture IDs reserve zero for absent and 254/255 for failure. */
    public static final int MAX_CAPTURE_FACTS = 253;
    /** Largest offered capacity, below both failure sentinels. */
    public static final int MAX_ENTRIES = 240;
    private VoxelPaletteLayout() { }
    public static int entries() { return FornaxConfig.activeVoxelPaletteCapacity().entries(); }

    /** Palette plus optional 42-word face records, excluding fixed geometry and RT copies. */
    public static long materialBytes(int capacity, long slots, boolean faces) {
        if (capacity < 1 || capacity > MAX_CAPTURE_FACTS || slots < 0)
            throw new IllegalArgumentException("invalid voxel palette capacity or slot count");
        return Math.multiplyExact(Math.multiplyExact(slots, capacity), (16L + (faces ? 42L : 0L)) * Integer.BYTES);
    }
}
