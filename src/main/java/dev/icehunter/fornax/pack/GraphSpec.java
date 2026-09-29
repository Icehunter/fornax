package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public record GraphSpec(Map<String, TargetSpec> targets, Map<String, PackTextureSpec> textures,
                        List<PassSpec> passes, @Nullable RayTracedShadowSpec rayTracedShadows,
                        Map<String, String> numericExpressions) {
    /** Unresolved sizing formulas survive concrete copies so compile-option reloads can resize. */
    public GraphSpec(Map<String, TargetSpec> targets, Map<String, PackTextureSpec> textures,
                     List<PassSpec> passes, @Nullable RayTracedShadowSpec rayTracedShadows) {
        this(targets, textures, passes, rayTracedShadows, Map.of());
    }
    /** Packs without an explicit ray-traced shadow declaration keep raster ownership. */
    public GraphSpec(Map<String, TargetSpec> targets, Map<String, PackTextureSpec> textures, List<PassSpec> passes) {
        this(targets, textures, passes, null);
    }

    /** Compat constructor for every pre-existing caller (no pack-shipped texture assets declared). */
    public GraphSpec(Map<String, TargetSpec> targets, List<PassSpec> passes) {
        this(targets, Map.of(), passes, null);
    }
}
