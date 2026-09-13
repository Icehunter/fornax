package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RayTracedShadowSubscriptionTest {
    private static final String DECLARATION = """
            [ray_traced_shadows]
            enabled_if = "RT_ENABLED"
            distance_option = "u_TraceDistance"
            blocks_per_unit = 16
            """;
    private static final String CONSUMER = """
            [[pass]]
            name = "lighting"
            type = "fullscreen"
            shader = "lighting"
            inputs = ["rtTerrainShadowDepth"]
            outputs = ["builtin.output"]
            enabled_if = "LIGHTING"
            runtime_enabled_if = "dimension != 3"
            """;

    @Test void noDeclarationOrNoConsumerDoesNotSubscribe() {
        assertFalse(active(CONSUMER, 1, 1, 0));
        assertFalse(active(DECLARATION, 1, 1, 0));
        assertFalse(active(DECLARATION + CONSUMER.replace("rtTerrainShadowDepth", "rtSunDepth"), 1, 1, 0));
    }

    @Test void declarationAndActualConsumerMustBothBeEnabled() {
        String graph = DECLARATION + CONSUMER;
        assertTrue(active(graph, 1, 1, 0));
        assertFalse(active(graph, 0, 1, 0));
        assertFalse(active(graph, 1, 0, 0));
        assertFalse(active(graph, 1, 1, 3));
    }

    private static boolean active(String toml, int rt, int lighting, int dimension) {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml");
        return GraphRunner.activeShadowSubscriber(graph, Map.of("RT_ENABLED", rt, "LIGHTING", lighting),
                Map.of("dimension", dimension));
    }
}
