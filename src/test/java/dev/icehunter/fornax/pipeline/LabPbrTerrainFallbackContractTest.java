package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabPbrTerrainFallbackContractTest {
    @Test
    void terrainNeverBindsAlbedoAsNormalOrMaterialFallback() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/DefaultChunkRendererTextureBindMixin.java"));

        assertFalse(source.contains("normalMapAtlas.getTextureView() : renderPass.getAtlas()"));
        assertFalse(source.contains("materialMapAtlas.getTextureView() : renderPass.getAtlas()"));
        assertTrue(source.contains("LabPbrNeutralTextures.normalView()"));
        assertTrue(source.contains("LabPbrNeutralTextures.materialView()"));
    }

    @Test
    void terrainNormalUsesMatchingLinearFilters() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/DefaultChunkRendererTextureBindMixin.java"));

        assertTrue(Pattern.compile(
                "FilterMode\\.LINEAR,\\s*FilterMode\\.LINEAR,\\s*false\\)", Pattern.DOTALL)
                .matcher(source).find());
        assertTrue(source.contains("FilterMode.NEAREST, FilterMode.NEAREST, true"),
                "material sampling must remain a separate mipmapped nearest sampler");
    }
}
