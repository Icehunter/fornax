package dev.icehunter.fornax.pass.shadow;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Pure light-camera math for the sun/moon shadow map (no Minecraft/GPU dependency -- the
 * CameraJitter pattern: a static, device-free core the tests exercise directly, with the impure
 * per-frame wiring living at the call site in GraphRunner).
 *
 * <p>The returned matrices are CAMERA-RELATIVE: they map positions expressed relative to the player
 * camera (exactly what gbuffer_resolve.fsh reconstructs via u_InvProjModelView, and exactly what
 * terrain vertices become after Sodium's CameraTransform translation) into light clip space
 * ([-1,1]^2 xy, [0,1] z -- the zero-to-one depth convention this engine uses everywhere). Building
 * them camera-relative keeps them consistent with every other matrix in u_Globals and lets ONE
 * matrix pair serve both the shadow render pass and resolve's sampling with no absolute-position
 * uniform.
 *
 * <p>Texel snapping: the light camera's eye is quantized to whole shadow-map texels in light space,
 * so sub-texel player movement produces the IDENTICAL rasterization of static geometry frame after
 * frame -- the standard anti-shimmer technique, and this milestone's core stability guarantee.
 *
 * <p><b>{@code viewProj} itself stays a plain, LINEAR camera-relative ortho*view matrix -- the
 * radial distortion is a shader-only post-process, applied downstream in {@code shadow.vsh}'s write
 * side and {@code sampleSunShadow}'s read side (matched math, {@link
 * #distortFactor} the canonical reference both shader sites mirror), never folded into this matrix.</b>
 * A non-linear per-pixel/per-vertex warp cannot be expressed as a matrix multiply, and keeping the
 * matrix itself linear is also load-bearing for {@code ShadowCasterLists.aabbIntersectsShadowVolume}:
 * that predicate transforms AABB corners through this same matrix and needs the result to remain an
 * exact linear box test (see its own javadoc's "affine map" argument) -- the acne model's two parts
 * (distortion + the hardware comparison sampler) both layer strictly downstream of this matrix,
 * never inside it.
 *
 * <p>History note (2026-08-19): a third write-side step, {@code gl_Position.z *= 0.2}, was removed
 * from both write sites and all three pack read sites. On this engine it was vestigial: the depth
 * target is D32_FLOAT, whose precision is relative rather than absolute, so scaling both sides of
 * the comparison by the same constant changes no comparison outcome -- and the z window it could
 * have bought headroom inside is already floored at 8192 blocks ({@link #depthHalfExtent}), far
 * beyond any submitted caster. A multiply that provably alters nothing on this engine is carried
 * complexity, and every site that had to "match byte-for-byte" across two repos was a lockstep
 * hazard (this file's own history section is the monument to that failure mode).
 */
public final class ShadowCamera {
    private ShadowCamera() {
    }

    /**
     * World size, in blocks, that one shadow-map texel is held to at the map's exact centre --
     * the quality target the radial distortion exists to deliver. The derivation in
     * {@link #shadowMapBias}'s own doc shows the centre texel equals exactly this value at every
     * slider distance once the bias is coupled to it; 0.025 blocks (2.5 cm) is the value accepted
     * in-game at the 2048 default map, where it reproduces the long-shipped warp strength.
     */
    private static final float CENTER_TEXEL_BLOCKS = 0.025f;

    /**
     * The radial-distortion bias term shared by the active shadow shader sites (via
     * {@code u_ShadowMapParams.x} in {@code u_Globals} -- see globals.glsl's own doc comment; none
     * of them recompute the formula locally, they all read this single already-computed uniform):
     * {@code shadow.vsh}'s write side and {@code plague/shaders/blocks/shadow_entities.vsh}'s
     * write side (this repo and Plague, respectively); {@code sampleSunShadow} and the shadow
     * debug-view branch in Plague's {@code gbuffer_resolve.fsh}.
     *
     * <p><b>Derivation, from the quantity the warp exists to control.</b> The ortho window spans
     * {@code 2*shadowDistance} blocks across a {@code resolution}-texel map, so the unwarped texel
     * is {@code 2D/res} blocks. {@link #distortFactor} magnifies the map centre by
     * {@code 1/(1-bias)}; choosing {@code bias = 1 - R/D} makes that magnification {@code D/R},
     * so the centre texel becomes {@code (2D/res) * (R/D) = 2R/res} -- CONSTANT across the whole
     * slider. Solving {@code 2R/res = CENTER_TEXEL_BLOCKS} gives the full-detail radius
     * {@code R = CENTER_TEXEL_BLOCKS * res/2}: 25.6 blocks at the 2048 default (bias 0.8 at
     * Plague's default D=128), 12.8 at 1024, 51.2 at 4096. Coupling R to the LIVE resolution is
     * what keeps the centre-texel promise when the player changes {@code SHADOW_RESOLUTION};
     * before 2026-08-19 the radius was a fixed literal and the centre texel silently halved or
     * doubled with the map size instead.
     *
     * <p>Real domains, since a stale one derives the wrong bias: Plague ships {@code [16..512]}
     * (default 128) for the distance and {@code [1024 2048 4096]} for the resolution.
     *
     * <p><b>Floored at 0, not left to go negative.</b> Below {@code shadowDistance = R} the raw
     * formula goes negative, and for {@code bias < 0}, {@link #distortFactor} is a DECREASING
     * function of {@code lVertexPos} that crosses exactly zero (and then flips sign) once {@code
     * lVertexPos} exceeds {@code (bias-1)/bias} -- reachable in practice, since {@code
     * ShadowCasterLists} deliberately submits casters far outside the camera-relative ortho XY box
     * ({@link #depthHalfExtent} floors generously rather than tightly, for exactly this reason) and
     * {@code shadow.vsh}'s divide runs BEFORE clipping. Plague's step-16 slider reaches exactly one such
     * value at the 2048 default, D=16 (bias -0.6, zero-crossing at radius 2.667); the observed
     * failure mode there is not a crash but silently mirrored/exploded shadow geometry, which
     * reads as "shadows are broken at short distance" with no error of any kind.
     *
     * <p>The floor is the fix, not shader-side clamping of {@code distortFactor} itself: for {@code
     * bias} in {@code [0,1)}, {@code distortFactor(l) = l*bias + (1-bias)} equals {@code 1-bias > 0}
     * at {@code l=0} with non-negative slope, so it is strictly positive for EVERY {@code l >= 0}
     * regardless of how far outside the ortho box a vertex or fragment sits -- no bound on reachable
     * {@code lVertexPos} is needed once {@code bias} cannot go negative. {@code bias = 0} means "no
     * warp" (uniform texel density), which is already the documented default in {@link
     * ShadowFrameState} and already pinned by {@code ShadowDistortionTest.zeroBiasIsAlwaysIdentity}
     * -- and costs nothing visually at D=16, where the map is already ~64 texels/block. A single
     * Java-side clamp reaches all four sites through the one shared uniform, with no pack edit and
     * no lockstep risk, unlike a shader-side fix which would need four synchronized edits across
     * two repos -- exactly the failure mode this file's own history section (below) is a monument
     * to.
     */
    public static float shadowMapBias(float shadowDistance, float resolution) {
        float fullDetailRadius = CENTER_TEXEL_BLOCKS * 0.5f * resolution;
        // Negated compares, not Math.max(0, ...): Math.max propagates NaN, and this form floors
        // every degenerate input to the same safe "no warp" 0 -- pack-authored data
        // (SodiumWorldRendererOrchestrationMixin's option reads) is not trusted to stay positive
        // and finite on either axis. A non-positive/NaN radius must bail here explicitly: it would
        // sail through the distance compare below and come out as bias >= 1, which makes
        // distortFactor negative at the map centre -- the same mirrored-geometry failure the
        // distance floor exists to prevent.
        if (!(fullDetailRadius > 0.0f) || !(shadowDistance > fullDetailRadius)) {
            return 0.0f;
        }
        return 1.0f - fullDetailRadius / shadowDistance;
    }

    /**
     * The radial-distortion warp factor itself: {@code lVertexPos*bias + (1-bias)}, applied by
     * dividing a light-clip-space xy coordinate (or its vertex-shader equivalent) by this value to
     * push detail toward the shadow-map center (where the camera looks) and away from its edges --
     * the standard technique that lets a fixed-resolution shadow map spend more texels near the
     * player. Exposed as a pure static method purely so a GPU-free test can verify the formula
     * algebraically; {@code shadow.vsh} and both pack-side read sites duplicate this exact expression
     * inline (GLSL has no cross-shader function sharing across separately-compiled stages), never
     * recomputing {@code bias} itself -- only reading the one shared {@code u_ShadowMapParams.x}
     * value this method's sibling, {@link #shadowMapBias}, produces once per frame.
     */
    public static float distortFactor(float lVertexPos, float bias) {
        return lVertexPos * bias + (1.0f - bias);
    }

    /**
     * The unit contract for a pack's {@code u_ShadowDistance} option: the raw value IS blocks, and
     * reaches {@link #compute}'s {@code shadowDistance} parameter unconverted. An identity, so the
     * contract has one named and tested home rather than an inline conversion at the read site.
     *
     * <p>A unit conversion here is a 16x error, not a rounding one. A {@code * 16} once sat at the
     * mixin read site, treating the option as chunks: at a 128-block distance that builds a
     * 4096-block ortho window on a 2048^2 map, pushes the warp bias to 0.9875 where 0.8 is
     * designed, and puts every loaded section in the caster list every frame. It is silent -- no
     * assert fires, the shadows just go soft and the frame time goes up.
     *
     * <p>{@code ShadowCameraTest.shadowDistanceOptionRemainsInBlocks} pins the identity, so a
     * conversion reintroduced INSIDE this method is caught. One reintroduced upstream at a read
     * site is not; there is no end-to-end check that would catch it (reading {@code blocksPerTexel}
     * back off the ortho matrix {@link #compute} produces).
     */
    public static float shadowDistanceOptionBlocks(float optionValue) {
        return optionValue;
    }

    /**
     * Couples the light Z-axis half-extent to the live {@code shadowDistance}, floored so the light
     * volume never shrinks below a fixed minimum regardless of how low the slider goes: {@code
     * max(8192, shadowDistance * 2)}.
     *
     * <p>The floor is deliberately far above what any current slider reaches. It is one constant,
     * so the headroom is free, and the failure it prevents is invisible rather than loud: a Z
     * window too short silently drops occluders out of the caster list instead of erroring.
     *
     * <p>Do not tighten it. At grazing sun angles a horizontal distance projects almost entirely
     * onto the light's Z axis rather than its XY extent, so an occluder far outside the XY radius
     * still casts into view -- the mechanism {@code ShadowCasterLists}'s class javadoc describes.
     * {@code ShadowCasterListsTest.lowSunIncludesOccluderOutsideOldXzRadius} guards exactly this,
     * asserting a section 300 blocks along a low sun's azimuth stays included at a shadowDistance
     * of 96. A lower floor was tried and reverted because it clipped that occluder back out.
     * Raising the floor only widens the margin on that guard, so it cannot regress it.
     *
     * <p>Typical option domains run to a 512-block maximum, where {@code shadowDistance * 2} is
     * 1024 -- the {@code max()} branch is live at today's slider ceilings, not a future concern.
     * Package-private so {@code ShadowCameraTest} can exercise it directly.
     */
    static float depthHalfExtent(float shadowDistance) {
        return Math.max(8192.0f, shadowDistance * 2.0f);
    }

    /**
     * The light camera's projection, view, and combined viewProj matrices for one frame.
     *
     * @param proj     camera-relative light-space orthographic projection, texel-snapped.
     * @param view     camera-relative light-space view (look-along-light-direction) matrix.
     * @param viewProj {@code proj * view}, composes directly with resolve's camera-relative
     *                 reconstructed worldPos. The split {@code proj}/{@code view} pair is what the
     *                 shadow draw pass feeds into Sodium's {@code ChunkRenderMatrices(projection,
     *                 modelView)}.
     */
    public record LightMatrices(Matrix4f proj, Matrix4f view, Matrix4f viewProj) {
    }

    /**
     * @param lightDir  unit direction TOWARD the light (SunDirection.computeSunDirection()).
     * @param camX/Y/Z  the player camera's absolute world position this frame (used ONLY for texel
     *                  snapping -- the output matrices are camera-relative).
     * @param shadowDistance horizontal half-extent of the shadowed area, blocks.
     * @param shadowResolution shadow map size in texels (square).
     */
    public static LightMatrices compute(Vector3f lightDir, double camX, double camY, double camZ,
                                         float shadowDistance, int shadowResolution) {
        // Light view: looking along -lightDir toward the (camera-relative) origin from a point
        // depthHalfExtent away along +lightDir. Up vector: world-up unless the light is nearly
        // vertical, then world-north (avoids the degenerate lookAt).
        float depthHalfExtent = depthHalfExtent(shadowDistance);
        Vector3f dir = new Vector3f(lightDir).normalize();
        Vector3f up = Math.abs(dir.y) > 0.99f ? new Vector3f(0.0f, 0.0f, -1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f eye = new Vector3f(dir).mul(depthHalfExtent);
        Matrix4f lightView = new Matrix4f().setLookAt(eye, new Vector3f(0.0f, 0.0f, 0.0f), up);

        // Texel snapping: express the camera's ABSOLUTE position in light space, quantize its xy to
        // whole texels, and apply the residual as a light-space translation correction. Because the
        // matrix is camera-relative (the camera IS the origin), the snap manifests as translating
        // the ortho window by the sub-texel remainder, so the window's world-space texel grid stays
        // fixed while the player moves within a texel.
        //
        // The remainder must be SUBTRACTED from the window bounds, not added: shifting the window by
        // +remainder would move the window's own origin by the fractional part again, cancelling the
        // snap instead of applying it. The window has to move by -remainder so that camLight's
        // quantized (floor) position -- not its raw position -- sits at the window center.
        float texelSize = (2.0f * shadowDistance) / shadowResolution;
        // camX/Y/Z are absolute (not camera-relative), so this transform runs in double: a float
        // cast here loses sub-texel precision at large world coordinates. eye/up promote losslessly.
        Matrix4d lightViewD = new Matrix4d().setLookAt(eye.x, eye.y, eye.z, 0.0, 0.0, 0.0, up.x, up.y, up.z);
        Vector3d camLight = lightViewD.transformPosition(camX, camY, camZ, new Vector3d());
        double lx = camLight.x, ly = camLight.y;
        float snapX = (float) (lx - Math.floor(lx / texelSize) * texelSize);
        float snapY = (float) (ly - Math.floor(ly / texelSize) * texelSize);

        Matrix4f lightProj = new Matrix4f().setOrtho(
                -shadowDistance - snapX, shadowDistance - snapX,
                -shadowDistance - snapY, shadowDistance - snapY,
                0.0f, 2.0f * depthHalfExtent,
                true /* zZeroToOne -- matches the engine's [0,1] depth convention */);

        Matrix4f viewProj = new Matrix4f(lightProj).mul(lightView);
        return new LightMatrices(lightProj, lightView, viewProj);
    }
}
