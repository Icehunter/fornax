package dev.icehunter.fornax.pack;

import java.util.Map;

/** Parsed blocks.toml: insertion-ordered categories (declaration order == dense ID order). */
public record BlocksSpec(Map<String, CategorySpec> categories, boolean voxelLightingDefault) {
    /** Existing manifests allow every source until they declare a lighting policy. */
    public BlocksSpec(Map<String, CategorySpec> categories) { this(categories, true); }
    public static BlocksSpec empty() { return new BlocksSpec(java.util.Map.of()); }
}
