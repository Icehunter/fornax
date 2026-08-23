package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MipchainTargetIndexTest {
    @Test
    void indexesRunnerByDeclaredTargetWhenPassNameDiffers() {
        GraphSpec graph = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.waterEnvironment]
                format = "rgba16f"
                width = 128
                height = 128

                [[pass]]
                name = "water_environment_mips"
                type = "mipchain"
                target = "waterEnvironment"
                inputs = ["seed"]
                """), "graph.toml");

        Map<String, String> byTarget = GraphRunner.indexMipchainTargets(
                graph, Map.of("water_environment_mips", "runner"));

        assertEquals(Map.of("waterEnvironment", "runner"), byTarget);
    }
}
