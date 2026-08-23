package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.EmissiveSpec;
import dev.icehunter.fornax.pack.SmoothnessSpec;

import java.util.List;
import java.util.Locale;

/**
 * Generates the fornax_runtime:shaders/include/materials.glsl include: the MAT_<NAME> index defines,
 * the parameter arrays (indexed by material ID, slot 0 = neutral uncategorized), and the tier-3
 * applyMaterialHook dispatch built from each category's optional snippet. Pure text; no GPU.
 */
public final class MaterialInclude {
    /** Resource path this content is served under (matches ShaderImports's shaders/include/ rule). */
    public static final String PATH = "shaders/include/materials.glsl";

    private MaterialInclude() {}

    /**
     * @param snippetBodies category name -> raw GLSL body for categories that declared a `glsl` ref;
     *                       categories with no snippet are absent from this map.
     */
    public static String generate(MaterialCategories cats, java.util.Map<String, String> snippetBodies) {
        List<CategorySpec> ordered = cats.ordered();
        int n = cats.slotCount();
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated material category table -- do not edit.\n");
        sb.append("#define MAT_UNCATEGORIZED 0\n");
        for (int i = 0; i < ordered.size(); i++) {
            sb.append("#define MAT_").append(ordered.get(i).name().toUpperCase(Locale.ROOT))
              .append(' ').append(i + 1).append('\n');
        }
        sb.append("const int MAT_COUNT = ").append(n).append(";\n");

        // 0 = none/neutral; 1 = albedo_luma. Slot 0 (uncategorized) is neutral in every array.
        appendIntArray(sb, "MAT_SMOOTHNESS_SRC", n, i -> srcCode(smooth(ordered, i) == null ? null : smooth(ordered, i).source()));
        appendFloatArray(sb, "MAT_SMOOTHNESS_CURVE", n, i -> smooth(ordered, i) == null ? 1.0 : smooth(ordered, i).curve());
        appendFloatArray(sb, "MAT_SMOOTHNESS_MIN", n, i -> smooth(ordered, i) == null ? 0.0 : smooth(ordered, i).min());
        // Multiplier over AUTHORED _s smoothness (terrain.fsh applies it once, before Tier-2
        // gap-fill/override runs -- see SmoothnessSpec's doc comment). Neutral (1.0) for slot 0 and
        // any category that didn't declare smoothness at all, or didn't declare scale within it.
        appendFloatArray(sb, "MAT_SMOOTHNESS_SCALE", n, i -> smooth(ordered, i) == null ? 1.0 : smooth(ordered, i).scale());
        appendIntArray(sb, "MAT_F0_MODE", n, i -> f0Mode(ordered, i));
        appendIntArray(sb, "MAT_EMISSIVE_SRC", n, i -> srcCode(emit(ordered, i) == null ? null : emit(ordered, i).source()));
        appendFloatArray(sb, "MAT_EMISSIVE", n, i -> emit(ordered, i) == null ? 0.0 : emit(ordered, i).strength());
        // Bit 0: smoothness/f0's shared category-level force_override. Bit 1: emissive's own `force`
        // (see EmissiveSpec) -- kept as a separate bit rather than a new array since both are single
        // per-category booleans consumed the same way (a bitmask test in terrain.fsh's Tier-2 gate)
        // and MAT_FLAGS already exists as the generated include's home for that shape of data.
        appendUintArray(sb, "MAT_FLAGS", n, i -> (i >= 1 ? flagsFor(ordered.get(i - 1)) : 0));

        // Tier-3 hook: each category with a snippet contributes one guarded block.
        sb.append("void applyMaterialHook(uint mid, inout float smoothness, inout float f0, inout float emissive) {\n");
        for (int i = 0; i < ordered.size(); i++) {
            String body = snippetBodies.get(ordered.get(i).name());
            if (body == null) continue;
            sb.append("    if (mid == ").append(i + 1).append("u) {\n");
            sb.append(body).append('\n');
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static SmoothnessSpec smooth(List<CategorySpec> o, int slot) { return slot == 0 ? null : o.get(slot - 1).smoothness(); }
    private static EmissiveSpec emit(List<CategorySpec> o, int slot) { return slot == 0 ? null : o.get(slot - 1).emissive(); }
    private static int f0Mode(List<CategorySpec> o, int slot) {
        if (slot == 0) return 0;
        return "metal_albedo".equals(o.get(slot - 1).f0()) ? 1 : 0;
    }
    private static int srcCode(String source) { return "albedo_luma".equals(source) ? 1 : 0; }
    private static int flagsFor(CategorySpec cat) {
        int flags = cat.forceOverride() ? 1 : 0;
        if (cat.emissive() != null && cat.emissive().force()) flags |= 2;
        return flags;
    }

    private interface FloatOf { double at(int slot); }
    private interface IntOf { int at(int slot); }

    private static void appendFloatArray(StringBuilder sb, String name, int n, FloatOf f) {
        sb.append("const float ").append(name).append('[').append(n).append("] = float[](");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(", "); sb.append(String.format(Locale.ROOT, "%.5f", f.at(i))); }
        sb.append(");\n");
    }
    private static void appendIntArray(StringBuilder sb, String name, int n, IntOf f) {
        sb.append("const int ").append(name).append('[').append(n).append("] = int[](");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(", "); sb.append(f.at(i)); }
        sb.append(");\n");
    }
    private static void appendUintArray(StringBuilder sb, String name, int n, IntOf f) {
        sb.append("const uint ").append(name).append('[').append(n).append("] = uint[](");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(", "); sb.append(f.at(i)).append('u'); }
        sb.append(");\n");
    }
}
