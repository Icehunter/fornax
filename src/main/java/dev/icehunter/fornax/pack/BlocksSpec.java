package dev.icehunter.fornax.pack;

import java.util.Map;

/** Parsed blocks.toml: insertion-ordered categories (declaration order == dense ID order). */
public record BlocksSpec(Map<String, CategorySpec> categories) {
    public static BlocksSpec empty() { return new BlocksSpec(java.util.Map.of()); }
}
