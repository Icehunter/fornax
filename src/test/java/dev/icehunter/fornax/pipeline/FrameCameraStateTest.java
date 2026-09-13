package dev.icehunter.fornax.pipeline;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link FrameCameraState}'s round trip: a committed matrix reads back column-major, exactly
 * as {@link Matrix4f#get(float[])} would produce, and each commit advances the frame counter. */
class FrameCameraStateTest {
    @Test
    void commitRoundTripsAMatrixColumnMajor() {
        Matrix4f matrix = new Matrix4f()
                .translate(1.0f, 2.0f, 3.0f)
                .rotateY(0.5f);
        float[] expected = new float[16];
        matrix.get(expected);

        FrameCameraState.commit(matrix);

        assertArrayEquals(expected, FrameCameraState.invProjModelView(), 1e-6f);
    }

    @Test
    void invProjModelViewReturnsADefensiveCopy() {
        FrameCameraState.commit(new Matrix4f().identity());
        float[] first = FrameCameraState.invProjModelView();
        first[0] = 999.0f;
        float[] second = FrameCameraState.invProjModelView();
        assertEquals(1.0f, second[0], "mutating a returned array must not corrupt the held state");
    }

    @Test
    void eachCommitAdvancesTheFrameCounter() {
        FrameCameraState.commit(new Matrix4f().identity());
        long before = FrameCameraState.frame();
        FrameCameraState.commit(new Matrix4f().identity());
        assertTrue(FrameCameraState.frame() > before, "frame counter must strictly increase on commit");
    }
}
