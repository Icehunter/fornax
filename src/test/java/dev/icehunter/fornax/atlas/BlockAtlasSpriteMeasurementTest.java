package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockAtlasSpriteMeasurementTest {
    @Test
    void aWellFormedResolvedStackPassesThroughUnchanged() {
        List<FakeEntry> resolved = List.of(
                entry("stone", 16, 16),
                entry("dirt", 16, 16),
                entry("iron_block", 32, 32));

        List<FakeEntry> measured = BlockAtlasSpriteMeasurement.measure(resolved);

        assertEquals(resolved, measured);
    }

    @Test
    void nonPositiveWidthNamesTheOffendingSprite() {
        List<FakeEntry> resolved = List.of(entry("stone", 16, 16), entry("broken", 0, 16));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasSpriteMeasurement.measure(resolved));
        assertEquals(true, failure.getMessage().contains("broken"));
    }

    @Test
    void nonPositiveHeightNamesTheOffendingSprite() {
        List<FakeEntry> resolved = List.of(entry("stone", 16, 16), entry("broken", 16, -4));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasSpriteMeasurement.measure(resolved));
        assertEquals(true, failure.getMessage().contains("broken"));
    }

    @Test
    void aDuplicateNameMeansTheStackWasNotActuallyResolved() {
        List<FakeEntry> unresolved = List.of(
                entry("stone", 16, 16),
                entry("stone", 32, 32));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BlockAtlasSpriteMeasurement.measure(unresolved));
        assertEquals(true, failure.getMessage().contains("stone"));
    }

    private static FakeEntry entry(String path, int width, int height) {
        return new FakeEntry(Identifier.fromNamespaceAndPath("pack", "textures/block/" + path + ".png"),
                width, height);
    }

    private record FakeEntry(Identifier name, int width, int height) implements Stitcher.Entry {
    }
}
