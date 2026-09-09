package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SourceInventoryGraphContractTest {
    @Test
    void metadataOnlyConsumerActivatesStreamingAndAcceptsEngineSizing() {
        for (String target : new String[]{"voxelSectionState", "voxelSourceSummary"}) {
            GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.%s]
                kind = "buffer"
                [targets.out]
                format = "rgba8"
                [[pass]]
                name = "diagnostic"
                type = "fullscreen"
                shader = "shaders/diagnostic.fsh"
                enabled_if = "SOURCE_VIEW != 0"
                inputs = ["%s"]
                outputs = ["out"]
                """.formatted(target, target)), "graph.toml");
            assertDoesNotThrow(() -> GraphValidator.validate(graph, dev.icehunter.fornax.pack.option.OptionScanner.scan(Map.of("shaders/diagnostic.fsh", "#define SOURCE_VIEW 0 //[0 1] compile \"Source View\"")), 64, 64));
            assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(graph, Map.of("SOURCE_VIEW", 1)));
            assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(graph, Map.of("SOURCE_VIEW", 0)));
        }
    }

    // The render thread/native registry cannot be constructed headlessly; pin its lifecycle seam.
    @Test
    void materialEpochIsSynchronizedBeforeConsumersRatherThanOnlyAfterDraws() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        assertTrue(source.contains("VoxelSourceSummary.setEnabled(registry.isEnabledBufferTarget("));
        assertTrue(source.contains("VoxelSourceSummary.setEnabled(false)"));
        int prepare = source.indexOf("public static void prepare(");
        int synchronize = source.indexOf("VoxelWindow.synchronizeSourceGeneration(", prepare);
        int runnerDefinition = source.indexOf("private static void ensureRunnersBuilt()");
        assertTrue(synchronize > prepare && synchronize < runnerDefinition,
                "material epoch must be synchronized in the per-frame prepare path");
    }
}
