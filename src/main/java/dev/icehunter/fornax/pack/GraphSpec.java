package dev.icehunter.fornax.pack;
import java.util.List;
import java.util.Map;
public record GraphSpec(Map<String, TargetSpec> targets, Map<String, PackTextureSpec> textures, List<PassSpec> passes) {
    /** Compat constructor for every pre-existing caller (no pack-shipped texture assets declared). */
    public GraphSpec(Map<String, TargetSpec> targets, List<PassSpec> passes) {
        this(targets, Map.of(), passes);
    }
}
