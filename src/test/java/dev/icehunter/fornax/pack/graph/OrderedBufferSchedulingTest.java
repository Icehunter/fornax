package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Queue handoffs follow transitive graphics ownership and scratch rewrites. */
class OrderedBufferSchedulingTest {
    private static GraphSpec graph(String passes) {
        return PackTomlLoader.loadGraph(new StringReader("""
                [targets.x]
                kind = "buffer"
                stride_bytes = 16
                count = 16
                [targets.y]
                kind = "buffer"
                stride_bytes = 16
                count = 16
                [targets.z]
                kind = "buffer"
                stride_bytes = 16
                count = 16
                [targets.color]
                format = "rgba16f"
                scale = 1.0
                """ + passes), "graph.toml");
    }

    private static String compute(String name, String inputs, String output) {
        return """
                [[pass]]
                name = "%s"
                type = "compute"
                shader = "compute/step.comp"
                inputs = [%s]
                outputs = ["%s"]
                dispatch = [1, 1, 1]
                """.formatted(name, inputs, output);
    }

    @Test
    void rawProducerWaitsForATransitiveGraphicsComputeConsumer() {
        GraphSpec graph = graph(compute("independent", "", "z")
                + compute("graphics_seed", "\"builtin.depth\"", "x")
                + compute("graphics_next", "\"x\"", "y")
                + compute("join", "\"y\", \"z\"", "color"));
        assertEquals(Set.of("graphics_seed", "graphics_next", "join"),
                GraphRunner.graphicsStreamComputePasses(graph, Map.of()));
        long stages = GraphRunner.computeGraphicsWaitStages(graph.passes().getFirst(), graph, Map.of());
        assertTrue((stages & VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT) != 0,
                "the join reads a graphics-derived buffer and must wait for the independent raw producer");
        assertTrue(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(
                graph.passes().getFirst(), graph, Map.of()),
                "the raw producer cannot reuse z while last frame's graphics join still reads it");
    }

    @Test
    void scratchReadModifyWriteConsumerIsAGraphicsConsumer() {
        GraphSpec graph = graph(compute("seed", "", "x")
                + compute("update", "\"x\"", "x"));
        assertEquals(Set.of("seed", "update"), GraphRunner.graphicsStreamComputePasses(graph, Map.of()),
                "every writer shares the graphics queue, including the output-only initializer");
    }

    @Test
    void pingPongRewriteAfterAFragmentReadStaysOnTheGraphicsSide() {
        GraphSpec graph = graph(compute("seed", "", "x")
                + compute("copy", "\"x\"", "y") + """
                [[pass]]
                name = "inspect"
                type = "fullscreen"
                shader = "post/inspect.fsh"
                inputs = ["x"]
                outputs = ["color"]
                """ + compute("rewrite", "\"y\"", "x"));
        assertEquals(Set.of("seed", "copy", "rewrite"), GraphRunner.graphicsStreamComputePasses(graph, Map.of()),
                "the fragment read and all buffer versions share graphics ordering without self overlap");
    }

    @Test
    void aCompileDisabledScratchWriterDoesNotMoveIndependentComputeWork() {
        GraphSpec graph = graph(compute("seed", "", "x")
                + compute("optional_rewrite", "", "x") + "enabled_if = \"REWRITE\"\n"
                + compute("consumer", "\"x\"", "y"));
        assertEquals(Set.of(), GraphRunner.graphicsStreamComputePasses(graph, Map.of("REWRITE", 0)));
        assertEquals(Set.of("seed", "optional_rewrite", "consumer"),
                GraphRunner.graphicsStreamComputePasses(graph, Map.of("REWRITE", 1)));
    }

}
