package dev.icehunter.fornax.pass.taa;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxSettings;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All three pure functions here are order-sensitive by nature (a rotated-grid or Halton sequence is
 * only "correct" for a specific frame index), so every assertion below pins an exact frame index
 * rather than asserting on "some frame in the cycle" -- deliberately avoiding the {@code Map.of}
 * iteration-order trap this codebase already documents elsewhere (see ARCHITECTURE.md's "known
 * laws"): these sequences are order-sensitive on purpose, not accidentally.
 */
class CameraJitterTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    @Test
    void fourTapSequenceUnchangedForTaa() {
        // Pins the original 4-tap rotated-grid values exactly -- this path must not move under the
        // aaMethod rework.
        Vector2f frame0 = CameraJitter.offsetNdcForFrame(0, WIDTH, HEIGHT);
        assertEquals(2.0f * -0.25f / WIDTH, frame0.x(), 1e-6f);
        assertEquals(2.0f * -0.25f / HEIGHT, frame0.y(), 1e-6f);

        Vector2f frame3 = CameraJitter.offsetNdcForFrame(3, WIDTH, HEIGHT);
        assertEquals(2.0f * 0.25f / WIDTH, frame3.x(), 1e-6f);
        assertEquals(2.0f * 0.25f / HEIGHT, frame3.y(), 1e-6f);

        // Cyclic: frame 4 repeats frame 0.
        Vector2f frame4 = CameraJitter.offsetNdcForFrame(4, WIDTH, HEIGHT);
        assertEquals(frame0.x(), frame4.x(), 1e-6f);
        assertEquals(frame0.y(), frame4.y(), 1e-6f);
    }

    @Test
    void haltonNdcIsDeterministicAndBoundedToOnePixel() {
        Vector2f a = CameraJitter.haltonNdc(0, 8, WIDTH, HEIGHT);
        Vector2f b = CameraJitter.haltonNdc(0, 8, WIDTH, HEIGHT);
        assertEquals(a.x(), b.x(), 0.0f);
        assertEquals(a.y(), b.y(), 0.0f);

        assertTrue(Math.abs(a.x()) <= 1.0f / WIDTH + 1e-6f);
        assertTrue(Math.abs(a.y()) <= 1.0f / HEIGHT + 1e-6f);
    }

    @Test
    void haltonNdcVariesAcrossFramesWithinTheSequence() {
        Vector2f frame0 = CameraJitter.haltonNdc(0, 8, WIDTH, HEIGHT);
        Vector2f frame1 = CameraJitter.haltonNdc(1, 8, WIDTH, HEIGHT);
        assertNotEquals(frame0.x(), frame1.x());
    }

    @Test
    void offAndSsaaDispatchToZeroOffset() {
        FornaxSettings off = new FornaxSettings();
        off.aaMethod = AaMethod.OFF;
        Vector2f offOffset = CameraJitter.offsetForMethod(off, 5, WIDTH, HEIGHT);
        assertEquals(0.0f, offOffset.x());
        assertEquals(0.0f, offOffset.y());

        FornaxSettings ssaa = new FornaxSettings();
        ssaa.aaMethod = AaMethod.SSAA;
        Vector2f ssaaOffset = CameraJitter.offsetForMethod(ssaa, 5, WIDTH, HEIGHT);
        assertEquals(0.0f, ssaaOffset.x());
        assertEquals(0.0f, ssaaOffset.y());
    }

    @Test
    void taauDispatchReturnsNonzeroHaltonOffset() {
        FornaxSettings taau = new FornaxSettings();
        taau.aaMethod = AaMethod.TAAU;

        Vector2f offset = CameraJitter.offsetForMethod(taau, 3, WIDTH, HEIGHT);

        assertTrue(offset.x() != 0.0f || offset.y() != 0.0f);
    }

    @Test
    void taaDispatchMatchesTheFourTapSequence() {
        FornaxSettings taa = new FornaxSettings();
        taa.aaMethod = AaMethod.TAA;

        Vector2f dispatched = CameraJitter.offsetForMethod(taa, 2, WIDTH, HEIGHT);
        Vector2f direct = CameraJitter.offsetNdcForFrame(2, WIDTH, HEIGHT);

        assertEquals(direct.x(), dispatched.x());
        assertEquals(direct.y(), dispatched.y());
    }
}
