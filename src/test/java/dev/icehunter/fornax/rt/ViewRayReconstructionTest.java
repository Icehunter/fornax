package dev.icehunter.fornax.rt;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a primary ray's direction is the difference of two unprojected points rather than one.
 *
 * <p>The tempting shortcut is that every point along a perspective view ray shares one direction
 * from the eye, so unprojecting a single depth is enough. That holds only while the eye really sits
 * at camera-relative (0, 0, 0). View bobbing puts a small translation inside modelView which is not
 * folded out the way the camera's own translation is, and against a near plane centimetres away
 * that offset is a large fraction of the distance. This measures the resulting error instead of
 * asserting it, because the number is the whole argument: a few centimetres of bob turns into
 * degrees of ray swing per step, which is a view that throws itself off screen.
 */
class ViewRayReconstructionTest {

    /** Minecraft's near plane, in blocks. The bob offset is a large fraction of this. */
    private static final float NEAR = 0.05f;
    private static final float FAR = 512.0f;

    /** A few centimetres, the scale vanilla's view bob moves the camera by. */
    private static final float BOB = 0.05f;

    /** Reversed-Z, the convention this engine renders with: near maps to 1, far to 0. */
    private static Matrix4f inverseProjModelView(float bobX, float bobY) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0), 16f / 9f, FAR, NEAR, true);
        Matrix4f modelView = new Matrix4f().translate(bobX, bobY, 0.0f);
        return projection.mul(modelView).invert();
    }

    private static org.joml.Vector3f unproject(Matrix4f inverse, float ndcX, float ndcY, float ndcZ) {
        Vector4f clip = inverse.transform(new Vector4f(ndcX, ndcY, ndcZ, 1.0f));
        return new org.joml.Vector3f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w);
    }

    private static float degreesBetween(org.joml.Vector3f a, org.joml.Vector3f b) {
        float cos = new org.joml.Vector3f(a).normalize().dot(new org.joml.Vector3f(b).normalize());
        return (float) Math.toDegrees(Math.acos(Math.max(-1.0f, Math.min(1.0f, cos))));
    }

    /**
     * The defect, measured. Normalising the near-plane point as if the eye were at the origin turns
     * a few centimetres of bob into several degrees of ray direction, every frame, in both axes.
     */
    @Test
    void normalisingASingleNearPlanePointSwingsTheRayByDegreesWhenTheEyeIsOffset() {
        org.joml.Vector3f still = unproject(inverseProjModelView(0, 0), 0, 0, 1.0f);
        org.joml.Vector3f bobbed = unproject(inverseProjModelView(BOB, BOB), 0, 0, 1.0f);

        float swing = degreesBetween(still, bobbed);
        assertTrue(swing > 5.0f,
                "a single near-plane point should swing by degrees under bob; measured " + swing);
    }

    /** The fix, measured on the same matrices: the offset cancels in the difference. */
    @Test
    void theDifferenceOfTwoPointsOnTheRayIsUnmovedByTheSameOffset() {
        org.joml.Vector3f still = rayDirection(inverseProjModelView(0, 0));
        org.joml.Vector3f bobbed = rayDirection(inverseProjModelView(BOB, BOB));

        float swing = degreesBetween(still, bobbed);
        assertTrue(swing < 0.01f,
                "the two-point direction must be unmoved by an eye offset; measured " + swing);
    }

    /**
     * And it stays correct off-centre, where a perspective ray is not the view axis.
     *
     * <p>The bound is 0.05 degrees rather than zero: what is left at a frame corner is float
     * round-trip through a 4x4 inverse, measured at about 0.02 degrees, not the eye offset. That
     * residual is two orders of magnitude below the swing the single-point form produces at the
     * same pixel, and roughly a thousandth of a pixel at any resolution this renders at.
     */
    @Test
    void theTwoPointDirectionIsStableAcrossTheFrameNotJustAtItsCentre() {
        for (float ndcX : new float[]{-0.9f, -0.3f, 0.3f, 0.9f}) {
            for (float ndcY : new float[]{-0.9f, 0.9f}) {
                org.joml.Vector3f still = rayDirection(inverseProjModelView(0, 0), ndcX, ndcY);
                org.joml.Vector3f bobbed = rayDirection(inverseProjModelView(BOB, BOB), ndcX, ndcY);
                float twoPoint = degreesBetween(still, bobbed);
                assertTrue(twoPoint < 0.05f,
                        "corner (" + ndcX + ", " + ndcY + ") swung " + twoPoint + " degrees");

                // What makes that bound mean something: the same pixel, the same matrices, built
                // from one unprojected point instead of two.
                float singlePoint = degreesBetween(
                        unproject(inverseProjModelView(0, 0), ndcX, ndcY, 1.0f),
                        unproject(inverseProjModelView(BOB, BOB), ndcX, ndcY, 1.0f));
                assertTrue(singlePoint > twoPoint * 50.0f,
                        "at (" + ndcX + ", " + ndcY + ") the single-point form swung " + singlePoint
                                + " degrees against the two-point form's " + twoPoint);
            }
        }
    }

    /**
     * Source-level, because the kernel needs a Metal device: the two shadow kernels have always
     * taken the difference of two unprojected points, and the debug kernel is the one that did not.
     */
    @Test
    void everyKernelThatBuildsAViewRayTakesTheDifferenceOfTwoUnprojectedPoints() throws IOException {
        Path shaders = Path.of("src/main/resources/assets/fornax/shaders_engine");
        String debug = Files.readString(shaders.resolve("rt_debug.metal"));
        assertTrue(debug.contains("float4 nearClip = constants.invProjModelView * float4(ndc, 1.0, 1.0);"),
                "the debug kernel must unproject the near plane");
        assertTrue(debug.contains("float4 farClip = constants.invProjModelView * float4(ndc, 0.0, 1.0);"),
                "and the far plane");
        assertTrue(debug.contains("float3 extent = farClip.xyz / farClip.w - nearClip.xyz / nearClip.w;"),
                "and take the difference, so an eye offset cancels");

        for (String kernel : new String[]{"rt_sun_depth.metal", "rt_mesh_shadow.metal"}) {
            String source = Files.readString(shaders.resolve(kernel));
            assertTrue(source.contains("nearPoint") || source.contains("nearClip"),
                    kernel + " builds its ray from two unprojected points; keep it that way");
        }
    }

    private static org.joml.Vector3f rayDirection(Matrix4f inverse) {
        return rayDirection(inverse, 0, 0);
    }

    private static org.joml.Vector3f rayDirection(Matrix4f inverse, float ndcX, float ndcY) {
        org.joml.Vector3f near = unproject(inverse, ndcX, ndcY, 1.0f);
        org.joml.Vector3f far = unproject(inverse, ndcX, ndcY, 0.0f);
        return new org.joml.Vector3f(far).sub(near);
    }
}
