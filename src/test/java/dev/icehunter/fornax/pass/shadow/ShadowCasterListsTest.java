package dev.icehunter.fornax.pass.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShadowCasterLists#aabbIntersectsShadowVolume} is the caster-selection predicate --
 * exercised directly (package-private, no Sodium/GPU dependency) against real {@link
 * ShadowCamera#compute} matrices, exactly like {@link ShadowCameraTest} exercises {@code compute}
 * itself. A flat world-XZ radius test around the camera is correct only at noon (Y-blind), and
 * misses occluders that sit inside the light's true (tilted) frustum but outside that world-XZ
 * cylinder at low sun angles; the scenarios below exercise exactly that boundary. Each test's AABB
 * is expressed CAMERA-RELATIVE (min/max deltas from camera position), matching {@link
 * ShadowCasterLists}'s own contract.
 */
class ShadowCasterListsTest {
    private static final Vector3f NOON_SUN = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final float SHADOW_DISTANCE = 96.0f;
    private static final int RESOLUTION = 2048;
    /** camX,camY,camZ passed to {@link ShadowCamera#compute} for every case below -- irrelevant to
     * the camera-relative AABB math itself (only affects texel snapping), held fixed for clarity. */
    private static final double CAM_X = 0.0, CAM_Y = 64.0, CAM_Z = 0.0;

    private static boolean intersects(Matrix4f viewProj, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        return ShadowCasterLists.aabbIntersectsShadowVolume(viewProj, minX, minY, minZ, maxX, maxY, maxZ, new Vector4f());
    }

    /**
     * At a low sun angle, a section far along the sun's ground azimuth (world +X here) sits well
     * outside a world-XZ-radius cylinder (radius ~ shadowDistance + one section = 112 blocks) but
     * inside the light's true tilted frustum, because at low sun that horizontal offset maps mostly
     * onto the light's generous Z (depth) axis, not its tight XY ortho extent. The predicate under
     * test must include it; a bare {@code Math.abs(dx) > radius} check provably would not.
     */
    @Test
    void lowSunIncludesOccluderOutsideOldXzRadius() {
        // Mostly-horizontal sun, low elevation, pointed along +X.
        Vector3f lowSun = new Vector3f(0.995f, 0.1f, 0.0f).normalize();
        Matrix4f viewProj = ShadowCamera.compute(lowSun, CAM_X, CAM_Y, CAM_Z, SHADOW_DISTANCE, RESOLUTION).viewProj();

        // A 16-block section centered 300 blocks along +X from the camera -- well outside the old
        // (shadowDistance + 16 = 112 block) world-XZ radius, at the SAME height as the camera.
        double centerX = 300.0, centerY = 0.0, centerZ = 0.0;
        boolean oldPredicateWouldInclude = Math.abs(centerX - CAM_X) <= SHADOW_DISTANCE + 16.0;
        assertFalse(oldPredicateWouldInclude, "sanity: this section must be OUTSIDE the old world-XZ radius");

        assertTrue(intersects(viewProj,
                centerX - 8.0, centerY - 8.0, centerZ - 8.0,
                centerX + 8.0, centerY + 8.0, centerZ + 8.0),
                "section inside the true tilted light frustum must now be included");
    }

    /**
     * A section genuinely outside the light's shadow volume (large offset along the light's tight XY
     * ortho extent, well beyond {@code shadowDistance}) must still be excluded -- the predicate is not
     * a no-op that includes everything.
     */
    @Test
    void clearlyOutsideOrthoBoxIsExcluded() {
        Vector3f lowSun = new Vector3f(0.995f, 0.1f, 0.0f).normalize();
        Matrix4f viewProj = ShadowCamera.compute(lowSun, CAM_X, CAM_Y, CAM_Z, SHADOW_DISTANCE, RESOLUTION).viewProj();

        // For this lightDir the light's local XY roughly tracks world Z/Y (see class javadoc), so a
        // large world-Z offset lands far outside the tight [-shadowDistance, shadowDistance] ortho
        // extent, unlike the world-X offset in the inclusion test above.
        double centerX = 0.0, centerY = 0.0, centerZ = 2000.0;
        assertFalse(intersects(viewProj,
                centerX - 8.0, centerY - 8.0, centerZ - 8.0,
                centerX + 8.0, centerY + 8.0, centerZ + 8.0),
                "section far outside the light's ortho volume must be excluded");
    }

    /**
     * Noon regression guard: at true noon (light direction ~= world -Y... here {@code NOON_SUN} is
     * "toward the light", straight up), the light's local XY coincides with world XZ and its local Z
     * with world Y -- so the new predicate must agree with the OLD world-XZ-cylinder test's verdict
     * for both an in-radius and an out-of-radius section, matching this task's "no regression at
     * noon" requirement.
     */
    @Test
    void noonBehaviorMatchesOldWorldXzRadius() {
        Matrix4f viewProj = ShadowCamera.compute(NOON_SUN, CAM_X, CAM_Y, CAM_Z, SHADOW_DISTANCE, RESOLUTION).viewProj();

        // Well within the old radius (96 + 16 margin) -- old predicate included it, new must too.
        assertTrue(intersects(viewProj, 42.0, -8.0, 42.0, 58.0, 8.0, 58.0),
                "section within the noon world-XZ radius must still be included");

        // Well outside the old radius -- old predicate excluded it, new must too (noon: light Z axis
        // is world Y, which does NOT rescue a horizontal offset the way low sun does).
        assertFalse(intersects(viewProj, 292.0, -8.0, -8.0, 308.0, 8.0, 8.0),
                "section outside the noon world-XZ radius must still be excluded");
    }

    /**
     * Degenerate/near-vertical light axis: {@link ShadowCamera#compute} already guards this (switches
     * its up-vector to avoid a singular {@code lookAt} basis -- see {@link
     * ShadowCameraTest#lowSunAngleStillProducesValidOrtho} for the matrix-level guarantee). This test
     * confirms the AABB predicate built on top of that matrix stays well-defined (no NaN/infinite
     * results) rather than assuming it.
     */
    @Test
    void nearVerticalLightAxisProducesFiniteResult() {
        Vector3f nearVertical = new Vector3f(0.001f, 0.9999995f, 0.0f).normalize();
        Matrix4f viewProj = ShadowCamera.compute(nearVertical, CAM_X, CAM_Y, CAM_Z, SHADOW_DISTANCE, RESOLUTION).viewProj();

        // Doesn't matter whether this particular AABB intersects or not -- only that the computation
        // terminates with a boolean and never throws/NaNs internally (a NaN comparison in
        // aabbIntersectsShadowVolume would make every >=/<= comparison false, silently EXCLUDING
        // everything -- the assertion below on the camera-centered section, which must always be
        // inside any well-formed shadow volume, is what actually catches that failure mode).
        assertTrue(intersects(viewProj, -8.0, -8.0, -8.0, 8.0, 8.0, 8.0),
                "the section containing the camera itself must always be inside a well-formed shadow volume");
    }
}
