package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.*;
import dev.icehunter.fornax.pack.ShaderImports;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialIncludeTest {
    private static BlocksSpec twoCats() {
        // Sequential put()s, not Map.of: Map.of iteration order is hash-salt randomized per JVM run,
        // and these tests pin declaration order (polished_metal = 1, glass = 2).
        java.util.LinkedHashMap<String, CategorySpec> cats = new java.util.LinkedHashMap<>();
        cats.put("polished_metal", new CategorySpec("polished_metal", List.of("minecraft:iron_block"),
                true, null, new SmoothnessSpec("albedo_luma", 2.0, 0.6, 1.0), "metal_albedo", null, false, false));
        cats.put("glass", new CategorySpec("glass", List.of("minecraft:glass"),
                false, null, new SmoothnessSpec("albedo_luma", 1.0, 0.4, 1.0), null, null, false, false));
        return new BlocksSpec(cats);
    }

    @Test void assignsDenseIdsInDeclarationOrder() {
        MaterialCategories c = MaterialCategories.from(twoCats());
        assertEquals(1, c.idOf("polished_metal"));
        assertEquals(2, c.idOf("glass"));
        assertEquals(0, c.idOf("nope"));
        assertEquals(3, c.slotCount());
    }

    @Test void generatesDefinesAndArraysWithNeutralSlotZero() {
        String g = MaterialInclude.generate(MaterialCategories.from(twoCats()), Map.of());
        assertTrue(g.contains("#define MAT_POLISHED_METAL 1"));
        assertTrue(g.contains("#define MAT_GLASS 2"));
        assertTrue(g.contains("const int MAT_COUNT = 3;"));
        // slot 0 neutral, slot 1 force_override flag set
        assertTrue(g.contains("MAT_FLAGS[3] = uint[](0u, 1u, 0u)"));
    }

    private static BlocksSpec emissiveForceCats() {
        // Sequential put()s, not Map.of: Map.of iteration order is hash-salt randomized per JVM run,
        // and these tests pin declaration order (ore = 1, lamp = 2).
        java.util.LinkedHashMap<String, CategorySpec> cats = new java.util.LinkedHashMap<>();
        // ore: no category-level force_override, but emissive.force = true -> bit 1 only (MAT_FLAGS = 2).
        cats.put("ore", new CategorySpec("ore", List.of("minecraft:iron_ore"),
                false, null, null, null, new EmissiveSpec("albedo_luma", 0.6, null, true), false, false));
        // lamp: category-level force_override = true AND emissive.force = true -> both bits (MAT_FLAGS = 3).
        cats.put("lamp", new CategorySpec("lamp", List.of("minecraft:redstone_lamp"),
                true, null, null, null, new EmissiveSpec("albedo_luma", 1.0, null, true), false, false));
        return new BlocksSpec(cats);
    }

    @Test void emitsEmissiveForceFlagAsBit1OfMatFlags() {
        String g = MaterialInclude.generate(MaterialCategories.from(emissiveForceCats()), Map.of());
        // slot 0 neutral, slot 1 (ore) emissive.force only -> bit 1 (2u), slot 2 (lamp) both
        // force_override (bit 0) and emissive.force (bit 1) -> 3u. Bit 0 (smoothness/f0's
        // force_override) stays unaffected by emissive.force alone (ore has it unset).
        assertTrue(g.contains("MAT_FLAGS[3] = uint[](0u, 2u, 3u)"));
    }

    @Test void generatedIncludeSatisfiesShaderImports() {
        String g = MaterialInclude.generate(MaterialCategories.from(twoCats()), Map.of());
        // a pack shader importing it, plus the served include itself, must validate cleanly
        Map<String, String> sources = Map.of(
            "shaders/blocks/terrain.fsh", "#version 330\n#moj_import <fornax_runtime:materials.glsl>\n",
            MaterialInclude.PATH, g);
        assertDoesNotThrow(() -> ShaderImports.validate(sources));
    }

    private static BlocksSpec manyCats(int count) {
        java.util.LinkedHashMap<String, CategorySpec> cats = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = "cat_" + i;
            cats.put(name, new CategorySpec(name, List.of("minecraft:stone"), false, null, null, null, null, false, false));
        }
        return new BlocksSpec(cats);
    }

    @Test void rejectsMoreThanMaxCategories() {
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> MaterialCategories.from(manyCats(1024)));
        assertEquals("blocks.toml", e.file());
        assertEquals("categories", e.key());
        assertTrue(e.reason().contains("1024"));
        assertTrue(e.reason().contains("1023"));
    }

    @Test void acceptsExactlyMaxCategoriesWithSlotZeroReserved() {
        MaterialCategories c = MaterialCategories.from(manyCats(1023));
        assertEquals(1024, c.slotCount());          // 1023 categories + reserved slot 0
        assertEquals(1, c.idOf("cat_0"));           // first category gets 1, never 0
        assertEquals(1023, c.idOf("cat_1022"));     // last legal ID
        assertEquals(0, c.idOf("uncategorized"));   // ID 0 stays the unknown/uncategorized slot
    }

    private static BlocksSpec scaleCats() {
        // Sequential put()s, not Map.of: Map.of iteration order is hash-salt randomized per JVM run,
        // and this test pins declaration order (ore = 1, glass = 2, water = 3).
        java.util.LinkedHashMap<String, CategorySpec> cats = new java.util.LinkedHashMap<>();
        // scale-only smoothness (source null): the ore use case -- scales AUTHORED _s data with no
        // albedo-luma synthesis engaged at all (MAT_SMOOTHNESS_SRC stays 0 for this slot).
        cats.put("ore", new CategorySpec("ore", List.of("minecraft:iron_ore"),
                false, null, new SmoothnessSpec(null, 1.0, 0.0, 0.5), null, null, false, false));
        // declares smoothness (T2 synthesis) but no scale key -> defaults to neutral 1.0.
        cats.put("glass", new CategorySpec("glass", List.of("minecraft:glass"),
                false, null, new SmoothnessSpec("albedo_luma", 1.0, 0.4, 1.0), null, null, false, false));
        // no smoothness table at all -> also neutral 1.0.
        cats.put("water", new CategorySpec("water", List.of("minecraft:water"),
                false, null, null, null, null, false, false));
        return new BlocksSpec(cats);
    }

    @Test void emitsSmoothnessScalePerCategoryWithNeutralDefaults() {
        String g = MaterialInclude.generate(MaterialCategories.from(scaleCats()), Map.of());
        // slot 0 (uncategorized) neutral, slot 1 (ore) authored 0.5, slot 2 (glass) declared
        // smoothness without scale (defaults 1.0), slot 3 (water) has no smoothness table at all
        // (also 1.0).
        assertTrue(g.contains("MAT_SMOOTHNESS_SCALE[4] = float[](1.00000, 0.50000, 1.00000, 1.00000)"));
    }

    @Test void splicesTier3Snippet() {
        String g = MaterialInclude.generate(MaterialCategories.from(twoCats()),
                Map.of("glass", "smoothness = max(smoothness, 0.9);"));
        assertTrue(g.contains("if (mid == 2u) {"));
        assertTrue(g.contains("smoothness = max(smoothness, 0.9);"));
    }
}
