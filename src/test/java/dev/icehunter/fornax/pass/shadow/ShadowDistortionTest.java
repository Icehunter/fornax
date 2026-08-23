package dev.icehunter.fornax.pass.shadow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ShadowCamera#distortFactor}/{@link ShadowCamera#shadowMapBias} are the pure, GPU-free
 * reference the active shadow shader ports must all match byte-for-byte, via the single
 * {@code u_ShadowMapParams.x} uniform {@link ShadowCamera#shadowMapBias}'s own javadoc names in
 * full: this repo's {@code shadow.vsh} write side; Plague's {@code shadow_entities.vsh} write side,
 * {@code gbuffer_resolve.fsh}'s {@code sampleSunShadow} read, and its shadow debug-view branch.
 * These tests exercise the formula algebraically so a
 * future edit to any one of the (textually duplicated, GLSL has no cross-shader sharing) shader
 * ports can be checked against this single Java source of truth without a GPU.
 */
class ShadowDistortionTest {
    /**
     * Solves {@code U = dist / (dist*bias + (1-bias))} for {@code dist} given a distorted magnitude
     * {@code U} and asserts the original {@code dist} is recovered -- catches a swapped
     * {@code bias}/{@code 1-bias} term or a sign error in any future edit to either shader port,
     * since a formula error of that shape would not round-trip.
     */
    @Test
    void distortionRoundTripRecoversOriginalMagnitude() {
        float bias = 0.7333333f; // ShadowCamera.shadowMapBias(96, 2048) -- a mid-slider distance
        float dist = 0.6f; // an arbitrary light-clip-space xy magnitude inside the ortho box

        float distorted = dist / ShadowCamera.distortFactor(dist, bias);
        // Recover dist from distorted: solving U = dist/(dist*bias+(1-bias)) for dist gives
        // dist = U*(1-bias) / (1 - U*bias).
        float recovered = distorted * (1.0f - bias) / (1.0f - distorted * bias);

        assertEquals(dist, recovered, 1e-5f, "the distortion must be exactly invertible");
    }

    /** {@link ShadowCamera#distortFactor} is exactly 1.0 (no warp) at the ortho box edge
     * ({@code lVertexPos == 1}), for ANY bias -- {@code bias + (1 - bias) == 1} algebraically. */
    @Test
    void distortFactorIsIdentityAtOrthoBoxEdge() {
        assertEquals(1.0f, ShadowCamera.distortFactor(1.0f, 0.0f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(1.0f, 0.5f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(1.0f, 0.9f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(1.0f, -0.3f), 1e-6f);
    }

    /** {@link ShadowCamera#distortFactor} is exactly 1.0 (no warp, anywhere in the box) when
     * {@code bias == 0} -- {@code lVertexPos*0 + (1-0) == 1} for any {@code lVertexPos}. */
    @Test
    void zeroBiasIsAlwaysIdentity() {
        assertEquals(1.0f, ShadowCamera.distortFactor(0.0f, 0.0f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(0.25f, 0.0f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(0.5f, 0.0f), 1e-6f);
        assertEquals(1.0f, ShadowCamera.distortFactor(1.0f, 0.0f), 1e-6f);
    }

    /**
     * The property the bias is DERIVED from ({@link ShadowCamera#shadowMapBias}'s own doc): the
     * warped centre texel is {@code (2D/res) * (1 - bias)} blocks, and coupling the bias to the
     * full-detail radius holds it at exactly 0.025 blocks for every distance and resolution where
     * the warp is active. A future edit that decouples the radius from the resolution, or nudges
     * either constant, breaks this identity rather than some memorized output value.
     */
    @Test
    void warpedCentreTexelIsConstantAcrossDistanceAndResolution() {
        float[][] cases = {{64, 1024}, {128, 1024}, {64, 2048}, {128, 2048}, {192, 2048},
                {512, 2048}, {128, 4096}, {512, 4096}};
        for (float[] c : cases) {
            float bias = ShadowCamera.shadowMapBias(c[0], c[1]);
            float centreTexelBlocks = (2.0f * c[0] / c[1]) * (1.0f - bias);
            assertEquals(0.025f, centreTexelBlocks, 1e-5f,
                    "centre texel drifted at D=" + c[0] + ", res=" + c[1]);
        }
    }

    /** Continuity pins at the 2048 default map, where the derived full-detail radius is 25.6
     * blocks: the shipped look before the resolution coupling landed, kept as regression anchors
     * ({@code bias = 1 - 25.6/D}). */
    @Test
    void shadowMapBiasKeepsTheShippedValuesAtTheDefaultResolution() {
        assertEquals(0.8f, ShadowCamera.shadowMapBias(128.0f, 2048.0f), 1e-4f);
        assertEquals(0.73333f, ShadowCamera.shadowMapBias(96.0f, 2048.0f), 1e-4f);
        // Plague's slider minimum's next step: D=32 is still comfortably positive.
        assertEquals(0.2f, ShadowCamera.shadowMapBias(32.0f, 2048.0f), 1e-4f);
    }

    /**
     * D=16 is Plague's actual slider minimum ({@code [16..512] step 16}) and the ONLY value on that
     * step-16 slider where the raw formula goes negative (D=32 already gives +0.2) -- celestial
     * rework decision, Stage 0/Task 4 (2026-08-11). Unclamped, {@code shadowMapBias(16) = -0.6} makes
     * {@link ShadowCamera#distortFactor} cross exactly zero at radius 2.667 and flip sign, which is
     * reachable in practice (see {@link ShadowCamera#shadowMapBias}'s own doc) and reads in-game as
     * silently mirrored/exploded shadow geometry, not a crash. The floor at 0 means "no warp" at
     * D=16 -- well-defined, and free: at 16 blocks over a 2048px map that's already ~64 texels/block.
     */
    @Test
    void shadowMapBiasIsFlooredAtZeroAtPlaguesSliderMinimum() {
        assertEquals(0.0f, ShadowCamera.shadowMapBias(16.0f, 2048.0f), 1e-6f);
    }

    /** Pack-authored data (the runtime {@code shadowDistance} and {@code SHADOW_RESOLUTION}
     * options) is not trusted to stay positive and finite on either axis -- {@link
     * ShadowCamera#shadowMapBias} floors every degenerate input to the same safe identity-warp
     * value a valid low D would get. A bad RESOLUTION needs its own explicit guard: unguarded, a
     * non-positive radius sails through the distance compare and produces {@code bias >= 1}, the
     * negative-distortFactor mirrored-geometry failure from the other side. */
    @Test
    void shadowMapBiasIsSafeForNonPositiveOrNonFiniteInput() {
        assertEquals(0.0f, ShadowCamera.shadowMapBias(0.0f, 2048.0f), 1e-6f);
        assertEquals(0.0f, ShadowCamera.shadowMapBias(-1.0f, 2048.0f), 1e-6f);
        assertEquals(0.0f, ShadowCamera.shadowMapBias(Float.NaN, 2048.0f), 1e-6f);
        assertEquals(0.0f, ShadowCamera.shadowMapBias(128.0f, 0.0f), 1e-6f);
        assertEquals(0.0f, ShadowCamera.shadowMapBias(128.0f, -2048.0f), 1e-6f);
        assertEquals(0.0f, ShadowCamera.shadowMapBias(128.0f, Float.NaN), 1e-6f);
    }

    /** The write side stays free of any depth-scale constant: a {@code gl_Position.z *= c} in
     * shadow.vsh silently desynchronizes from every pack-side read the moment either side changes,
     * and the D32_FLOAT target makes such a scale a no-op anyway (ShadowCamera's class javadoc,
     * history note). This pin keeps the vestige from returning on the engine side. */
    @Test
    void shadowWriteSideCarriesNoDepthScale() throws java.io.IOException {
        String vsh = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/fornax/shaders/blocks/shadow.vsh"));
        org.junit.jupiter.api.Assertions.assertFalse(vsh.contains("gl_Position.z *="),
                "shadow.vsh must write unscaled light-clip depth");
    }
}
