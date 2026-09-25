package dev.icehunter.fornax.pipeline;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Per-frame camera focus distance and stillness, committed once per frame at the same
 * orchestration point as {@link CameraMotionState} and read by the aperture-jitter activation
 * logic. Focus is the crosshair hit distance smoothed with an exponential filter, frame-rate
 * dependent by choice like the water surface tracker (a time source here would couple the lane
 * to the pause menu). Stillness means the camera neither translated (CameraMotionState-style
 * deltas at zero within a small epsilon) nor rotated (this frame's model-view equals last
 * frame's within a small epsilon); any motion breaks it, which resets the aperture accumulation
 * upstream.
 */
public final class FocusState {
    /** Per-frame blend toward the raw distance -- about 0.3 s to 90 percent at 60 fps, the same
     * filter shape the water surface tracker uses. */
    private static final float SMOOTHING = 0.12f;
    /** Blocks per frame; below it the camera reads as parked rather than drifting. */
    private static final float POSITION_EPSILON = 1e-4f;
    /** Largest absolute element difference between this frame's and last frame's model-view
     * under which the camera counts as un-rotated. */
    private static final float VIEW_EPSILON = 1e-6f;

    /** Neutral mid-range focus before the first commit, matching the pack's own frame-one fallback. */
    private static float distanceBlocks = 16.0f;
    private static boolean still;
    private static int stillStreak;
    private static float frozenDistance = 16.0f;
    private static boolean hasPrevView;
    private static final Matrix4f prevView = new Matrix4f();

    private FocusState() {
    }

    public static void commit(float rawDistanceBlocks, float deltaX, float deltaY, float deltaZ,
            Matrix4fc modelView) {
        boolean positionStill = Math.abs(deltaX) < POSITION_EPSILON
                && Math.abs(deltaY) < POSITION_EPSILON
                && Math.abs(deltaZ) < POSITION_EPSILON;

        boolean rotationStill = hasPrevView;
        if (rotationStill) {
            outer:
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    if (Math.abs(modelView.get(c, r) - prevView.get(c, r)) >= VIEW_EPSILON) {
                        rotationStill = false;
                        break outer;
                    }
                }
            }
        }

        still = positionStill && rotationStill;
        stillStreak = still ? Math.min(stillStreak + 1, Integer.MAX_VALUE - 1) : 0;

        float clamped = Math.min(512.0f, Math.max(0.5f, rawDistanceBlocks));
        if (still) {
            // Parked: snap on the first still frame and hold. A focus that keeps easing
            // moves the aperture skew's pinned plane every frame, so even the crosshair
            // block displaces until the filter settles. The crosshair cannot move while
            // still, so the snap is stable.
            if (stillStreak == 1) {
                frozenDistance = clamped;
            }
            distanceBlocks = frozenDistance;
        } else {
            // Moving: log-space smoothing so a pull from near to sky and back feels equally
            // paced (the pack's own autofocus does the same).
            distanceBlocks = (float) Math.exp(
                    Math.log(distanceBlocks) * (1.0 - SMOOTHING) + Math.log(clamped) * SMOOTHING);
        }

        prevView.set(modelView);
        hasPrevView = true;
    }

    public static float distanceBlocks() {
        return distanceBlocks;
    }

    public static boolean still() {
        return still;
    }

    /** How many consecutive frames the camera has been still, 0 while moving. An activation
     * that waits on a short streak never starts jittering during the brief pauses of normal
     * play, which read as wobble. */
    public static int stillStreak() {
        return stillStreak;
    }

    /** Restores the pre-first-commit state for tests. */
    public static void reset() {
        distanceBlocks = 16.0f;
        still = false;
        stillStreak = 0;
        hasPrevView = false;
    }
}
