package dev.icehunter.fornax.pipeline;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Where an INFINITELY DISTANT screen point was on the previous frame's screen -- the reprojection
 * the temporal reconstruct needs for every pixel that has no geometry, and therefore no motion
 * vector.
 *
 * <p><b>Why it is needed.</b> {@code gMotion} is a G-BUFFER ATTACHMENT ({@code
 * DeferredGeometryPipelines}' {@code RG16_FLOAT} "screen-space motion delta"), written only by
 * geometry passes. A sky pixel has no geometry, so it carries the CLEARED value -- zero motion --
 * and {@code reconstruct.fsh}'s {@code prevUv = texCoord - motion} then fetches history from the
 * same screen pixel, asserting the sky did not move while the camera turned underneath it. At the
 * default {@code taaBlendFactor} of 0.9 that recycles 90% of a stale pixel every frame. The
 * disocclusion guard cannot catch it either: both of its depth taps are the same cleared far value,
 * so their difference is exactly 0.0 and the invalid reprojection is silently accepted, precisely
 * where it is most wrong.
 *
 * <p><b>Why one 3x3 is the whole answer.</b> Content at effective infinity is a DIRECTION, not a
 * position: it has no translational parallax, so its screen motion is purely rotational and needs
 * no depth value at all. Writing a direction as the {@code w = 0} homogeneous point {@code (d, 0)},
 * a frame's clip position is {@code (P * MV) * (d, 0)} -- which touches only COLUMNS 0..2 of that
 * product, and only rows 0, 1 and 3 of it decide the screen position ({@code ndc = clip.xy /
 * clip.w}). That 3x3 submatrix, {@link #directionToScreen}, maps direction to homogeneous screen
 * point and is invertible for any real perspective projection. So the current frame's inverse
 * composed with the previous frame's forward map is a single 3x3 projective map from this frame's
 * NDC to the previous frame's NDC -- exact, not an approximation, and one {@code mat3} multiply per
 * fragment with no depth fetch.
 *
 * <p><b>Why rotation-only is correct here when the water motion vectors needed translation.</b>
 * Both model-view matrices carry rotation only -- the camera's translation lives in Sodium's
 * per-region offset (see {@link CameraMotionState}) -- which is exactly why {@code u_CameraDelta}
 * had to be added for water: a water surface is at a FINITE distance, so how far the eye travelled
 * changes where it lands on screen. Sky is not. Dropping column 3 here is not an omission working
 * around a missing lane; it is the physically correct model, and ADDING the camera delta would be a
 * bug -- it would slide the sky sideways every time the player walks, which is the exact defect the
 * retired parallax-curtain precipitation shipped with.
 *
 * <p><b>View bobbing cancels for free.</b> Minecraft's view bob lives in the PROJECTION matrix's
 * 4th column ({@code m30}/{@code m31}), a clip-space offset proportional to {@code w} -- i.e. a
 * parallax that is full strength at the near plane and zero at infinity. Column 3 is never read
 * here, so the bob is structurally excluded rather than approximately cancelled. (Contrast the
 * {@code z = 0.0001} far-unprojection trick {@code gbuffer_resolve.fsh} needs when it reconstructs a
 * sky ray through a full inverse view-projection: that only ASYMPTOTICALLY drops the same column.)
 *
 * <p><b>Un-jittered on both sides, deliberately.</b> {@code terrain.vsh} subtracts each frame's own
 * TAA jitter from that frame's NDC before differencing, so {@code gMotion} is expressed in a
 * jitter-FREE screen basis and the sky motion must match it or the two disagree. Feeding this the
 * rasterized (jittered) projections instead injects a half-pixel-per-axis error that oscillates on
 * the jitter sequence's own period -- permanent sky shimmer, measured at 0.71 px worst case at
 * 1920x1080 under the 4-tap TAA grid. Note this is a DIFFERENT question from {@code globals.glsl}'s
 * warning on {@code u_InvProjModelViewNoJitter}: that warning is about voxel/lattice ADDRESSING
 * versus inverting a RASTERIZED depth sample, which must stay jitter-consistent with the G-buffer.
 * Nothing here inverts a rasterized sample -- this maps output UV to output UV in the same basis
 * {@code gMotion} already uses.
 *
 * <p>Committed once per frame from {@code GraphRunner}'s finish-opaque, which runs before the
 * temporal reconstruct at {@code renderLevel} RETURN. Unlike {@link CameraMotionState} this keeps
 * its OWN one-frame history rather than reading {@link PreviousFrameCameraTransform}: the projection
 * it needs is the UN-JITTERED one ({@code CameraJitter.currentUnjitteredProjection()}), which that
 * snapshot does not hold.
 */
public final class SkyReprojection {
    /** The previous frame's un-jittered {@code projection * modelView}. */
    private static final Matrix4f previousViewProjection = new Matrix4f();

    /**
     * This frame's NDC-to-previous-NDC map for infinitely distant content, embedded in the
     * upper-left 3x3 of a {@link Matrix4f} purely so it can ride {@code Std140Builder.putMat4f} --
     * the one matrix upload path this codebase has already proven end to end. The shader reads it
     * back as {@code mat3(...)}. Identity until a second frame exists.
     */
    private static final Matrix4f current = new Matrix4f();

    private static boolean hasPrevious;

    private SkyReprojection() {
    }

    /**
     * Records this frame's un-jittered view-projection and derives the homography against the one
     * recorded last call. Must be called exactly once per frame, and before the temporal reconstruct
     * pass reads {@link #current()}.
     *
     * @param unjitteredProjection {@code CameraJitter.currentUnjitteredProjection()} -- captured
     *                             before {@code GameRendererMixin.fornax$setProjection} applies
     *                             jitter, so it is jitter-free regardless of the active AA method
     * @param modelView            the same per-frame model-view {@code PreviousFrameCameraTransform}
     *                             is committed with, i.e. the one {@code terrain.vsh} sees as
     *                             {@code u_ModelViewMatrix}
     */
    public static void commit(Matrix4fc unjitteredProjection, Matrix4fc modelView) {
        Matrix4f viewProjection = new Matrix4f(unjitteredProjection).mul(modelView);

        if (hasPrevious) {
            current.set(new Matrix4f(homography(viewProjection, previousViewProjection)));
        } else {
            current.identity();
            hasPrevious = true;
        }

        previousViewProjection.set(viewProjection);
    }

    /** This frame's map, ready for {@code Std140Builder.putMat4f} -- see the field's own comment. */
    public static Matrix4fc current() {
        return current;
    }

    /**
     * The 3x3 that takes a camera-relative DIRECTION to a homogeneous screen point: rows 0, 1 and 3
     * of {@code viewProjection}, columns 0, 1 and 2. Columns 0..2 are the only ones a {@code w = 0}
     * point reads, and rows 0/1/3 the only ones {@code clip.xy / clip.w} consumes.
     *
     * <p>JOML is column-major with {@code m<column><row>} accessors, and {@link Matrix3f}'s
     * constructor takes columns in the same order, so each triple below is one column of the
     * original with its row-2 entry dropped.
     */
    public static Matrix3f directionToScreen(Matrix4fc viewProjection) {
        return new Matrix3f(
                viewProjection.m00(), viewProjection.m01(), viewProjection.m03(),
                viewProjection.m10(), viewProjection.m11(), viewProjection.m13(),
                viewProjection.m20(), viewProjection.m21(), viewProjection.m23());
    }

    /**
     * The projective map from a point in {@code currentViewProjection}'s NDC to the same infinitely
     * distant direction's NDC under {@code previousViewProjection}. Apply as {@code (x', y', w') =
     * M * (ndc.x, ndc.y, 1)}, then {@code prevNdc = (x', y') / w'}.
     *
     * <p>Returns identity if the current frame's direction-to-screen map is singular. That cannot
     * happen for a real perspective projection (its 3x3 is a diagonal times a rotation), but an
     * all-zero projection is reachable before the first {@code renderLevel} ever captures one, and
     * inverting it would upload NaNs -- which the shader would turn into a NaN motion vector for
     * every sky pixel on screen. Identity degrades to exactly today's behaviour instead.
     */
    public static Matrix3f homography(Matrix4fc currentViewProjection, Matrix4fc previousViewProjection) {
        Matrix3f from = directionToScreen(currentViewProjection);
        if (from.determinant() == 0.0f || !Float.isFinite(from.determinant())) {
            return new Matrix3f();
        }

        return directionToScreen(previousViewProjection).mul(from.invert());
    }
}
