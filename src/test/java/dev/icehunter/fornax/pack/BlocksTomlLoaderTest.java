package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class BlocksTomlLoaderTest {
    @Test void parsesCategoryWithSynthesis() {
        String toml = """
            [categories.polished_metal]
            blocks = ["minecraft:iron_block", "#c:storage_blocks/iron"]
            force_override = true
            smoothness = { source = "albedo_luma", curve = 2.0, min = 0.6 }
            f0 = "metal_albedo"
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        CategorySpec c = s.categories().get("polished_metal");
        assertEquals(2, c.blocks().size());
        assertTrue(c.forceOverride());
        assertEquals(0.6, c.smoothness().min());
        assertEquals("metal_albedo", c.f0());
    }

    @Test void rejectsUnknownCategoryKey() {
        String toml = "[categories.x]\nblocks=[]\nbogus=1\n";
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("bogus", e.key());
    }

    @Test void rejectsNonIdentifierCategoryName() {
        String toml = "[categories.\"Bad Name\"]\nblocks=[]\n";
        assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
    }

    @Test void emptyWhenNoCategories() {
        assertTrue(PackTomlLoader.loadBlocks(new StringReader(""), "blocks.toml")
                .categories().isEmpty());
    }

    @Test void rejectsOutOfRangeSmoothnessMin() {
        String toml = """
            [categories.x]
            smoothness = { source = "albedo_luma", min = 1.5 }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.smoothness.min", e.key());
        assertTrue(e.reason().contains("1.5"));
        assertTrue(e.reason().contains("[0"));
    }

    @Test void rejectsZeroSmoothnessCurve() {
        String toml = """
            [categories.x]
            smoothness = { source = "albedo_luma", curve = 0.0 }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.smoothness.curve", e.key());
    }

    @Test void rejectsHugeSmoothnessCurve() {
        String toml = """
            [categories.x]
            smoothness = { source = "albedo_luma", curve = 9.0 }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.smoothness.curve", e.key());
        assertTrue(e.reason().contains("9.0"));
    }

    @Test void rejectsNegativeEmissiveStrength() {
        String toml = """
            [categories.x]
            emissive = { source = "albedo_luma", strength = -0.5 }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.emissive.strength", e.key());
        assertTrue(e.reason().contains("-0.5"));
    }

    @Test void parsesSmoothnessScale() {
        String toml = """
            [categories.ore]
            blocks = ["minecraft:iron_ore"]
            smoothness = { scale = 0.5 }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        CategorySpec c = s.categories().get("ore");
        assertEquals(0.5, c.smoothness().scale());
        // scale-only smoothness declares no source: no albedo-luma synthesis engaged for this
        // category, just a multiplier over the authored _s value.
        assertEquals(null, c.smoothness().source());
    }

    @Test void smoothnessScaleDefaultsToNeutralWhenAbsent() {
        String toml = """
            [categories.x]
            smoothness = { source = "albedo_luma" }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        assertEquals(1.0, s.categories().get("x").smoothness().scale());
    }

    @Test void rejectsOutOfRangeSmoothnessScale() {
        String toml = """
            [categories.x]
            smoothness = { scale = 4.5 }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.smoothness.scale", e.key());
        assertTrue(e.reason().contains("4.5"));
    }

    @Test void rejectsUnknownSmoothnessSource() {
        String toml = """
            [categories.x]
            smoothness = { source = "vertex_ao" }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.smoothness.source", e.key());
        assertTrue(e.reason().contains("albedo_luma"));
    }

    @Test void rejectsUnknownEmissiveSource() {
        String toml = """
            [categories.x]
            emissive = { source = "nope" }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.emissive.source", e.key());
    }

    @Test void parsesEmissiveColorAsInts() {
        String toml = """
            [categories.redstone_torch]
            blocks = ["minecraft:redstone_torch"]
            emissive = { source = "albedo_luma", strength = 1.0, color = [255, 48, 24] }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        EmissiveColor color = s.categories().get("redstone_torch").emissive().color();
        assertEquals(255, color.r());
        assertEquals(48, color.g());
        assertEquals(24, color.b());
    }

    @Test void parsesEmissiveColorAsNormalizedFloats() {
        String toml = """
            [categories.torch]
            blocks = ["minecraft:torch"]
            emissive = { source = "albedo_luma", strength = 1.0, color = [1.0, 0.627, 0.251] }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        EmissiveColor color = s.categories().get("torch").emissive().color();
        assertEquals(255, color.r());
        assertEquals(160, color.g());
        assertEquals(64, color.b());
    }

    @Test void emissiveColorDefaultsToNullWhenAbsent() {
        String toml = """
            [categories.x]
            emissive = { source = "albedo_luma", strength = 1.0 }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        assertEquals(null, s.categories().get("x").emissive().color());
    }

    @Test void rejectsEmissiveColorWithWrongComponentCount() {
        String toml = """
            [categories.x]
            emissive = { source = "albedo_luma", color = [255, 48] }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.emissive.color", e.key());
    }

    @Test void rejectsOutOfRangeEmissiveColorComponent() {
        String toml = """
            [categories.x]
            emissive = { source = "albedo_luma", color = [256, 0, 0] }
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.emissive.color", e.key());
    }

    @Test void parsesEmissiveForce() {
        String toml = """
            [categories.ore]
            blocks = ["minecraft:iron_ore"]
            emissive = { source = "albedo_luma", strength = 0.6, force = true }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        CategorySpec c = s.categories().get("ore");
        assertTrue(c.emissive().force());
    }

    @Test void emissiveForceDefaultsToFalseWhenAbsent() {
        String toml = """
            [categories.x]
            emissive = { source = "albedo_luma", strength = 1.0 }
            """;
        BlocksSpec s = PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml");
        assertFalse(s.categories().get("x").emissive().force());
    }

    @Test void rejectsUnknownF0Keyword() {
        String toml = """
            [categories.x]
            f0 = "chrome"
            """;
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadBlocks(new StringReader(toml), "blocks.toml"));
        assertEquals("categories.x.f0", e.key());
        assertTrue(e.reason().contains("metal_albedo"));
    }
}
