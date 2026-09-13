package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the three {@link RayTracingMode} values and their declaration order. */
class RayTracingModeTest {
    @Test
    void hasExactlyOffAutoForceInThatOrder() {
        assertEquals(3, RayTracingMode.values().length);
        assertEquals(RayTracingMode.OFF, RayTracingMode.values()[0]);
        assertEquals(RayTracingMode.AUTO, RayTracingMode.values()[1]);
        assertEquals(RayTracingMode.FORCE, RayTracingMode.values()[2]);
    }
}
