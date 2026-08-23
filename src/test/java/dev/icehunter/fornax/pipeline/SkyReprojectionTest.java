package dev.icehunter.fornax.pipeline;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sky reprojection maths, in pixels at 1920x1080, with no GPU involved.
 *
 * <p>Ground truth is built the long way round for each sample: take a screen point, recover the
 * camera-relative DIRECTION the current frame's projection puts there, then project that direction
 * through the previous frame's projection as a {@code w = 0} homogeneous point. {@link
 * SkyReprojection#homography} has to land on the same place with one 3x3 multiply.
 *
 * <p>The comparison the whole round exists for is {@link #todaysZeroMotionErrorInPixels}: with
 * {@code gMotion} at its cleared zero on sky, {@code reconstruct.fsh} reprojects a sky pixel to
 * ITSELF, and that test measures how far wrong that is per frame.
 */
class SkyReprojectionTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final float FOV_DEGREES = 70.0f;
    private static final float NEAR = 0.05f;
    /** The user's reported frame rate in the night scene the crosshatch ghost was filed against. */
    private static final float FPS = 119.0f;

    /**
     * Reversed-Z perspective (near -> 1.0, far -> 0.0), matching the engine's depth convention.
     * Only rows 0, 1 and 3 matter to any assertion here -- the depth row is present so the matrix
     * is a realistic 4x4 rather than a contrivance the homography could accidentally depend on.
     */
    private static Matrix4f projection() {
        float f = (float) (1.0 / Math.tan(Math.toRadians(FOV_DEGREES) * 0.5));
        Matrix4f p = new Matrix4f().zero();
        p.m00(f / ((float) WIDTH / HEIGHT));
        p.m11(f);
        p.m22(0.0f);
        p.m32(NEAR);
        p.m23(-1.0f);
        return p;
    }

    /**
     * TAA's per-frame jitter as the engine applies it: {@code translateLocal(jx, jy, 0)}, i.e. a
     * pure NDC translation of everything the matrix projects.
     */
    private static Matrix4f jittered(Matrix4f p, float pixelX, float pixelY) {
        return new Matrix4f(p).translateLocal(2.0f * pixelX / WIDTH, 2.0f * pixelY / HEIGHT, 0.0f);
    }

    /**
     * Minecraft's view bobbing, which lives in the PROJECTION's 4th column (m30/m31) -- a clip
     * offset proportional to w, so full strength at the near plane and zero at infinity. Values are
     * the magnitudes measured on a straight walk in this project's own instrumentation round.
     */
    private static Matrix4f bobbed(Matrix4f p, float x, float y) {
        return new Matrix4f(p).m30(p.m30() + x).m31(p.m31() + y);
    }

    /** Rotation-only model-view, the engine's convention (camera translation is in the region offset). */
    private static Matrix4f rotation(float yawDegrees, float pitchDegrees) {
        return new Matrix4f().rotateX((float) Math.toRadians(pitchDegrees))
                .rotateY((float) Math.toRadians(yawDegrees));
    }

    /** Ground truth: the previous frame's screen UV of the infinitely distant point seen at {@code uv}. */
    private static Vector2f truePreviousUv(Matrix4f currentViewProjection, Matrix4f previousViewProjection,
            Vector2f uv) {
        Vector3f ndc = new Vector3f(uv.x * 2.0f - 1.0f, uv.y * 2.0f - 1.0f, 1.0f);
        // Direction the current projection puts at this screen point.
        Vector3f direction = new Matrix3f(SkyReprojection.directionToScreen(currentViewProjection))
                .invert().transform(new Vector3f(ndc));
        // Project it as a w = 0 point: the projection's 4th column (bob) never applies.
        Vector3f clip = SkyReprojection.directionToScreen(previousViewProjection).transform(new Vector3f(direction));
        return new Vector2f((clip.x / clip.z) * 0.5f + 0.5f, (clip.y / clip.z) * 0.5f + 0.5f);
    }

    private static Vector2f apply(Matrix3f homography, Vector2f uv) {
        Vector3f h = homography.transform(new Vector3f(uv.x * 2.0f - 1.0f, uv.y * 2.0f - 1.0f, 1.0f));
        return new Vector2f((h.x / h.z) * 0.5f + 0.5f, (h.y / h.z) * 0.5f + 0.5f);
    }

    private static float pixelDistance(Vector2f a, Vector2f b) {
        float dx = (a.x - b.x) * WIDTH;
        float dy = (a.y - b.y) * HEIGHT;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Worst residual, in native pixels, over a grid covering the screen. */
    private static float worstResidualPixels(Matrix4f currentViewProjection, Matrix4f previousViewProjection,
            Matrix3f homography) {
        float worst = 0.0f;
        for (int i = 0; i <= 32; i++) {
            for (int j = 0; j <= 18; j++) {
                Vector2f uv = new Vector2f(0.02f + 0.96f * i / 32.0f, 0.02f + 0.96f * j / 18.0f);
                worst = Math.max(worst, pixelDistance(apply(homography, uv),
                        truePreviousUv(currentViewProjection, previousViewProjection, uv)));
            }
        }
        return worst;
    }

    /** Worst error, in native pixels, of reprojecting a sky pixel to itself (a zero motion vector). */
    private static float worstZeroMotionErrorPixels(Matrix4f currentViewProjection, Matrix4f previousViewProjection) {
        float worst = 0.0f;
        for (int i = 0; i <= 32; i++) {
            for (int j = 0; j <= 18; j++) {
                Vector2f uv = new Vector2f(0.02f + 0.96f * i / 32.0f, 0.02f + 0.96f * j / 18.0f);
                worst = Math.max(worst, pixelDistance(uv,
                        truePreviousUv(currentViewProjection, previousViewProjection, uv)));
            }
        }
        return worst;
    }

    private static Matrix4f viewProjection(Matrix4f p, Matrix4f modelView) {
        return new Matrix4f(p).mul(modelView);
    }

    @Test
    void homographyReprojectsSkyExactlyUnderYaw() {
        // A slow 30 deg/s pan at the user's reported 119 fps.
        Matrix4f current = viewProjection(projection(), rotation(30.0f / FPS, 0.0f));
        Matrix4f previous = viewProjection(projection(), rotation(0.0f, 0.0f));

        float residual = worstResidualPixels(current, previous, SkyReprojection.homography(current, previous));

        assertTrue(residual < 0.001f, "worst residual " + residual + " px, expected sub-thousandth-pixel");
    }

    @Test
    void homographyReprojectsSkyExactlyUnderYawAndPitchAtFlickSpeed() {
        // 180 deg/s yaw with 60 deg/s pitch -- a fast flick, well past normal look-around.
        Matrix4f current = viewProjection(projection(), rotation(180.0f / FPS, 60.0f / FPS));
        Matrix4f previous = viewProjection(projection(), rotation(0.0f, 0.0f));

        float residual = worstResidualPixels(current, previous, SkyReprojection.homography(current, previous));

        assertTrue(residual < 0.001f, "worst residual " + residual + " px, expected sub-thousandth-pixel");
    }

    /**
     * THE BUG'S MAGNITUDE. This is what {@code reconstruct.fsh} did on every sky pixel before the
     * fix: {@code gMotion} carries the cleared zero, so {@code prevUv == texCoord}. At a slow
     * 30 deg/s pan that is already ~9 px of error PER FRAME, and at {@code taaBlendFactor} 0.9 the
     * accumulation is ~10 frames deep, so the stale history smears across roughly 90 px of screen.
     */
    @Test
    void todaysZeroMotionErrorInPixels() {
        Matrix4f previous = viewProjection(projection(), rotation(0.0f, 0.0f));

        float slowPan = worstZeroMotionErrorPixels(
                viewProjection(projection(), rotation(30.0f / FPS, 0.0f)), previous);
        float lookAround = worstZeroMotionErrorPixels(
                viewProjection(projection(), rotation(90.0f / FPS, 0.0f)), previous);
        float flick = worstZeroMotionErrorPixels(
                viewProjection(projection(), rotation(180.0f / FPS, 0.0f)), previous);

        assertTrue(slowPan > 8.0f && slowPan < 10.0f, "30 deg/s: " + slowPan + " px");
        assertTrue(lookAround > 25.0f && lookAround < 28.0f, "90 deg/s: " + lookAround + " px");
        assertTrue(flick > 52.0f && flick < 56.0f, "180 deg/s: " + flick + " px");
    }

    /**
     * View bobbing must not reach the sky. It lives in the projection's 4th column, which a {@code
     * w = 0} direction never reads -- so a camera that only bobs produces the identity map, and one
     * that bobs while turning produces exactly the map it would have produced standing still.
     */
    @Test
    void viewBobDoesNotMoveTheSky() {
        Matrix4f still = rotation(0.0f, 0.0f);
        Matrix4f bobOnlyCurrent = viewProjection(bobbed(projection(), -0.034f, -0.091f), still);
        Matrix4f bobOnlyPrevious = viewProjection(bobbed(projection(), 0.021f, 0.058f), still);

        float residual = worstZeroMotionErrorPixels(bobOnlyCurrent, bobOnlyPrevious);
        assertEquals(0.0f, residual, 0.001f, "a bobbing, non-rotating camera must not move the sky");

        Matrix4f turned = rotation(30.0f / FPS, 0.0f);
        Matrix3f withBob = SkyReprojection.homography(
                viewProjection(bobbed(projection(), -0.034f, -0.091f), turned),
                viewProjection(bobbed(projection(), 0.021f, 0.058f), still));
        Matrix3f withoutBob = SkyReprojection.homography(
                viewProjection(projection(), turned), viewProjection(projection(), still));

        float worst = 0.0f;
        for (int i = 0; i <= 32; i++) {
            for (int j = 0; j <= 18; j++) {
                Vector2f uv = new Vector2f(0.02f + 0.96f * i / 32.0f, 0.02f + 0.96f * j / 18.0f);
                worst = Math.max(worst, pixelDistance(apply(withBob, uv), apply(withoutBob, uv)));
            }
        }
        assertTrue(worst < 0.001f, "bob leaked into the sky map: " + worst + " px");
    }

    /**
     * Camera TRANSLATION must not move the sky either, and here that is structural rather than
     * asserted-into-existence: the model-view is rotation-only by engine convention, so there is no
     * translation to leak. What this pins is that {@link SkyReprojection#directionToScreen} drops
     * column 3 -- so even if a translation DID appear in the model-view (a future convention change,
     * or a caller passing the wrong matrix), the sky map would be unaffected. This is exactly the
     * lane {@code u_CameraDelta} exists to supply for WATER, whose surface is at a finite distance.
     */
    @Test
    void cameraTranslationDoesNotMoveTheSky() {
        Matrix4f turned = rotation(30.0f / FPS, 0.0f);
        Matrix4f still = rotation(0.0f, 0.0f);

        Matrix3f pure = SkyReprojection.homography(
                viewProjection(projection(), turned), viewProjection(projection(), still));
        // The same pair with a full sprint's worth of travel folded into the model-views.
        Matrix3f translated = SkyReprojection.homography(
                viewProjection(projection(), new Matrix4f(turned).setTranslation(5.6f, -1.2f, 3.4f)),
                viewProjection(projection(), new Matrix4f(still).setTranslation(-2.1f, 0.7f, -4.8f)));

        float worst = 0.0f;
        for (int i = 0; i <= 32; i++) {
            for (int j = 0; j <= 18; j++) {
                Vector2f uv = new Vector2f(0.02f + 0.96f * i / 32.0f, 0.02f + 0.96f * j / 18.0f);
                worst = Math.max(worst, pixelDistance(apply(pure, uv), apply(translated, uv)));
            }
        }
        assertTrue(worst < 0.001f, "camera translation leaked into the sky map: " + worst + " px");
    }

    /**
     * The jitter question, in pixels. gMotion is expressed in a jitter-FREE basis (terrain.vsh
     * subtracts each frame's own jitter before differencing), so the sky map must be built from
     * un-jittered projections. Building it from the RASTERIZED pair instead disagrees by half a
     * pixel per axis -- and because the jitter sequence cycles, that error oscillates every frame
     * rather than settling: permanent sky shimmer, which is what TAA is there to remove.
     */
    @Test
    void jitteredMatricesDisagreeWithTheMotionVectorConvention() {
        Matrix4f turned = rotation(30.0f / FPS, 0.0f);
        Matrix4f still = rotation(0.0f, 0.0f);
        float[][] grid = {{-0.25f, -0.25f}, {0.25f, -0.25f}, {-0.25f, 0.25f}, {0.25f, 0.25f}};

        Matrix3f correct = SkyReprojection.homography(
                viewProjection(projection(), turned), viewProjection(projection(), still));

        float worst = 0.0f;
        for (int frame = 0; frame < grid.length; frame++) {
            float[] now = grid[frame];
            float[] before = grid[Math.floorMod(frame - 1, grid.length)];
            Matrix3f wrong = SkyReprojection.homography(
                    viewProjection(jittered(projection(), now[0], now[1]), turned),
                    viewProjection(jittered(projection(), before[0], before[1]), still));
            for (int i = 0; i <= 32; i++) {
                for (int j = 0; j <= 18; j++) {
                    Vector2f uv = new Vector2f(0.02f + 0.96f * i / 32.0f, 0.02f + 0.96f * j / 18.0f);
                    worst = Math.max(worst, pixelDistance(apply(wrong, uv), apply(correct, uv)));
                }
            }
        }

        // Half a pixel on each axis: sqrt(0.5^2 + 0.5^2) = 0.707.
        assertTrue(worst > 0.7f && worst < 0.72f, "jitter disagreement " + worst + " px, expected ~0.707");
    }

    /**
     * A singular current-frame map (reachable only before any real projection has been captured)
     * must fall back to identity rather than uploading NaNs -- one NaN here becomes a NaN motion
     * vector on every sky pixel on screen.
     */
    @Test
    void singularProjectionFallsBackToIdentity() {
        Matrix3f fallback = SkyReprojection.homography(new Matrix4f().zero(), viewProjection(projection(), rotation(0, 0)));

        assertEquals(new Matrix3f(), fallback);
    }

    /**
     * {@link SkyReprojection#commit} pairs THIS call's view-projection with the PREVIOUS call's, and
     * publishes exactly the homography {@link SkyReprojection#homography} would give for that pair,
     * embedded in a mat4's upper-left 3x3 (the shader reads it back as {@code mat3(...)}). Two
     * commits back to back, so the assertion does not depend on test ordering against the class's
     * static one-frame history.
     */
    @Test
    void commitPublishesTheHomographyOfConsecutiveFrames() {
        Matrix4f p = projection();
        Matrix4f before = rotation(0.0f, 0.0f);
        Matrix4f after = rotation(30.0f / FPS, 0.0f);

        SkyReprojection.commit(p, before);
        SkyReprojection.commit(p, after);

        Matrix4f expected = new Matrix4f(
                SkyReprojection.homography(viewProjection(p, after), viewProjection(p, before)));
        assertTrue(expected.equals(new Matrix4f(SkyReprojection.current()), 1.0e-9f),
                "published " + SkyReprojection.current() + " expected " + expected);
        // ...and it is a REAL map, not the identity fallback quietly passing the line above.
        assertTrue(!expected.equals(new Matrix4f(), 1.0e-7f), "expected a non-identity rotation map");
    }
}
