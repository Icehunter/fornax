package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.EnabledIfExpr;
import dev.icehunter.fornax.util.DimensionId;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code runtime_enabled_if} parses, and its one name means what the shader means by it.
 *
 * <p>The gate and {@code u_WorldBounds.w} both come from {@link DimensionId}. Let them drift and a
 * pack gates a pass on one dimension and shades it as another, which nothing else would catch.
 */
class RuntimePassGateTest {
    @Test
    void theGateParsesAndDefaultsToNull() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [[pass]]
                name = "gated"
                type = "fullscreen"
                shader = "shaders/post/a.fsh"
                outputs = ["builtin.output"]
                runtime_enabled_if = "dimension != 3"

                [[pass]]
                name = "ungated"
                type = "fullscreen"
                shader = "shaders/post/b.fsh"
                outputs = ["builtin.output"]
                """), "graph.toml");

        PassSpec gated = graph.passes().get(0);
        PassSpec ungated = graph.passes().get(1);
        assertEquals("dimension != 3", gated.runtimeEnabledIf());
        assertNull(ungated.runtimeEnabledIf(), "a pass with no gate must carry none, not a default");

        EnabledIfExpr expr = EnabledIfExpr.parse(gated.runtimeEnabledIf());
        assertFalse(expr.evaluate(Map.of("dimension", DimensionId.END)),
                "the gate must shut in the End");
        assertTrue(expr.evaluate(Map.of("dimension", DimensionId.OVERWORLD)));
        assertTrue(expr.evaluate(Map.of("dimension", DimensionId.NETHER)));
    }

    /**
     * The numbers a pack writes in a gate are the numbers its shaders read. They are published to
     * packs, so a renumber would move every gate to another dimension without a word.
     */
    @Test
    void theDimensionNumbersArePublished() {
        assertEquals(0, DimensionId.UNKNOWN);
        assertEquals(1, DimensionId.OVERWORLD);
        assertEquals(2, DimensionId.NETHER);
        assertEquals(3, DimensionId.END);
    }
}
