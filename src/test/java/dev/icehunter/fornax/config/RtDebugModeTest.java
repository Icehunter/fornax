package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the seven {@link RtDebugMode} values, their declaration order, and the numeric mode each
 * one maps to for {@code rt_debug.metal}'s own {@code RtDebugConstants.mode} field. */
class RtDebugModeTest {
    @Test
    void hasExactlySevenValuesInDeclarationOrder() {
        assertEquals(7, RtDebugMode.values().length);
        assertEquals(RtDebugMode.OFF, RtDebugMode.values()[0]);
        assertEquals(RtDebugMode.HIT_MISS, RtDebugMode.values()[1]);
        assertEquals(RtDebugMode.DISTANCE, RtDebugMode.values()[2]);
        assertEquals(RtDebugMode.NORMAL, RtDebugMode.values()[3]);
        assertEquals(RtDebugMode.INSTANCE_ID, RtDebugMode.values()[4]);
        assertEquals(RtDebugMode.PRIMITIVE_ID, RtDebugMode.values()[5]);
        assertEquals(RtDebugMode.RAY_DIRECTION, RtDebugMode.values()[6]);
    }

    @Test
    void shaderModeMatchesRtDebugMetalsModeConstants() {
        // rt_debug.metal: MODE_HIT_MISS = 0, MODE_DISTANCE = 1, MODE_NORMAL = 2,
        // MODE_INSTANCE_ID = 3, MODE_PRIMITIVE_ID = 4, MODE_RAY_DIRECTION = 5.
        assertEquals(0, RtDebugMode.HIT_MISS.shaderMode());
        assertEquals(1, RtDebugMode.DISTANCE.shaderMode());
        assertEquals(2, RtDebugMode.NORMAL.shaderMode());
        assertEquals(3, RtDebugMode.INSTANCE_ID.shaderMode());
        assertEquals(4, RtDebugMode.PRIMITIVE_ID.shaderMode());
        assertEquals(5, RtDebugMode.RAY_DIRECTION.shaderMode());
    }

    @Test
    void offHasNoShaderModeSinceTheDispatchIsSkippedInstead() {
        assertThrows(IllegalStateException.class, RtDebugMode.OFF::shaderMode);
    }
}
