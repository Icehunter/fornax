package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.TargetBasis;
import dev.icehunter.fornax.pack.graph.TargetFilter;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackTomlLoaderTest {
    @Test
    void storageTextureFlagParsesWithoutChangingOrdinaryTargets() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.waveState]
                format = "rgba16f"
                width = 512
                height = 512
                storage = true

                [targets.color]
                format = "rgba16f"
                scale = 1.0
                """), "graph.toml");
        assertTrue(graph.targets().get("waveState").storage());
        assertFalse(graph.targets().get("color").storage());
    }

    @Test
    void loadsMeta() {
        PackMeta m = PackTomlLoader.loadMeta(new StringReader("""
            [pack]
            name = "Sample Pack"
            version = "0.1.0"
            authors = ["Icehunter"]
            license = "MIT"
            format = 1
            """), "pack.toml");
        assertEquals("Sample Pack", m.name());
        assertEquals(1, m.format());
        assertEquals(List.of("Icehunter"), m.authors());
    }

    @Test
    void metaKeepsRawFormatForCallerToReject() {
        // Loader parses format verbatim; the discovery layer refuses unknown formats.
        PackMeta m = PackTomlLoader.loadMeta(new StringReader("""
            [pack]
            name = "x"
            version = "1"
            authors = []
            license = "MIT"
            format = 99
            """), "pack.toml");
        assertEquals(99, m.format());
    }

    @Test
    void missingPackTableThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadMeta(new StringReader("name = \"x\"\n"), "pack.toml"));
        assertEquals("pack.toml", e.file());
    }

    @Test
    void loadsGraphTargetsAndPassesInOrder() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [targets.gAlbedo]
            format = "rgba8"
            scale = 1.0

            [targets.ssr]
            format = "rgba16f"
            scale = 0.5
            history = true
            enabled_if = "SSR_QUALITY == 1"

            [[pass]]
            name = "terrain_opaque"
            type = "geometry"
            slot = "terrain"
            program = "shaders/terrain"
            outputs = ["gAlbedo"]

            [[pass]]
            name = "ssr_trace"
            type = "fullscreen"
            shader = "shaders/post/ssr_trace.fsh"
            inputs = ["builtin.depth", "gAlbedo"]
            outputs = ["ssr"]
            enabled_if = "SSR_QUALITY != 0"
            """), "graph.toml");
        assertEquals(List.of("gAlbedo", "ssr"), List.copyOf(g.targets().keySet()));
        assertTrue(g.targets().get("ssr").history());
        assertEquals(0.5, g.targets().get("ssr").scale());
        assertEquals(2, g.passes().size());
        assertEquals(PassType.GEOMETRY, g.passes().get(0).type());
        assertEquals(GeometrySlot.TERRAIN, g.passes().get(0).slot());
        assertEquals(List.of("builtin.depth", "gAlbedo"), g.passes().get(1).inputs());
    }

    @Test
    void blendParsesWhenPresent() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [[pass]]
            name = "clouds_composite"
            type = "fullscreen"
            shader = "shaders/post/clouds.fsh"
            inputs = ["builtin.depth"]
            outputs = ["builtin.output"]
            blend = "translucent"
            """), "graph.toml");
        assertEquals("translucent", g.passes().get(0).blend());
    }

    @Test
    void blendDefaultsToNullWhenAbsent() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [[pass]]
            name = "terrain_opaque"
            type = "geometry"
            slot = "terrain"
            program = "shaders/terrain"
            outputs = ["gAlbedo"]
            """), "graph.toml");
        assertNull(g.passes().get(0).blend());
    }

    @Test
    void targetBasisDefaultsToRenderAndOutputRoundTrips() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [targets.gAlbedo]
            format = "rgba8"
            scale = 1.0

            [targets.native]
            format = "rgba8"
            scale = 1.0
            basis = "output"
            """), "graph.toml");
        assertEquals(TargetBasis.RENDER, g.targets().get("gAlbedo").basis());
        assertEquals(TargetBasis.OUTPUT, g.targets().get("native").basis());
    }

    @Test
    void targetFilterDefaultsToNearestAndLinearRoundTrips() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [targets.gAlbedo]
            format = "rgba8"
            scale = 1.0

            [targets.bloom7]
            format = "rgba16f"
            scale = 0.00390625
            filter = "linear"
            """), "graph.toml");
        assertEquals(TargetFilter.NEAREST, g.targets().get("gAlbedo").filter(),
                "omitting filter must stay NEAREST -- what every target got before the key existed");
        assertEquals(TargetFilter.LINEAR, g.targets().get("bloom7").filter());
    }

    @Test
    void fixedTextureExtentParsesAndRejectsScaleOrPartialExtent() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
            [targets.simulation]
            format = "rg16f"
            width = 512
            height = 256
            """), "graph.toml");
        assertEquals(512, graph.targets().get("simulation").fixedSize().width());
        assertEquals(256, graph.targets().get("simulation").fixedSize().height());

        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader("""
            [targets.bad]
            format = "rg16f"
            width = 512
            """), "graph.toml"));
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader("""
            [targets.bad]
            format = "rg16f"
            width = 512
            height = 512
            scale = 0.5
            """), "graph.toml"));
    }

    @Test
    void unknownTargetFilterThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("""
                    [targets.a]
                    format = "r8"
                    filter = "trilinear"
                    """), "graph.toml"));
        assertEquals("targets.a.filter", e.key());
    }

    @Test
    void unknownTargetBasisThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("""
                    [targets.a]
                    format = "r8"
                    basis = "bogus"
                    """), "graph.toml"));
        assertEquals("targets.a.basis", e.key());
    }

    @Test
    void unknownPassTypeThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("""
                    [[pass]]
                    name = "x"
                    type = "bogus"
                    """), "graph.toml"));
        assertEquals("pass.x.type", e.key());
    }

    @Test
    void loadsScreens() {
        ScreensSpec s = PackTomlLoader.loadScreens(new StringReader("""
            sliders = ["SSAO_RADIUS"]

            [main]
            elements = ["<profile>", "SSR_QUALITY", "[LIGHTING]"]
            columns = 2

            [screens.LIGHTING]
            title = "Lighting"
            elements = ["SSAO_ENABLED", "SSAO_RADIUS"]

            [profiles.Ultra]
            values = { SSR_QUALITY = 1, SSAO_ENABLED = true }
            """), "screens.toml");
        assertEquals(2, s.main().columns());
        assertEquals("Lighting", s.screens().get("LIGHTING").title());
        assertEquals(1, ((Number) s.profiles().get("Ultra").values().get("SSR_QUALITY")).intValue());
        assertEquals(List.of("SSAO_RADIUS"), s.sliders());
    }

    @Test
    void loadMeta_rejectsUnknownRootTable() {
        String toml = "[pack]\nname=\"P\"\nversion=\"1\"\nlicense=\"MIT\"\nformat=1\n[oops]\nx=1\n";
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadMeta(new StringReader(toml), "pack.toml"));
        assertEquals("oops", e.key());
    }

    @Test
    void loadGraph_rejectsUnknownRootTable() {
        String toml = "[targets.a]\nformat=\"r8\"\n[bogus]\nx=1\n";
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml"));
        assertEquals("bogus", e.key());
    }

    @Test
    void nonTablePackThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadMeta(new StringReader("pack = \"x\"\n"), "pack.toml"));
        assertEquals("pack", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void nonTableTargetsThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("targets = 3\n"), "graph.toml"));
        assertEquals("targets", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void screensUnknownRootKeyThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("bogus = 1\n"), "screens.toml"));
        assertEquals("screens.toml", e.file());
        assertEquals("bogus", e.key());
        assertTrue(e.reason().contains("bogus"));
    }

    @Test
    void screensUnknownMainKeyThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [main]
                    elements = []
                    rows = 3
                    """), "screens.toml"));
        assertEquals("rows", e.key());
    }

    @Test
    void screensUnknownScreenKeyThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [screens.LIGHTING]
                    title = "Lighting"
                    extra = true
                    """), "screens.toml"));
        assertEquals("extra", e.key());
    }

    @Test
    void screensUnknownProfileKeyThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [profiles.Ultra]
                    values = { SSR_QUALITY = 1 }
                    label = "u"
                    """), "screens.toml"));
        assertEquals("label", e.key());
    }

    @Test
    void screensNonTableScreenThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [screens]
                    LIGHTING = "x"
                    """), "screens.toml"));
        assertEquals("screens.toml", e.file());
        assertEquals("screens.LIGHTING", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void screensNonTableProfileThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [profiles]
                    Ultra = 5
                    """), "screens.toml"));
        assertEquals("profiles.Ultra", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void loadsTextures() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [textures.waterWaveNormal]
            file = "textures/water_wave_normal.png"
            """), "graph.toml");
        assertEquals(1, g.textures().size());
        assertEquals("waterWaveNormal", g.textures().get("waterWaveNormal").name());
        assertEquals("textures/water_wave_normal.png", g.textures().get("waterWaveNormal").file());
    }

    @Test
    void graphWithNoTexturesTableHasEmptyTextures() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
            [targets.gAlbedo]
            format = "rgba8"
            """), "graph.toml");
        assertTrue(g.textures().isEmpty());
    }

    @Test
    void textureMissingFileThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("""
                    [textures.waterWaveNormal]
                    """), "graph.toml"));
        assertEquals("file", e.key());
    }

    @Test
    void textureUnknownKeyThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("""
                    [textures.waterWaveNormal]
                    file = "textures/water_wave_normal.png"
                    scale = 1.0
                    """), "graph.toml"));
        assertEquals("scale", e.key());
    }

    @Test
    void nonTableTextureThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadGraph(new StringReader("textures = 3\n"), "graph.toml"));
        assertEquals("textures", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void screensNonTableProfileValuesThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackTomlLoader.loadScreens(new StringReader("""
                    [profiles.Ultra]
                    values = "SSR_QUALITY"
                    """), "screens.toml"));
        assertEquals("profiles.Ultra.values", e.key());
        assertTrue(e.reason().contains("table"));
    }

    @Test
    void loadsCutoutAndCrossFlags() {
        BlocksSpec spec = PackTomlLoader.loadBlocks(new StringReader("""
            [categories.foliage]
            cutout = true
            blocks = ["minecraft:oak_leaves"]

            [categories.cross_plants]
            cutout = true
            cross = true
            blocks = ["minecraft:short_grass"]

            [categories.stone_like]
            blocks = ["minecraft:stone"]
            """), "blocks.toml");
        CategorySpec foliage = spec.categories().get("foliage");
        assertTrue(foliage.cutout());
        assertFalse(foliage.cross());

        CategorySpec crossPlants = spec.categories().get("cross_plants");
        assertTrue(crossPlants.cutout());
        assertTrue(crossPlants.cross());

        CategorySpec stone = spec.categories().get("stone_like");
        assertFalse(stone.cutout());
        assertFalse(stone.cross());
    }
}
