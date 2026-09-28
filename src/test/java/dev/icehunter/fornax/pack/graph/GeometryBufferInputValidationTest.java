package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.PackTomlLoader;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryBufferInputValidationTest {
    private static void validate(String text) {
        GraphValidator.validate(PackTomlLoader.loadGraph(new StringReader(text), "graph.toml"), Map.of(), 640, 480);
    }

    private static String graph(String slot, String inputs) {
        return """
                [targets.field]
                kind = "buffer"
                stride_bytes = 4
                count = 16
                [[pass]]
                name = "fill"
                type = "compute"
                shader = "shaders/fill.comp"
                dispatch = [1, 1, 1]
                inputs = []
                outputs = ["field"]
                [[pass]]
                name = "surface"
                type = "geometry"
                slot = "%s"
                program = "shaders/surface"
                inputs = [%s]
                """.formatted(slot, inputs);
    }

    @Test void terrainCanReadAnAppendedGenericBufferWithoutSpendingASampler() {
        assertDoesNotThrow(() -> validate(graph("terrain",
                "\"builtin.noise\",\"builtin.noise\",\"builtin.noise\",\"builtin.noise\","
                + "\"builtin.noise\",\"builtin.noise\",\"builtin.noise\",\"builtin.noise\",\"field\"")));
    }

    @Test void texturesCannotFollowTheBufferTail() {
        assertThrows(FornaxPackError.class, () -> validate(graph("terrain", "\"field\",\"builtin.noise\"")));
    }

    @Test void slotsWithoutABufferBinderRemainRejected() {
        assertThrows(FornaxPackError.class, () -> validate(graph("entities", "\"field\"")));
    }

    @Test void ninthBufferExceedsTheFixedDescriptorBank() {
        assertThrows(FornaxPackError.class, () -> validate(graph("terrain",
                "\"field\",\"field\",\"field\",\"field\",\"field\",\"field\",\"field\",\"field\",\"field\"")));
    }

    @Test void terrainBufferReadsParticipateInBothQueueDirections() {
        var spec = PackTomlLoader.loadGraph(new StringReader(graph("terrain", "\"field\"")), "graph.toml");
        var writer = spec.passes().getFirst();
        assertTrue((GraphRunner.computeGraphicsWaitStages(writer, spec, Map.of())
                & VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT) != 0);
        assertTrue((GraphRunner.computeGraphicsWaitStages(writer, spec, Map.of())
                & VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT) != 0);
        assertTrue(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(writer, spec, Map.of()));
    }

    @Test void terrainAloneKeepsItsVoxelInputsAllocatedAndHarvested() {
        var spec = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelOccupancy]
                kind = "buffer"
                [[pass]]
                name = "surface"
                type = "geometry"
                slot = "terrain"
                program = "shaders/surface"
                inputs = ["voxelOccupancy"]
                """), "graph.toml");
        assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(spec, Map.of()));
    }

    @Test void terrainEngineInputsUseTheExistingGraphicsUploadDrain() {
        var spec = PackTomlLoader.loadGraph(new StringReader("""
                [targets.waterActors]
                kind = "buffer"
                [[pass]]
                name = "surface"
                type = "geometry"
                slot = "terrain"
                program = "shaders/surface"
                inputs = ["waterActors"]
                """), "graph.toml");
        assertTrue(GraphRunner.anyEnabledPassReadsWaterActors(spec, Map.of()));
        assertTrue(GraphRunner.graphicsDrainableBufferTargets(spec, Map.of()).contains("waterActors"));
    }

    @Test void engineUploadedBufferIsFinalWithoutAPackWriter() {
        assertDoesNotThrow(() -> validate("""
                [targets.voxelOccupancy]
                kind = "buffer"
                [[pass]]
                name = "surface"
                type = "geometry"
                slot = "terrain"
                program = "shaders/surface"
                inputs = ["voxelOccupancy"]
                """));
    }

    @Test void computeRejectsAVirtualConsolidatedInputBeforeRunnerBuild() {
        assertThrows(FornaxPackError.class, () -> validate("""
                [targets.a]
                format = "rgba16f"
                scale = 1.0
                [targets.out]
                format = "rgba16f"
                scale = 1.0
                storage = true
                [[pass]]
                name = "layers"
                type = "consolidate"
                inputs = ["a"]
                outputs = ["virtualArray"]
                [[pass]]
                name = "gather"
                type = "compute"
                shader = "shaders/gather.comp"
                dispatch = [1, 1, 1]
                inputs = ["virtualArray"]
                outputs = ["out"]
                """));
    }
}
