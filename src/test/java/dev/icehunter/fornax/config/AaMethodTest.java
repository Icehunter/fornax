package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins which {@link AaMethod}s resolve through pack-graph resources at end of frame. */
class AaMethodTest {
    @Test
    void needsGraphResourcesTrueForTaaTaauAndMetalfx() {
        assertTrue(AaMethod.TAA.needsGraphResources());
        assertTrue(AaMethod.TAAU.needsGraphResources());
        assertTrue(AaMethod.METALFX.needsGraphResources());
    }

    @Test
    void needsGraphResourcesFalseForOffAndSsaa() {
        assertFalse(AaMethod.OFF.needsGraphResources());
        assertFalse(AaMethod.SSAA.needsGraphResources());
    }
}
