package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.EmissiveColor;
import dev.icehunter.fornax.pack.EmissiveSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialScalarsTest {
    @Test
    void categoryZeroHasNoEmission() {
        MaterialScalars scalars = MaterialScalars.build(List.of());
        assertFalse(scalars.hasEmissive(0));
        assertEquals(0.0, scalars.emissiveStrength(0));
    }

    @Test
    void categoryWithNoEmissiveSpecHasNoEmission() {
        CategorySpec cat = new CategorySpec("stone_like", List.of("minecraft:stone"), false, null,
                null, null, null, false, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertFalse(scalars.hasEmissive(1));
    }

    @Test
    void categoryWithEmissiveSpecReportsItsStrength() {
        CategorySpec cat = new CategorySpec("lamp", List.of("minecraft:redstone_lamp"), false, null,
                null, null, new EmissiveSpec("albedo_luma", 2.0, null, false), false, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertTrue(scalars.hasEmissive(1));
        assertEquals(2.0, scalars.emissiveStrength(1), 1e-9);
        assertEquals(0, scalars.emissiveColor(1), "no color authored -> 0, fall back to face-color derivation");
    }

    @Test
    void categoryWithAuthoredColorReportsPackedRgb() {
        CategorySpec cat = new CategorySpec("redstone_torch", List.of("minecraft:redstone_torch"), false, null,
                null, null, new EmissiveSpec("albedo_luma", 1.0, new EmissiveColor(255, 48, 24), false), false, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertEquals(0xFF3018, scalars.emissiveColor(1));
    }

    @Test
    void categoryWithNoEmissiveSpecHasNoColor() {
        CategorySpec cat = new CategorySpec("stone_like", List.of("minecraft:stone"), false, null,
                null, null, null, false, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertEquals(0, scalars.emissiveColor(1));
    }

    @Test
    void unknownCategoryIdReturnsNoEmission() {
        MaterialScalars scalars = MaterialScalars.build(List.of());
        assertFalse(scalars.hasEmissive(999));
        assertEquals(0.0, scalars.emissiveStrength(999));
        assertEquals(0, scalars.emissiveColor(999));
    }

    @Test
    void categoryZeroIsNeverCutoutOrCross() {
        MaterialScalars scalars = MaterialScalars.build(List.of());
        assertFalse(scalars.isCutout(0));
        assertFalse(scalars.isCross(0));
    }

    @Test
    void categoryWithNeitherFlagReportsFalseForBoth() {
        CategorySpec cat = new CategorySpec("stone_like", List.of("minecraft:stone"), false, null,
                null, null, null, false, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertFalse(scalars.isCutout(1));
        assertFalse(scalars.isCross(1));
    }

    @Test
    void leavesStyleCategoryIsCutoutOnly() {
        CategorySpec cat = new CategorySpec("foliage", List.of("minecraft:oak_leaves"), false, null,
                null, null, null, true, false);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertTrue(scalars.isCutout(1));
        assertFalse(scalars.isCross(1), "leaves are cutout but not cross-shaped");
    }

    @Test
    void crossPlantCategoryIsCutoutAndCross() {
        CategorySpec cat = new CategorySpec("cross_plants", List.of("minecraft:short_grass"), false, null,
                null, null, null, true, true);
        MaterialScalars scalars = MaterialScalars.build(List.of(cat));
        assertTrue(scalars.isCutout(1));
        assertTrue(scalars.isCross(1));
    }

    @Test
    void unknownCategoryIdIsNeverCutoutOrCross() {
        MaterialScalars scalars = MaterialScalars.build(List.of());
        assertFalse(scalars.isCutout(999));
        assertFalse(scalars.isCross(999));
    }
}
