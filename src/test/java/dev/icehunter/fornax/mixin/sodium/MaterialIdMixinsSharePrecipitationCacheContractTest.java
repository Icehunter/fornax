package dev.icehunter.fornax.mixin.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks both material-id mixins ask {@code BiomePrecipitationCache} for rain/snow, not the game
 * directly. For a block sitting in water, both mixins ask about the same spot, one right after
 * the other, on the same thread; asking the game directly in either one would do that work twice.
 * A mixin cannot run inside a unit test without a live game (see
 * {@code .claude/rules/mixins.md}), so this test reads the source file instead, the same way
 * {@code MaterialIdMixinsUseBulkAccessorContractTest} does.
 */
final class MaterialIdMixinsSharePrecipitationCacheContractTest {
    @Test
    void blockRendererMixinUsesTheSharedCache() throws IOException {
        assertUsesSharedCache("src/main/java/dev/icehunter/fornax/mixin/sodium/BlockRendererMaterialIdMixin.java");
    }

    @Test
    void fluidRendererMixinUsesTheSharedCache() throws IOException {
        assertUsesSharedCache("src/main/java/dev/icehunter/fornax/mixin/sodium/FluidRendererMaterialIdMixin.java");
    }

    private static void assertUsesSharedCache(String path) throws IOException {
        String source = Files.readString(Path.of(path));
        assertTrue(source.contains("BiomePrecipitationCache.at("),
                path + " must read precipitation through BiomePrecipitationCache, not a direct call");
    }
}
