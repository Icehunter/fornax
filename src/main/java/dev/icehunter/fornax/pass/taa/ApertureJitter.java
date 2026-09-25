package dev.icehunter.fornax.pass.taa;

import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * Per-frame camera offsets on a thin-lens aperture disc, applied to the shared projection matrix
 * while the camera is still, so averaging the frames converges to true depth of field for photo
 * stills. The eye offset is folded into the projection as a camera-space translate; a skew keeps
 * the focus plane pixel-fixed, so the focused subject never swims while everything off-plane
 * orbits by its own circle of confusion. Applied after (and mutually exclusive with) the TAA
 * jitter in {@code GameRendererMixin.fornax$setProjection}; the accumulation lives in the pack's
 * {@code dof_accumulate} pass, which receives the frame index and the active flag through its
 * pass params.
 */
public final class ApertureJitter {
    /** pi times (3 minus sqrt 5); Vogel's sunflower model gives an even disc fill at any prefix length. */
    public static final float GOLDEN_ANGLE = 2.39996323f;

    /** One full cycle of distinct aperture points; beyond it the sequence repeats with a rotated
     * cycle, which only holds the converged average steady. */
    public static final int DISC_POINTS = 64;

    private static boolean active;
    private static int frameIndex;
    private static float apertureRadiusBlocks;
    private static float focusDistanceBlocks;

    private ApertureJitter() {
    }

    /**
     * Eye offset for a given frame on the aperture disc. Pure and deterministic. Antithetic:
     * every even frame's point is followed by its exact negative, so the mean of ANY
     * even-length prefix is exactly zero and an odd prefix is off by at most one point over
     * its length -- the displayed running average holds still from the second frame instead
     * of orbiting with the spiral while it converges. The disc integral is unchanged: the
     * disc is symmetric, so the mirrored points sample it just as evenly.
     */
    public static Vector2f offsetForFrame(int index, float radiusBlocks) {
        int pair = index >> 1;
        float sign = (index & 1) == 0 ? 1.0f : -1.0f;
        int j = Math.floorMod(pair, DISC_POINTS);
        float r = radiusBlocks * (float) Math.sqrt((j + 0.5) / DISC_POINTS);
        // Later cycles rotate by half the golden angle so repeats do not land on identical points.
        float a = j * GOLDEN_ANGLE + Math.floorDiv(pair, DISC_POINTS) * GOLDEN_ANGLE * 0.5f;
        return new Vector2f(sign * r * (float) Math.cos(a), sign * r * (float) Math.sin(a));
    }

    /**
     * The eye-offset radius that makes the accumulated blur match the pack's own thin-lens circle
     * of confusion (f^2/(2N) millimetres of confusion on a 36 mm frame mapped to the output
     * width), so the still refines seamlessly out of the post-process blur with no size jump. One
     * block is one metre; the 36000 folds the 36 mm frame and the millimetre-to-metre conversion.
     */
    public static float apertureRadiusBlocks(float focalLengthMm, float fStop, float projectionM00) {
        return focalLengthMm * focalLengthMm
                / (36000.0f * Math.max(fStop, 0.1f) * Math.max(projectionM00, 1e-4f));
    }

    /**
     * Folds an eye offset (ox, oy) in camera space into the projection: a right-multiplied
     * translate moves the eye, then m20/m21 (JOML column-row naming: the view-Z coefficients of
     * clip x and y) take a skew so a point at the focus distance projects exactly where the
     * unjittered camera put it. Derivation in one line: clip.x at view depth -F is
     * m00*(X - ox) + m20*(-F); equality with m00*X forces m20 -= m00*ox/F.
     */
    public static void applyOffset(Matrix4f projection, float ox, float oy, float focusBlocks) {
        float f = Math.max(focusBlocks, 0.5f);
        projection.translate(-ox, -oy, 0.0f);
        projection.m20(projection.m20() - projection.m00() * ox / f);
        projection.m21(projection.m21() - projection.m11() * oy / f);
    }

    /**
     * Committed once per frame before the projection hook runs. Going inactive resets the frame
     * index so the next still starts a fresh cycle; while active the index advances by one per
     * call.
     */
    public static void configure(boolean nowActive, float radiusBlocks, float focusBlocks) {
        if (!nowActive) {
            active = false;
            frameIndex = 0;
            return;
        }
        if (!active) {
            frameIndex = 0;
        } else {
            frameIndex++;
        }
        active = true;
        apertureRadiusBlocks = radiusBlocks;
        focusDistanceBlocks = focusBlocks;
    }

    public static boolean active() {
        return active;
    }

    public static int frameIndex() {
        return frameIndex;
    }

    /** This frame's offset onto the shared matrix; no-op when inactive. */
    public static void applyTo(Matrix4f projection) {
        if (!active) {
            return;
        }
        Vector2f o = offsetForFrame(frameIndex, apertureRadiusBlocks);
        applyOffset(projection, o.x, o.y, focusDistanceBlocks);
    }
}
