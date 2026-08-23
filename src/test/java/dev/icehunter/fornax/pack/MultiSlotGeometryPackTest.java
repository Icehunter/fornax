package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end guard for multi-slot geometry passes: a pack declaring several {@code type = "geometry"}
 * passes in different {@link GeometrySlot}s must load cleanly through the whole {@link
 * PackDiscovery#loadFrom} chain, not merely satisfy {@code GraphValidator} in isolation.
 *
 * <p>Declaring a slot the engine does not route geometry into yet is deliberately legal -- a pack
 * should be able to author and ship those programs before the interception that fills them in lands,
 * rather than being unloadable until that day. These tests pin that down so the "reserved but inert"
 * behavior is not quietly lost.
 */
class MultiSlotGeometryPackTest {
    private static final String PACK_TOML = """
            [pack]
            name = "Multi Slot Pack"
            version = "1.0.0"
            authors = ["Test Author"]
            license = "MIT"
            format = 1
            """;

    private static final String SCREENS_TOML = """
            [main]
            elements = []
            columns = 1
            """;

    private static final String SHADER_SOURCE =
            "#define BLOOM_ENABLED 1 //[0 1] compile \"Bloom Enabled\"\n";

    @Test
    void packDeclaringTerrainAndReservedSlotsLoads(@TempDir Path root) throws IOException {
        writePack(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "entities"
                type = "geometry"
                slot = "entities"
                program = "shaders/entities"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "hand"
                type = "geometry"
                slot = "hand"
                program = "shaders/hand"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "banner_patterns"
                type = "geometry"
                slot = "banner_patterns"
                program = "shaders/banner_patterns"
                outputs = ["gAlbedo"]
                """);

        PackModel pack = assertDoesNotThrow(() -> PackDiscovery.loadFrom(root, 1920, 1080));

        assertEquals(4, pack.graph().passes().size());
        assertEquals(GeometrySlot.TERRAIN, pack.graph().passes().get(0).slot());
        assertEquals(GeometrySlot.ENTITIES, pack.graph().passes().get(1).slot());
        assertEquals(GeometrySlot.HAND, pack.graph().passes().get(2).slot());
        assertEquals(GeometrySlot.BANNER_PATTERNS, pack.graph().passes().get(3).slot());

        // Terrain, entities and banner patterns all draw today, by three DIFFERENT routes; hand is
        // still declared, validated and inert. This assertion used to read "only terrain draws", which
        // was true when it was written and stayed in the file long after entities started rendering --
        // the same staleness GeometrySlot.isRendered() itself carried, and the same question a pack
        // author asks before spending a round on a pass.
        assertTrue(GeometrySlot.TERRAIN.isRendered());
        assertTrue(GeometrySlot.ENTITIES.isRendered());
        assertTrue(GeometrySlot.BANNER_PATTERNS.isRendered());
        assertFalse(GeometrySlot.HAND.isRendered());

        // WEATHER is the one that matters: it IS mapped in GeometryPipelineMap and still renders
        // nothing, because WeatherEffectRenderer never reaches the draw chokepoint that map is
        // consulted at. Anyone tempted to derive isRendered() from isMapped() breaks exactly here.
        assertFalse(GeometrySlot.WEATHER.isRendered());

        // Forward is a property of the SLOT, not of the pass -- there is no `type = "forward"` and no
        // `mode` key, and declaring one must stay a parse error rather than becoming a way for a pack
        // to assert something false about vanilla's own draw order.
        assertTrue(GeometrySlot.BANNER_PATTERNS.rendersForward());
        assertFalse(GeometrySlot.TERRAIN.rendersForward());
        assertFalse(GeometrySlot.ENTITIES.rendersForward());
    }

    @Test
    void geometryPassWithoutAnExplicitSlotLoadsAsTerrain(@TempDir Path root) throws IOException {
        writePack(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                program = "shaders/terrain"
                outputs = ["gAlbedo"]
                """);

        PackModel pack = assertDoesNotThrow(() -> PackDiscovery.loadFrom(root, 1920, 1080));
        assertEquals(GeometrySlot.TERRAIN, pack.graph().passes().get(0).slot());
    }

    private static void writePack(Path root, String graphToml) throws IOException {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("pack.toml"), PACK_TOML);
        Files.writeString(root.resolve("graph.toml"), graphToml);
        Files.writeString(root.resolve("screens.toml"), SCREENS_TOML);
        Files.writeString(root.resolve("shaders/terrain.fsh"), SHADER_SOURCE);
    }
}
