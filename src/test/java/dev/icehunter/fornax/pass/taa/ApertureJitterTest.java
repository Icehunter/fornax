package dev.icehunter.fornax.pass.taa;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApertureJitterTest {

    private Matrix4f persp() {
        return new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
    }

    @Test
    void focusPlanePointsProjectExactlyWhereTheUnjitteredCameraPutThem() {
        Matrix4f base = persp();
        Matrix4f jittered = new Matrix4f(base);
        ApertureJitter.applyOffset(jittered, 0.03f, -0.02f, 8.0f);
        for (float[] p : new float[][]{{1.2f, -0.7f}, {-2.5f, 0.9f}}) {
            Vector4f a = base.transform(new Vector4f(p[0], p[1], -8.0f, 1.0f));
            Vector4f b = jittered.transform(new Vector4f(p[0], p[1], -8.0f, 1.0f));
            assertEquals(a.x / a.w, b.x / b.w, 1e-5);
            assertEquals(a.y / a.w, b.y / b.w, 1e-5);
        }
    }

    @Test
    void offPlanePointsDisplaceByTheThinLensFormula() {
        Matrix4f base = persp();
        Matrix4f jittered = new Matrix4f(base);
        ApertureJitter.applyOffset(jittered, 0.05f, 0.0f, 8.0f);
        Vector4f a = base.transform(new Vector4f(0.4f, 0.3f, -16.0f, 1.0f));
        Vector4f b = jittered.transform(new Vector4f(0.4f, 0.3f, -16.0f, 1.0f));
        assertEquals(base.m00() * 0.05f * (1.0f / 8.0f - 1.0f / 16.0f), b.x / b.w - a.x / a.w, 1e-5);
        assertEquals(a.y / a.w, b.y / b.w, 1e-6);
    }

    @Test
    void theApertureRadiusMatchesThePacksCircleOfConfusion() {
        float r = ApertureJitter.apertureRadiusBlocks(50.0f, 2.0f, 0.8f);
        assertEquals(2500.0f / (36000.0f * 2.0f * 0.8f), r, 1e-6);
        float doubled = ApertureJitter.apertureRadiusBlocks(100.0f, 2.0f, 0.8f);
        assertEquals(4.0f * r, doubled, 1e-6);
    }

    @Test
    void theDiscSequenceIsDeterministicAndStaysInsideTheAperture() {
        for (int i = 0; i < 200; i++) {
            Vector2f a = ApertureJitter.offsetForFrame(i, 0.05f);
            Vector2f b = ApertureJitter.offsetForFrame(i, 0.05f);
            assertEquals(a.x, b.x, 1e-6);
            assertEquals(a.y, b.y, 1e-6);
            assertTrue(Math.hypot(a.x, a.y) <= 0.05f + 1e-6);
        }
        Vector2f first = ApertureJitter.offsetForFrame(0, 0.05f);
        Vector2f secondCycle = ApertureJitter.offsetForFrame(2 * 64, 0.05f);
        assertNotEquals(first.x, secondCycle.x, 1e-6);
    }

    @Test
    void antitheticPairsKeepEveryPrefixMeanNearZeroSoTheAverageDoesNotOrbit() {
        float radius = 0.05f;
        float sumX = 0.0f;
        float sumY = 0.0f;
        for (int k = 0; k < 128; k++) {
            Vector2f o = ApertureJitter.offsetForFrame(k, radius);
            if ((k & 1) == 1) {
                Vector2f even = ApertureJitter.offsetForFrame(k - 1, radius);
                assertEquals(-even.x, o.x, 1e-7);
                assertEquals(-even.y, o.y, 1e-7);
            }
            sumX += o.x;
            sumY += o.y;
            int count = k + 1;
            float bound = (k % 2 == 1) ? 1e-6f : radius / count + 1e-6f;
            assertTrue(Math.hypot(sumX / count, sumY / count) <= bound,
                    "prefix " + count + " mean drifted");
        }
    }

    @Test
    void goingInactiveResetsTheFrameIndexAndTheNextStillStartsFresh() {
        ApertureJitter.configure(false, 0.0f, 0.0f);
        ApertureJitter.configure(true, 0.05f, 8.0f);
        assertTrue(ApertureJitter.active());
        assertEquals(0, ApertureJitter.frameIndex());
        ApertureJitter.configure(true, 0.05f, 8.0f);
        ApertureJitter.configure(true, 0.05f, 8.0f);
        assertEquals(2, ApertureJitter.frameIndex());
        ApertureJitter.configure(false, 0.0f, 0.0f);
        assertFalse(ApertureJitter.active());
        assertEquals(0, ApertureJitter.frameIndex());
        ApertureJitter.configure(true, 0.05f, 8.0f);
        assertEquals(0, ApertureJitter.frameIndex());
    }

    @Test
    void applyToIsANoOpWhileInactive() {
        ApertureJitter.configure(false, 0.0f, 0.0f);
        Matrix4f p = persp();
        Matrix4f copy = new Matrix4f(p);
        ApertureJitter.applyTo(p);
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(copy.get(col, row), p.get(col, row));
            }
        }
    }
}
