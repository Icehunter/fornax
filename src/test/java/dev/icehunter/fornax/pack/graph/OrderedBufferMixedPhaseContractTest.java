package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless resource planning plus recording-site contracts. These do not execute a GPU or
 * prove driver synchronization; they reject the missing edges discovered in ordered routing. */
class OrderedBufferMixedPhaseContractTest {
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
    void anEarlyRawProducerMustSignalBeforeTheGraphicsPreOpaqueConsumer() throws Exception {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
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
                """ + compute("light_inject", "", "x")
                + compute("light_propagate", "\"x\"", "x")
                + compute("light_list_reset", "", "y")
                + compute("light_list_build", "\"x\", \"y\"", "z")), "graph.toml");
        GraphValidator.validate(graph, Map.of(), 16, 16);
        assertEquals(Set.of("light_inject", "light_propagate", "light_list_build"),
                GraphRunner.graphicsStreamComputePasses(graph, Map.of()));
        assertTrue(graph.passes().stream().allMatch(GraphRunner::isPreOpaqueLightingComputePass));
        long resetStages = GraphRunner.computeGraphicsWaitStages(graph.passes().get(2), graph, Map.of());
        assertNotEquals(0, resetStages & VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        int start = source.indexOf("private static void runPreOpaqueLightingCompute(");
        String body = source.substring(start, source.indexOf("\n    }", start));
        assertFalse(body.contains("if (finalProducer)"),
                "only signaling the last runner loses reset's edge: that last runner is graphics and never signals a raw-compute semaphore");
        assertTrue(body.contains("runner.graphicsStream()") && body.contains("graphicsWaitStagesFor(p, pack.graph())"),
                "each raw pre-opaque producer must hand off its own actual consumer stages before the next graphics dispatch");
        assertTrue(body.contains("computeDispatchOverride(p), false, graphicsWaitStages, null)"),
                "the per-producer dependency must reach the immediate handoff call, not remain an unused calculation");
    }

    @Test
    void graphicsStreamBufferWritesAreReleasedToVertexReaders() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int start = source.indexOf("private long runInGraphicsStream(");
        String body = source.substring(start, source.indexOf("private long publishGraphicsInputs()", start));
        int releaseStart = body.indexOf("recordComputeWriteReleaseBarrier(cmd, stack,");
        String release = body.substring(releaseStart, body.indexOf(";", releaseStart));
        assertTrue(release.contains("VK13.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT")
                        || release.contains("VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT"),
                "graphics-routed scratch buffers can feed particle and terrain vertex stages; fragment-only shader scope does not cover those reads");
    }
}
