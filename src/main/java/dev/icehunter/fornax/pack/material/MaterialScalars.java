package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.EmissiveColor;
import dev.icehunter.fornax.pack.EmissiveSpec;

import java.util.List;

/**
 * CPU-side mirror of {@link MaterialInclude}'s emissive-strength generation -- same {@link
 * CategorySpec} inputs, but returning queryable values instead of GLSL text, so voxel harvesting can
 * read a block's real emissive strength/color without duplicating a shader. Strength is reported
 * verbatim from {@link EmissiveSpec#strength()} (no clamp/scale), matching {@link MaterialInclude}'s
 * {@code MAT_EMISSIVE} array generation exactly. Color is reported as a packed {@code 0x00RRGGBB} int
 * ({@code 0} when the category authored no {@link EmissiveColor} -- the harvest/upload path treats
 * that as "derive the tint from face colors instead", never as authored black). Category ID 0
 * (uncategorized) always reports no emission/color, matching {@link MaterialCategories}' own
 * convention.
 */
public final class MaterialScalars {
    private final double[] emissiveStrengthById; // index 0 unused (uncategorized), sized 1 + category count
    private final int[] emissiveColorById;        // 0x00RRGGBB, or 0 when uncategorized/uncolored
    private final boolean[] cutoutById;            // CategorySpec.cutout(), index 0 unused
    private final boolean[] crossById;             // CategorySpec.cross(), index 0 unused

    private MaterialScalars(double[] emissiveStrengthById, int[] emissiveColorById,
            boolean[] cutoutById, boolean[] crossById) {
        this.emissiveStrengthById = emissiveStrengthById;
        this.emissiveColorById = emissiveColorById;
        this.cutoutById = cutoutById;
        this.crossById = crossById;
    }

    public static MaterialScalars build(List<CategorySpec> orderedCategories) {
        double[] strengths = new double[orderedCategories.size() + 1];
        int[] colors = new int[orderedCategories.size() + 1];
        boolean[] cutout = new boolean[orderedCategories.size() + 1];
        boolean[] cross = new boolean[orderedCategories.size() + 1];
        for (int i = 0; i < orderedCategories.size(); i++) {
            CategorySpec cat = orderedCategories.get(i);
            EmissiveSpec emissive = cat.emissive();
            strengths[i + 1] = emissive == null ? 0.0 : emissive.strength();
            EmissiveColor color = emissive == null ? null : emissive.color();
            colors[i + 1] = color == null ? 0 : color.packedRgb();
            cutout[i + 1] = cat.cutout();
            cross[i + 1] = cat.cross();
        }
        return new MaterialScalars(strengths, colors, cutout, cross);
    }

    public boolean hasEmissive(int categoryId) {
        return categoryId > 0 && categoryId < emissiveStrengthById.length && emissiveStrengthById[categoryId] > 0.0;
    }

    public double emissiveStrength(int categoryId) {
        if (categoryId <= 0 || categoryId >= emissiveStrengthById.length) {
            return 0.0;
        }
        return emissiveStrengthById[categoryId];
    }

    /** Packed {@code 0x00RRGGBB} authored emission color, or {@code 0} if the category authored none
     * (uncategorized, no {@code emissive} table, or an {@code emissive} table without {@code color}). */
    public int emissiveColor(int categoryId) {
        if (categoryId <= 0 || categoryId >= emissiveColorById.length) {
            return 0;
        }
        return emissiveColorById[categoryId];
    }

    /** {@code true} iff {@code categoryId}'s category authored {@code cutout = true} in blocks.toml
     * (leaves, cross plants) -- category 0 (uncategorized) and any out-of-range id are always
     * {@code false}, matching {@link #hasEmissive}'s own bounds convention. */
    public boolean isCutout(int categoryId) {
        return categoryId > 0 && categoryId < cutoutById.length && cutoutById[categoryId];
    }

    /** {@code true} iff {@code categoryId}'s category authored {@code cross = true} in blocks.toml
     * (cross/billboard plant geometry) -- same bounds convention as {@link #isCutout}. */
    public boolean isCross(int categoryId) {
        return categoryId > 0 && categoryId < crossById.length && crossById[categoryId];
    }
}
