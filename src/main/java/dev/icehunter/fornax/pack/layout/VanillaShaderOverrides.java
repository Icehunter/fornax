package dev.icehunter.fornax.pack.layout;

import dev.icehunter.fornax.pack.FornaxPackError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of vanilla core shaders a pack may override. A pack file at
 * {@code shaders/vanilla/<name>.fsh} is served (already define-rewritten by
 * {@code GraphRunner.rebuild}'s normal loop) at the vanilla asset path
 * {@code minecraft:shaders/core/<name>.fsh} through {@link RuntimeShaderPack}. Each override
 * point carries a gate compile-option: when the pack resolves it to 0 (or the pack ships no
 * override file), NO entry is produced and vanilla's own shader text wins untouched -- the
 * true-vanilla A/B the spec requires. v1 registers exactly one point, {@code lightmap};
 * Phase 4 (sky) adds its own entry here rather than new plumbing.
 *
 * <p><b>No extra cache invalidation is needed when an override (de)activates.</b> {@code
 * RuntimeShaderPack.reload()} already triggers {@code Minecraft.reloadResourcePacks()}, and a
 * resource reload already forces an unconditional, eager recompile of every static render
 * pipeline -- including {@code RenderPipelines.LIGHTMAP} (fragment {@code core/lightmap.fsh}) --
 * via {@code ShaderManager.apply()}, which calls {@code GpuDevice.clearPipelineCache()} (wiping
 * both the pipeline cache and the {@code ShaderCompilationKey}-keyed shader-module cache) BEFORE
 * eagerly precompiling every static pipeline from the freshly-read source text. Bytecode-verified
 * against the real MC 26.2 jar; see {@code .superpowers/sdd/lightmap-override-research.md}, Q2.
 * No accessor mixin or manual cache-clear call is required here.
 */
public final class VanillaShaderOverrides {
    private static final String PACK_PREFIX = "shaders/vanilla/";

    /** pack file name -> gate compile-option name. */
    private static final Map<String, String> REGISTRY = Map.of(
            "lightmap.fsh", "LIGHTMAP_CURVES");

    private VanillaShaderOverrides() {
    }

    /**
     * @param rewrittenSources the full rewritten source map from {@code GraphRunner.rebuild}
     *     (pack-relative keys), AFTER define stamping
     * @param compileValues resolved compile-option values for this rebuild
     * @return vanilla asset path ({@code shaders/core/<name>.fsh}) -> source text, for every
     *     registered override whose gate option resolves non-zero; never null. A gate option
     *     ABSENT from {@code compileValues} defaults to 0 (vanilla passthrough) rather than 1 --
     *     the safer failure mode: a caller that forgets to resolve/pass a pack's gate option (or a
     *     future call site that doesn't carry the full compile-value map) must never accidentally
     *     activate a vanilla core-shader override it never actually evaluated.
     */
    public static Map<String, String> extract(
            Map<String, String> rewrittenSources, Map<String, Integer> compileValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rewrittenSources.entrySet()) {
            if (!entry.getKey().startsWith(PACK_PREFIX)) {
                continue;
            }
            String name = entry.getKey().substring(PACK_PREFIX.length());
            String gateOption = REGISTRY.get(name);
            if (gateOption == null) {
                throw new FornaxPackError(entry.getKey(), "",
                        "unknown vanilla shader override -- registered overrides: " + REGISTRY.keySet());
            }
            if (compileValues.getOrDefault(gateOption, 0) != 0) {
                out.put("shaders/core/" + name, entry.getValue());
            }
        }
        return out;
    }
}
