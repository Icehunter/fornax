package dev.icehunter.fornax.pass.reconstruct;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the reconstruct shader's disocclusion threshold against real gameplay numbers, reading the
 * constant from the shipped shader source itself (the same resource the jar carries) so the test
 * cannot drift from what actually runs. Scenario: ordinary walking -- 4.317 blocks/s at 90 fps,
 * 1080p, 70 degree vertical FOV, eye height 1.62, looking at ground 3 blocks ahead.
 *
 * <p>Two facts are pinned. First, per-frame reprojection during a walk is a handful of pixels --
 * comfortably in bounds -- so the bounds check must not be what rejects history in motion. Second,
 * the reversed-Z depth difference across that reprojection distance ON A CONTINUOUS SURFACE is
 * orders of magnitude below the threshold, so validity correctly ACCEPTS while walking: history
 * ghosting in motion is the neighborhood clamp's job (plus the honest age reset), never the
 * validity test's -- the retired taa_blend behaved identically, with the same 0.05 threshold this
 * test asserts we kept.
 */
class ReconstructValidityMathTest {
    private static final String SHADER_RESOURCE = "/assets/fornax/shaders/post/reconstruct.fsh";

    // Vanilla walking speed in blocks/s and the perf-phase frame-rate target.
    private static final double WALK_SPEED = 4.317;
    private static final double FPS = 90.0;
    private static final double EYE_HEIGHT = 1.62;
    private static final double GROUND_DISTANCE = 3.0;
    private static final double VERTICAL_FOV_RADIANS = Math.toRadians(70.0);
    // Vanilla near plane; reversed-Z with an infinite far plane stores near/viewDistance.
    private static final double NEAR_PLANE = 0.05;

    @Test
    void thresholdMatchesTheRetiredTaaBlendPass() {
        assertEquals(0.05, parseDisocclusionThreshold(), 1e-9,
                "DISOCCLUSION_DEPTH_THRESHOLD must stay at the retired taa_blend's 0.05 -- the ratio-1.0 equivalence bar");
    }

    @Test
    void walkingReprojectionStaysComfortablyInBounds() {
        double motionUv = walkingMotionUv();

        assertTrue(motionUv > 0.001, "walking must produce measurable per-frame motion, got " + motionUv);
        assertTrue(motionUv < 0.02, "walking motion must be a few pixels, not a large UV jump, got " + motionUv);
        // ~6 pixels at 1080p: far inside [0,1], so the bounds check never rejects a walk.
        assertTrue(motionUv * 1080.0 < 12.0, "expected single-digit pixel motion at 1080p, got " + (motionUv * 1080.0));
    }

    @Test
    void walkingGroundDepthDeltaIsFarBelowTheThreshold() {
        double stepPerFrame = WALK_SPEED / FPS;

        // Reversed-Z depth of the same pixel's ground content this frame vs at the reprojected
        // position (the surface point one step behind along the ground plane).
        double depthNow = NEAR_PLANE / viewDistance(GROUND_DISTANCE);
        double depthPrev = NEAR_PLANE / viewDistance(GROUND_DISTANCE - stepPerFrame);
        double delta = Math.abs(depthNow - depthPrev);

        double threshold = parseDisocclusionThreshold();
        assertTrue(delta < threshold / 50.0,
                "continuous-surface depth delta while walking (" + delta + ") must be far below the "
                        + "disocclusion threshold (" + threshold + ") -- validity ACCEPTS during smooth motion; "
                        + "anti-trailing is the clamp's job");
    }

    private static double walkingMotionUv() {
        double stepPerFrame = WALK_SPEED / FPS;
        // Angle below the horizon of the watched ground point, this frame and next.
        double angleNow = Math.atan(EYE_HEIGHT / GROUND_DISTANCE);
        double angleNext = Math.atan(EYE_HEIGHT / (GROUND_DISTANCE - stepPerFrame));
        // Small-angle mapping of angular motion onto the vertical FOV's UV span.
        return Math.abs(angleNext - angleNow) / VERTICAL_FOV_RADIANS;
    }

    private static double viewDistance(double groundDistanceAhead) {
        return Math.hypot(groundDistanceAhead, EYE_HEIGHT);
    }

    private static double parseDisocclusionThreshold() {
        String source = readShaderSource();
        Matcher m = Pattern.compile("#define\\s+DISOCCLUSION_DEPTH_THRESHOLD\\s+([0-9.]+)").matcher(source);
        assertTrue(m.find(), "reconstruct.fsh must define DISOCCLUSION_DEPTH_THRESHOLD");
        return Double.parseDouble(m.group(1));
    }

    private static String readShaderSource() {
        try (InputStream in = ReconstructValidityMathTest.class.getResourceAsStream(SHADER_RESOURCE)) {
            assertNotNull(in, "shader resource missing from classpath: " + SHADER_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed reading " + SHADER_RESOURCE, e);
        }
    }
}
