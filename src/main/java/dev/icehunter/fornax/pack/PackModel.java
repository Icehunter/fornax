package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.material.MaterialCategories;
import dev.icehunter.fornax.pack.option.PackOption;

import java.nio.file.Path;
import java.util.Map;

/**
 * Fully loaded, validated shaderpack: parsed manifests plus the merged option table.
 * {@code categories} is derived from {@code blocks} once at construction (see the canonical-arity
 * constructor below) so {@code categories()} never re-runs the ID assignment.
 */
public record PackModel(Path root, PackMeta meta, GraphSpec graph, ScreensSpec screens,
                        Map<String, PackOption> options, BlocksSpec blocks, MaterialCategories categories) {
    public PackModel(Path root, PackMeta meta, GraphSpec graph, ScreensSpec screens,
                     Map<String, PackOption> options, BlocksSpec blocks) {
        this(root, meta, graph, screens, options, blocks, MaterialCategories.from(blocks));
    }
}
