package dev.icehunter.fornax.pack.layout;

import dev.icehunter.fornax.pack.FornaxPackError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of vanilla binary assets a pack may override -- the binary counterpart to {@link
 * VanillaShaderOverrides}. A pack file at {@code textures/vanilla/<name>} is served (read verbatim
 * as bytes by {@code PackDiscovery.readTextureOverrides}) at the vanilla asset path {@code
 * minecraft:textures/environment/<name>} through {@link RuntimeShaderPack}. v1 registers the nine
 * celestial textures (the sun plus all eight moon phases) that MC 26.2's celestials atlas sources
 * from {@code textures/environment/celestial/} -- all nine gated behind a single compile-option,
 * {@code CELESTIAL_TEXTURES}: when the pack resolves it to 0 (or the pack ships none of the nine
 * files), NO entry is produced and vanilla's own celestial textures win untouched -- the true-vanilla
 * A/B the spec requires. Gate ON means the pack's texture(s) outrank user resource packs (this
 * synthetic pack sits at {@code Position.TOP}); gate OFF (or absent) means user resource packs and
 * vanilla's own textures win exactly as they always have.
 */
public final class VanillaAssetOverrides {
    private static final String PACK_PREFIX = "textures/vanilla/";
    private static final String GATE_OPTION = "CELESTIAL_TEXTURES";

    private static final String[] MOON_PHASES = {
            "full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
            "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous"};

    /** pack file name -> gate compile-option name. */
    private static final Map<String, String> REGISTRY = buildRegistry();

    private static Map<String, String> buildRegistry() {
        Map<String, String> registry = new LinkedHashMap<>();
        registry.put("celestial/sun.png", GATE_OPTION);
        for (String phase : MOON_PHASES) {
            registry.put("celestial/moon/" + phase + ".png", GATE_OPTION);
        }
        return Map.copyOf(registry);
    }

    private VanillaAssetOverrides() {
    }

    /**
     * @param textureOverrides the full pack-relative binary override map from {@code
     *     PackDiscovery.readTextureOverrides} ({@code "textures/vanilla/<name>"} -> raw bytes)
     * @param compileValues resolved compile-option values for this rebuild
     * @return vanilla asset path ({@code textures/environment/<name>}) -> raw bytes, for every
     *     registered override whose gate option resolves non-zero; never null. A gate option
     *     ABSENT from {@code compileValues} defaults to 0 (vanilla passthrough) rather than 1 --
     *     the safer failure mode: a caller that forgets to resolve/pass a pack's gate option (or a
     *     future call site that doesn't carry the full compile-value map) must never accidentally
     *     activate a vanilla asset override it never actually evaluated.
     */
    public static Map<String, byte[]> extract(
            Map<String, byte[]> textureOverrides, Map<String, Integer> compileValues) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : textureOverrides.entrySet()) {
            if (!entry.getKey().startsWith(PACK_PREFIX)) {
                continue;
            }
            String name = entry.getKey().substring(PACK_PREFIX.length());
            String gateOption = REGISTRY.get(name);
            if (gateOption == null) {
                throw new FornaxPackError(entry.getKey(), "",
                        "unknown vanilla asset override -- registered overrides: " + REGISTRY.keySet());
            }
            if (compileValues.getOrDefault(gateOption, 0) != 0) {
                out.put("textures/environment/" + name, entry.getValue());
            }
        }
        return out;
    }
}
