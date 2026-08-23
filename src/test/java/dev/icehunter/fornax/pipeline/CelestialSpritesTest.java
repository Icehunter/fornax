package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CelestialSpritesTest {
    @Test
    void uncapturedStateReturnsZeroRectsAndNullViews() {
        CelestialSprites.clear();
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f}, CelestialSprites.moonPhaseRect(3));
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f}, CelestialSprites.sunRect());
        assertNull(CelestialSprites.atlasView());
    }

    @Test
    void perPhaseRectsAreStoredAndClamped() {
        float[][] moonRects = new float[8][];
        for (int i = 0; i < 8; i++) {
            moonRects[i] = new float[] {i * 0.1f, 0.0f, i * 0.1f + 0.05f, 0.5f};
        }
        CelestialSprites.captureForTest(new float[] {0.9f, 0.9f, 1f, 1f}, moonRects);
        assertArrayEquals(new float[] {0.9f, 0.9f, 1f, 1f}, CelestialSprites.sunRect(), 1e-6f);
        assertArrayEquals(moonRects[0], CelestialSprites.moonPhaseRect(0), 1e-6f);
        assertArrayEquals(moonRects[7], CelestialSprites.moonPhaseRect(7), 1e-6f);
        // Out-of-range phase indices clamp instead of throwing (render thread must never
        // crash on odd attribute data).
        assertArrayEquals(moonRects[7], CelestialSprites.moonPhaseRect(11), 1e-6f);
        assertArrayEquals(moonRects[0], CelestialSprites.moonPhaseRect(-1), 1e-6f);
    }
}
