package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link SkyProbe}'s pure conversions. {@code SkyProbe.read()} itself dereferences
 * {@code Minecraft.getInstance()} and has no headless harness -- which is exactly why the
 * conversions are split out as static functions rather than inlined into it. These assertions
 * carry over from the coverage {@code SkyFrameStateTest} had before the data moved.
 */
class SkyProbeTest {

    @Test
    void argbDecodesToUnitRangeFloats() {
        // 0xFF8040C0: R=0x80/255, G=0x40/255, B=0xC0/255 (alpha ignored)
        assertEquals(0x80 / 255.0f, SkyProbe.red(0xFF8040C0), 1e-6f);
        assertEquals(0x40 / 255.0f, SkyProbe.green(0xFF8040C0), 1e-6f);
        assertEquals(0xC0 / 255.0f, SkyProbe.blue(0xFF8040C0), 1e-6f);
        assertEquals(1.0f, SkyProbe.red(0xFFFF0000), 1e-6f);
        assertEquals(0.0f, SkyProbe.green(0xFFFF0000), 1e-6f);
    }

    @Test
    void argbIgnoresAlphaEntirely() {
        // The sky/sunrise attributes report alpha in the high byte; the uniform lanes carry only
        // rgb, and a fully-transparent colour must decode identically to an opaque one.
        assertEquals(SkyProbe.red(0xFF3366CC), SkyProbe.red(0x003366CC), 0.0f);
        assertEquals(SkyProbe.blue(0xFF3366CC), SkyProbe.blue(0x003366CC), 0.0f);
    }

    @Test
    void sunDirectionMatchesAngleConvention() {
        // Same convention as SunDirection's closed form: angle a -> (-sin a, cos a, 0).
        // Noon (a = 0): straight up.
        assertEquals(0.0f, SkyProbe.sunDirX(0.0f), 1e-6f);
        assertEquals(1.0f, SkyProbe.sunDirY(0.0f), 1e-6f);
        // Quarter turn (a = pi/2): sun at the horizon, -X side.
        assertEquals(-1.0f, SkyProbe.sunDirX((float) (Math.PI / 2)), 1e-6f);
        assertEquals(0.0f, SkyProbe.sunDirY((float) (Math.PI / 2)), 1e-6f);
    }

    @Test
    void sunDirectionGoesNegativeAtNight() {
        // The property light_inject.comp's `clamp(sunDir.y, 0, 1)` gate depends on, and the whole
        // reason that pass reads the TRUE sun rather than the active shadow-casting light.
        assertTrue(SkyProbe.sunDirY((float) Math.PI) < 0.0f);
    }

    @Test
    void sunDirectionIsUnitLength() {
        for (float angle = 0.0f; angle < 6.28f; angle += 0.37f) {
            float x = SkyProbe.sunDirX(angle);
            float y = SkyProbe.sunDirY(angle);
            assertEquals(1.0f, (float) Math.sqrt(x * x + y * y), 1e-6f);
        }
    }

    @Test
    void zeroValuesAreAllZero() {
        // The garbage-VRAM contract: no level to probe must still produce defined uniform bytes.
        SkyProbe.Values z = SkyProbe.ZERO;
        assertEquals(0.0f, z.skyR(), 0.0f);
        assertEquals(0.0f, z.skyG(), 0.0f);
        assertEquals(0.0f, z.skyB(), 0.0f);
        assertEquals(0.0f, z.starBrightness(), 0.0f);
        assertEquals(0.0f, z.sunDirY(), 0.0f);
        assertEquals(0.0f, z.moonPhase(), 0.0f);
        assertEquals(0.0f, z.rainLevel(), 0.0f);
        assertEquals(0.0f, z.sunAngleRadians(), 0.0f);
    }
}
