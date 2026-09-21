package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomePackDiscoveryTest {
    @Test
    void optionalManifestIsRereadWithoutRetainingReplacedOrRemovedMappings(@TempDir Path root)
            throws IOException {
        writeMinimalPack(root);
        PackModel absent = PackDiscovery.loadFrom(root, 16, 16);
        assertTrue(absent.biomes().ids().isEmpty());

        Files.writeString(root.resolve("biomes.toml"), "[biomes]\n\"minecraft:plains\" = 9\n");
        PackModel original = PackDiscovery.loadFrom(root, 16, 16);
        assertEquals(9, original.biomes().id("minecraft:plains"));

        Files.writeString(root.resolve("biomes.toml"), "[biomes]\n\"minecraft:lush_caves\" = 2\n");
        PackModel replaced = PackDiscovery.loadFrom(root, 16, 16);
        assertEquals(0, replaced.biomes().id("minecraft:plains"));
        assertEquals(2, replaced.biomes().id("minecraft:lush_caves"));
        assertEquals(9, original.biomes().id("minecraft:plains"));

        Files.delete(root.resolve("biomes.toml"));
        PackModel removed = PackDiscovery.loadFrom(root, 16, 16);
        assertTrue(removed.biomes().ids().isEmpty());
        assertEquals(0, removed.biomes().id("minecraft:lush_caves"));
    }

    private static void writeMinimalPack(Path root) throws IOException {
        Files.writeString(root.resolve("pack.toml"), """
                [pack]
                name = "Biome mapping fixture"
                version = "1.0.0"
                authors = ["Test"]
                license = "MIT"
                format = 1
                """);
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
                """);
        Files.writeString(root.resolve("screens.toml"), "[main]\nelements = []\ncolumns = 1\n");
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/terrain.fsh"), "void main() {}\n");
    }
}
