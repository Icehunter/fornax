package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpaqueDepthTest {
    @Test
    void builtinNameIsDepthOpaque() {
        assertEquals("builtin.depth_opaque", OpaqueDepth.NAME);
    }

    @Test
    void clearValueIsReversedZFar() {
        // Reversed-Z law: far == 0.0. Sampling depth_opaque before its first capture must read
        // "far", never MoltenVK garbage.
        assertEquals(0.0f, OpaqueDepth.FAR_CLEAR);
    }

    @Test
    void validatorBuiltinsContainsDepthOpaque() {
        assertTrue(dev.icehunter.fornax.pack.graph.GraphValidator.BUILTINS.contains("builtin.depth_opaque"));
    }
}
