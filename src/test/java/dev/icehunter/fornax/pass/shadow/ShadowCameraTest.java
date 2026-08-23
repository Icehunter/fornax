package dev.icehunter.fornax.pass.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShadowCamera#compute} is the pure light-camera core -- exercised directly (no
 * Minecraft/GPU needed). The matrix is CAMERA-RELATIVE: it maps camera-relative world positions
 * (exactly what gbuffer_resolve.fsh reconstructs, and what terrain vertices become after
 * CameraTransform translation) into light clip space [-1,1]^2 x [0,1].
 */
class ShadowCameraTest {
    private static final Vector3f NOON_SUN = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final float DISTANCE = 96.0f;
    private static final int RESOLUTION = 2048;

    @Test
    void cameraOriginProjectsToLightClipCenter() {
        Matrix4f m = ShadowCamera.compute(NOON_SUN, 1000.0, 64.0, -500.0, DISTANCE, RESOLUTION).viewProj();
        Vector4f p = m.transform(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        // Ortho: w == 1; the camera sits at the map's center in xy, up to one texel of snap.
        float texelWorld = (2.0f * DISTANCE) / RESOLUTION; // world units per shadow texel
        float texelClip = 2.0f / RESOLUTION;               // clip units per shadow texel
        assertEquals(1.0f, p.w(), 1e-6f);
        assertTrue(Math.abs(p.x()) <= texelClip + 1e-6f, "x centered within one texel, got " + p.x());
        assertTrue(Math.abs(p.y()) <= texelClip + 1e-6f, "y centered within one texel, got " + p.y());
        assertTrue(p.z() >= 0.0f && p.z() <= 1.0f, "z in [0,1], got " + p.z());
        assertTrue(texelWorld > 0.0f);
    }

    @Test
    void pointAtShadowDistanceEdgeProjectsToClipEdge() {
        Matrix4f m = ShadowCamera.compute(NOON_SUN, 0.0, 64.0, 0.0, DISTANCE, RESOLUTION).viewProj();
        // Straight-down sun: a point DISTANCE blocks east of the camera lands at the +x clip edge
        // (up to one texel of snap).
        Vector4f p = m.transform(new Vector4f(DISTANCE, 0.0f, 0.0f, 1.0f));
        assertEquals(1.0f, p.x(), 2.0f / RESOLUTION + 1e-5f);
    }

    @Test
    void texelSnappingMakesSubTexelCameraMotionAConstantOffset() {
        // THE stability property: moving the camera by a fraction of a shadow texel must produce the
        // IDENTICAL matrix (the light camera translates only in whole-texel steps), so static world
        // geometry rasterizes to identical shadow-map texels frame after frame.
        float texelWorld = (2.0f * DISTANCE) / RESOLUTION; // 0.09375 blocks at 96/2048
        Matrix4f a = ShadowCamera.compute(NOON_SUN, 100.0, 64.0, 100.0, DISTANCE, RESOLUTION).viewProj();
        Matrix4f b = ShadowCamera.compute(NOON_SUN, 100.0 + texelWorld * 0.25, 64.0, 100.0, DISTANCE, RESOLUTION).viewProj();
        // Same snapped light origin -> the two matrices differ ONLY by the camera-relative
        // translation delta, which in light clip space is exactly the world delta projected:
        // verify by transforming the SAME ABSOLUTE world point through both (camera-relative input
        // adjusted per camera) and asserting identical clip output.
        Vector4f pa = a.transform(new Vector4f(50.0f - 100.0f, 0.0f, 50.0f - 100.0f, 1.0f));
        Vector4f pb = b.transform(new Vector4f(50.0f - (100.0f + texelWorld * 0.25f), 0.0f, 50.0f - 100.0f, 1.0f));
        assertEquals(pa.x(), pb.x(), 1e-5f, "absolute world point must land on the same shadow texel");
        assertEquals(pa.y(), pb.y(), 1e-5f);
        assertEquals(pa.z(), pb.z(), 1e-4f);
    }

    @Test
    void lowSunAngleStillProducesValidOrtho() {
        // Near-sunset direction (mostly horizontal): matrix must stay finite/invertible and keep the
        // camera-origin near clip center.
        Vector3f lowSun = new Vector3f(-0.98f, 0.2f, 0.0f).normalize();
        Matrix4f m = ShadowCamera.compute(lowSun, 0.0, 64.0, 0.0, DISTANCE, RESOLUTION).viewProj();
        Vector4f p = m.transform(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        assertEquals(1.0f, p.w(), 1e-6f);
        assertTrue(Float.isFinite(p.x()) && Float.isFinite(p.y()) && Float.isFinite(p.z()));
        assertTrue(Math.abs(p.x()) <= 2.0f / RESOLUTION + 1e-5f);
    }

    /**
     * {@link ShadowCamera#depthHalfExtent}: {@code max(8192, shadowDistance*2)}, floored at 8192
     * across both Plague's real slider domain ({@code [16..512]}, default 128) and another pack's
     * ({@code [32..256]}) -- growing past the floor only once {@code shadowDistance} exceeds 4096,
     * far beyond either pack's exposed range.
     */
    @Test
    void depthHalfExtentNeverRegressesBelowTheFloor() {
        assertEquals(8192.0f, ShadowCamera.depthHalfExtent(16.0f), 1e-6f, "floor engaged at Plague's slider minimum");
        assertEquals(8192.0f, ShadowCamera.depthHalfExtent(128.0f), 1e-6f, "floor engaged at the pack default");
        assertEquals(8192.0f, ShadowCamera.depthHalfExtent(512.0f), 1e-6f, "floor still engaged at Plague's slider maximum");
        // Past either pack's exposed ceiling, the coupling engages and tracks 2x shadowDistance.
        assertEquals(9000.0f, ShadowCamera.depthHalfExtent(4500.0f), 1e-6f, "grows past 8192 only far beyond any real slider");

        for (float d = 16.0f; d <= 512.0f; d += 16.0f) {
            assertEquals(8192.0f, ShadowCamera.depthHalfExtent(d), 1e-6f,
                    "must stay pinned at the floor across Plague's whole slider domain, d=" + d);
        }
    }

    @Test
    void shadowDistanceOptionRemainsInBlocks() {
        assertEquals(16.0f, ShadowCamera.shadowDistanceOptionBlocks(16.0f), 0.0f);
        assertEquals(128.0f, ShadowCamera.shadowDistanceOptionBlocks(128.0f), 0.0f);
        assertEquals(512.0f, ShadowCamera.shadowDistanceOptionBlocks(512.0f), 0.0f);
    }
}
