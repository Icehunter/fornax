package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@code MipchainTargetIndexTest}: execution is keyed by pass name
 * ({@code consolidateRunners}), resolution by declared output name ({@code consolidateTargets}).
 * Builds a real {@link ConsolidateRunner}, not a stand-in: {@link ConsolidateRunner#build} touches
 * no GPU device, only {@link TargetFormat#parse}.
 */
class ConsolidateTargetIndexTest {
    @Test
    void indexesRunnerByDeclaredOutputWhenPassNameDiffers() {
        GraphSpec graph = PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.gAlbedo]
                format = "rgba16f"
                scale = 1.0

                [[pass]]
                name = "gbuf_consolidate"
                type = "consolidate"
                inputs = ["gAlbedo"]
                outputs = ["consolidated"]
                """), "graph.toml");

        ConsolidateRunner runner = ConsolidateRunner.build(graph.passes().get(0), graph.targets().get("gAlbedo"));
        Map<String, ConsolidateRunner> byOutput = GraphRunner.indexConsolidateTargets(
                graph, Map.of("gbuf_consolidate", runner));

        assertTrue(byOutput.containsKey("consolidated"));
        assertSame(runner, byOutput.get("consolidated"));
    }
}
