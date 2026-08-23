package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Holds the camera-transform and projection/model-view state that was actually used to render the
 * previous frame's opaque terrain, so this frame's motion-vector shader math can compute a
 * previous-frame clip-space position for the same vertex. Committed once per frame, after this
 * frame's own opaque terrain draws (see {@code SodiumWorldRenderer#drawChunkLayer}) — read by that
 * same frame's shader math, then overwritten to become "previous" for the next frame.
 * <p>
 * Mirrors {@link CameraTransform}'s own int-block + fractional-offset split by storing a
 * {@link CameraTransform} instance directly (it has no setters, so no defensive copy is needed) —
 * the whole point is to avoid reintroducing the float-precision seam issues that split exists to
 * prevent, rather than collapsing to a single combined previous view-projection matrix.
 * <p>
 * {@link Matrix4f}/{@link Matrix4fc} values ARE defensively copied on commit: the matrices this class
 * is handed (from {@code GameRendererMixin}'s `projection` field and vanilla's per-frame model-view)
 * are mutated in place every frame elsewhere in this codebase, so simply storing the reference would
 * make "previous" silently become "current" the instant the next frame's mutation happens.
 */
public final class PreviousFrameCameraTransform {
    private static volatile CameraTransform cameraTransform = new CameraTransform(0.0, 0.0, 0.0);
    private static volatile Matrix4f projection = new Matrix4f();
    private static volatile Matrix4f modelView = new Matrix4f();

    private PreviousFrameCameraTransform() {
    }

    public static CameraTransform getCameraTransform() {
        return cameraTransform;
    }

    public static Matrix4fc getProjection() {
        return projection;
    }

    public static Matrix4fc getModelView() {
        return modelView;
    }

    public static void commit(CameraTransform camera, Matrix4fc currentProjection, Matrix4fc currentModelView) {
        cameraTransform = camera;
        projection = new Matrix4f(currentProjection);
        modelView = new Matrix4f(currentModelView);
    }
}
