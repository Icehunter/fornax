package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live camera and GPU writes need a client, so the source contract pins their unconditional wiring. */
class CameraBiomeContractTest {
    @Test
    void exactBiomeDataAppendsOneVec4WithoutMovingExistingGlobals() throws IOException {
        String glsl = Files.readString(Path.of(
                "src/main/resources/assets/fornax/shaders/include/globals.glsl"));
        assertTrue(glsl.contains("vec4 u_CameraBiome;"), "the camera biome data lane must exist");
        assertTrue(glsl.indexOf("vec4 u_WorldBounds;") < glsl.indexOf("vec4 u_CameraBiome;"));
        String manager = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/UniformBufferManagerMixin.java"));
        // Existing 832-byte block plus one std140 vec4.
        assertTrue(manager.contains("return 848;"));
    }

    @Test
    void liveDataIsWrittenAtTheUnconditionalUniformTail() throws IOException {
        String writer = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/GlobalUniformsWriteMixin.java"));
        assertTrue(writer.contains("BiomeProbe.Values cameraBiome = BiomeProbe.read();"));
        assertTrue(writer.contains("builder.putVec4(cameraBiome.id(), cameraBiome.baseTemperature(),"));
        assertTrue(writer.contains("cameraBiome.localTemperature(), cameraBiome.downfall())"));
    }

    @Test
    void surfaceIdentityUsesTheSameMappingAndClearsOnPackTeardown() throws IOException {
        String upload = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/PrecipCoarseClipmapUpload.java"));
        assertTrue(upload.contains("WORD_BIOME_ID] = BiomeProbe.id(holder)"));
        String graph = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        String teardown = graph.substring(graph.indexOf("private static void closeCurrent()"));
        assertTrue(teardown.contains("PrecipCoarseClipmapUpload.reset();"),
                "a new pack mapping cannot inherit old surface IDs or partially refreshed rows");
    }
}
