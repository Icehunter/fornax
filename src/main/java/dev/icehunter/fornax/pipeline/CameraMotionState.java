package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

/**
 * How far the camera moved between the previous frame and this one, in blocks
 * ({@code thisFrame - previousFrame}), uploaded as {@code u_CameraDelta} in {@code u_Globals}.
 *
 * <p><b>Why a full-screen pass cannot derive this itself.</b> Every world position in this engine is
 * CAMERA-RELATIVE, and the camera's translation lives entirely in the per-region offset
 * ({@code DrawContextVKMixin.updateData} builds {@code u_RegionOffset} / {@code u_PrevRegionOffset}
 * as {@code regionOrigin - camera} for each frame's own camera) -- so {@code u_ModelViewMatrix} and
 * {@code u_PrevModelViewMatrix} carry rotation only, and the difference between them says nothing
 * about how far the eye travelled. A geometry pass gets the translation for free in its push
 * constants; a full-screen pass that reconstructs a position from a depth buffer has it nowhere.
 * Without this lane, reprojecting a depth sample into the previous frame is only possible for a
 * camera that did not move, which is exactly the case that never happens.
 *
 * <p>Committed once per frame from {@code SodiumWorldRendererOrchestrationMixin}'s opaque HEAD, at
 * the same point {@code EmitterFrameState} is committed and for the same reason: it must land before
 * the frame's first {@code UniformBufferManager.update()} writes {@code u_Globals}, and while
 * {@link PreviousFrameCameraTransform} still holds the PREVIOUS frame's camera (that class is
 * re-committed at the very end of {@code GraphRunner.finish()}, after this frame's terrain draws).
 * Pairing this frame's camera with that snapshot is the same pairing {@code
 * DrawContextVKMixin.updateData} makes for {@code u_PrevRegionOffset}, so a shader that adds this
 * delta to a reconstructed position lands on exactly the position {@code terrain.vsh} builds from
 * {@code u_PrevRegionOffset}.
 *
 * <p><b>Subtracted in double, stored as float.</b> {@code CameraTransform} keeps the raw
 * {@code double x/y/z} it was constructed from (javap on Sodium mc26.2-0.9.1's
 * {@code CameraTransform}: {@code public final double x; public final double y; public final double
 * z;}, assigned unrounded in the constructor alongside the int/frac split). Differencing there and
 * narrowing the SMALL result is exact to ~5e-9 blocks even at the world border; narrowing first and
 * differencing afterwards would not be -- a float holding a coordinate near 1e7 has a 1-block
 * quantum, which is more than ten times a whole frame of travel, so the delta would come out as a
 * staircase of zeroes and jumps and the reprojection would jitter by tens of pixels.
 */
public final class CameraMotionState {
    private static volatile float deltaX;
    private static volatile float deltaY;
    private static volatile float deltaZ;

    private CameraMotionState() {
    }

    /**
     * Records this frame's camera position and derives the delta against whatever
     * {@link PreviousFrameCameraTransform} currently holds. Must be called before the frame's first
     * {@code u_Globals} write and before {@code PreviousFrameCameraTransform.commit} overwrites the
     * snapshot -- see this class's javadoc.
     */
    public static void commit(double x, double y, double z) {
        CameraTransform previous = PreviousFrameCameraTransform.getCameraTransform();
        deltaX = (float) (x - previous.x);
        deltaY = (float) (y - previous.y);
        deltaZ = (float) (z - previous.z);
    }

    public static float deltaX() {
        return deltaX;
    }

    public static float deltaY() {
        return deltaY;
    }

    public static float deltaZ() {
        return deltaZ;
    }
}
