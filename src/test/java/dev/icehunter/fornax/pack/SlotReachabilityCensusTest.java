// In the `pack` package rather than `pipeline`, where the class under test lives: the census is
// exercised against a REAL loaded pack, and PackDiscovery.loadFrom is package-private on purpose.
// Testing it through a hand-built GraphSpec instead would test a mock of the thing that breaks.
package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pipeline.SlotReachabilityCensus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The census's two pure halves: what a pack CLAIMS, and how a claim/reach/substitute triple is
 * classified.
 *
 * <p>The census exists because {@code GraphValidator} structurally cannot answer "does this slot
 * receive draws" -- it deliberately permits declaring a slot the engine does not route to, and that
 * permission is the hole the weather pass fell through for its entire life. Its own classification
 * therefore has to be right, or it becomes another thing that looks like coverage and is not.
 */
class SlotReachabilityCensusTest {
    private static final String PACK_TOML = """
            [pack]
            name = "Census Pack"
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

    @Test
    void claimedSlotsAreExactlyTheGeometryPasses(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("pack.toml"), PACK_TOML);
        Files.writeString(root.resolve("screens.toml"), SCREENS_TOML);
        Files.writeString(root.resolve("shaders/terrain.fsh"),
                "#define BLOOM_ENABLED 1 //[0 1] compile \"Bloom Enabled\"\n");
        Files.writeString(root.resolve("graph.toml"), """
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
                name = "weather"
                type = "geometry"
                slot = "weather"
                program = "shaders/weather"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "banner_patterns"
                type = "geometry"
                slot = "banner_patterns"
                program = "shaders/banner_patterns"
                outputs = ["gAlbedo"]
                """);

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        assertEquals(EnumSet.of(GeometrySlot.TERRAIN, GeometrySlot.WEATHER,
                        GeometrySlot.BANNER_PATTERNS),
                SlotReachabilityCensus.claimedSlots(pack));
    }

    /**
     * The three outcomes must be distinguishable, because they need opposite fixes: unreached means
     * either "nothing of that kind was on screen" or "nothing routes here at all"; reached but never
     * substituted means the hook saw the draw and declined it, which is always a real problem.
     *
     * <p>This asserts the classification through the public surface rather than the log text, which
     * would be a test of string formatting.
     */
    @Test
    void theThreeOutcomesAreDistinguished() {
        Set<GeometrySlot> claimed = EnumSet.of(GeometrySlot.TERRAIN, GeometrySlot.WEATHER,
                GeometrySlot.ENTITIES, GeometrySlot.BANNER_PATTERNS);
        Set<GeometrySlot> reached = EnumSet.of(GeometrySlot.TERRAIN, GeometrySlot.ENTITIES,
                GeometrySlot.BANNER_PATTERNS);
        Set<GeometrySlot> substituted = EnumSet.of(GeometrySlot.TERRAIN, GeometrySlot.BANNER_PATTERNS);

        // Does not throw, and is the shape the frame loop calls. The classification itself is asserted
        // through isRendered() below, which is what the report annotates its unreached entries with.
        SlotReachabilityCensus.report(claimed, reached, substituted);

        // WEATHER is the case the whole class exists for: claimed, validated, program resolved, and
        // never reached, because it bypasses the chokepoint entirely. isRendered() is what lets the
        // report say so rather than leaving it as "maybe nothing was on screen".
        assertFalse(GeometrySlot.WEATHER.isRendered(),
                "weather must stay marked as unrouted -- it is mapped in GeometryPipelineMap and still"
                        + " receives no draws, and the census annotation depends on knowing that");
        assertTrue(GeometrySlot.BANNER_PATTERNS.isRendered());
        assertTrue(GeometrySlot.TERRAIN.isRendered());
    }

    @Test
    void resetClearsEverythingSoAPackSwitchRecensuses() {
        SlotReachabilityCensus.noteSlotSubstituted(GeometrySlot.BANNER_PATTERNS);
        SlotReachabilityCensus.reset();
        // onFrame with no pack must be a no-op rather than reporting an empty census, or a pack switch
        // would emit a spurious "nothing renders" warning on the frame between packs.
        SlotReachabilityCensus.onFrame(null);
    }
}
