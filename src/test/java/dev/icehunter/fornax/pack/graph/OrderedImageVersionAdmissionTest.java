package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Buffer versioning must not silently admit image feedback with no graphics-to-compute WAR edge. */
class OrderedImageVersionAdmissionTest {
    @Test
    void imagePingPongAcrossAGraphicsReadKeepsItsExistingCycleRejection() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.x]
                format = "rgba16f"
                scale = 1.0
                storage = true
                [targets.y]
                format = "rgba16f"
                scale = 1.0
                storage = true
                [targets.color]
                format = "rgba16f"
                scale = 1.0
                [[pass]]
                name = "seed"
                type = "compute"
                shader = "compute/step.comp"
                outputs = ["x"]
                dispatch = [1, 1, 1]
                [[pass]]
                name = "copy"
                type = "compute"
                shader = "compute/step.comp"
                inputs = ["x"]
                outputs = ["y"]
                dispatch = [1, 1, 1]
                [[pass]]
                name = "inspect"
                type = "fullscreen"
                shader = "post/inspect.fsh"
                inputs = ["x"]
                outputs = ["color"]
                [[pass]]
                name = "rewrite"
                type = "compute"
                shader = "compute/step.comp"
                inputs = ["y"]
                outputs = ["x"]
                dispatch = [1, 1, 1]
                """), "graph.toml");
        assertTrue(GraphRunner.graphicsStreamComputePasses(graph, Map.of()).isEmpty(),
                "this change routes multi-writer buffers, not these image writers");
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(graph, Map.of(), 16, 16),
                "a raw image rewrite after a graphics sample has no same-frame reverse wait; buffer-version admission cannot silently allow it");
        assertTrue(error.reason().contains("cycle"), error.getMessage());
    }
}
