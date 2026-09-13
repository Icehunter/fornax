package dev.icehunter.fornax.pipeline;

import org.joml.Matrix4fc;

/**
 * Per-frame holder for the main camera's inverse projection*modelView matrix, the same value
 * {@code GlobalUniformsWriteMixin} computes for {@code u_InvProjModelView}, kept here as a plain
 * column-major {@code float[16]} for a non-shader consumer (the Metal ray-tracing pass, which needs
 * it as a constant-buffer upload rather than a GLSL uniform). The {@code EmitterFrameState}/{@code
 * ShadowFrameState} pattern exactly: committed once per frame by {@code GlobalUniformsWriteMixin}
 * right where it already builds this matrix for the shader upload, read for the rest of the frame
 * by whatever needs it next. Holds the identity matrix before the first commit, which is harmless
 * since nothing reads it before a frame has run.
 */
public final class FrameCameraState {
    private static volatile float[] invProjModelView = identity();
    private static volatile long frame;

    private FrameCameraState() {
    }

    private static float[] identity() {
        float[] values = new float[16];
        values[0] = 1.0f;
        values[5] = 1.0f;
        values[10] = 1.0f;
        values[15] = 1.0f;
        return values;
    }

    /** Copies {@code matrix} out (column-major, as {@link Matrix4fc#get(float[])}) and advances the
     * frame counter. The caller's matrix is not retained, so later mutation of it cannot corrupt
     * this holder. Render-thread only: {@code GlobalUniformsWriteMixin} is the sole caller, so {@code
     * frame++} is a plain (non-atomic) read-modify-write of a single writer's own counter. {@code
     * volatile} here is for cross-thread visibility to {@link #frame()}'s readers, not for making the
     * increment itself thread-safe against a second, concurrent writer that does not exist. */
    public static void commit(Matrix4fc matrix) {
        float[] values = new float[16];
        matrix.get(values);
        invProjModelView = values;
        frame++;
    }

    /** A defensive copy of the last committed matrix. Returning the live array would let a
     * caller's mutation corrupt state every other reader this frame also sees. */
    public static float[] invProjModelView() {
        return invProjModelView.clone();
    }

    /** Monotonically increasing count of {@link #commit} calls, so a consumer that runs off the
     * render thread can tell whether it has already seen the current frame's matrix. */
    public static long frame() {
        return frame;
    }
}
