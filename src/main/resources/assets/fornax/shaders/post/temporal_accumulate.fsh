#version 330

// Engine-owned MID-GRAPH temporal accumulation -- the TEMPORAL pass type's program. Derived from
// post/reconstruct.fsh's ratio-1.0 arm, with the differences a mid-graph HDR placement forces:
//
//   * HDR source, HDR history, HDR output. The clamp box is seeded from the first neighborhood
//     sample rather than reconstruct.fsh's [0,1] LDR bounds -- there is no upper bound to seed
//     with in scene-referred light.
//   * The blend is KARIS-WEIGHTED (w = 1/(1+luma) on both sides, renormalized after). A raw HDR
//     mix flickers on bright sub-pixel content (sun glints, emissive specks): the frames where the
//     jittered raster catches the spark dominate the average and the pixel strobes at the jitter
//     period. Weighting by inverse luma is the standard fix -- it is exactly reconstruct.fsh's
//     behaviour in the limit of LDR-range inputs, so terrain converges identically.
//   * NO first-person mask and NO u_SceneDepth: this pass runs INSIDE the graph, before vanilla
//     draws the hand/held items/screen overlays, so first-person content never enters the
//     accumulator at all -- structurally excluded rather than masked. (The presented frame still
//     gets a hand: vanilla draws it onto the pack's output after the graph finishes.)
//   * NO translucent-overlay tier: it is TAAU-only in reconstruct.fsh, and this pass runs only at
//     ratio 1.0 by construction (input and output are validator-enforced to the same shape).
//
// Kept identical, deliberately: the 3x3 neighborhood clamp, the point motion fetch, the
// bounds + depth-similarity validity test at the same 0.05 threshold, the sky-reprojection
// fallback for depth-cleared pixels, the confidence ramp min(age/(age+1), u_BlendFactor), and the
// clamp-shift age reset. The settings UBO layout is byte-identical to u_ReconstructSettings so the
// Java side reuses one proven std140 builder (u_Sharpen and u_RatioIsOne ride along unused).
//
// WHY THIS PASS EXISTS: accumulating AFTER bloom lets a bright mover's halo hold the clamp box
// open along its own path -- history at passed pixels never crushes back to background, and every
// star drags a permanent comet tail (measured at +3 display codes, tools/verify_star_trails.py in
// the Plague repo; the same model measures this ordering at 0.0 codes). Accumulate-then-bloom also
// keeps the resolve linear: bloom reads a temporally converged frame rather than re-scattering the
// noise the accumulator is there to remove.
uniform sampler2D u_Source;  // this frame's HDR scene at the boundary (the temporal pass's input target)
uniform sampler2D u_History; // the OUTPUT target's HISTORY slot -- last frame's accumulation (pre-swap phase; see TemporalPassRunner)
uniform sampler2D u_Motion;  // render-res gMotion (currentUV - previousUV, jitter-corrected in terrain.vsh)
uniform sampler2D u_Depth;   // render-res G-buffer depth (reversed-Z)
uniform sampler2D u_SurfaceClass; // builtin.gAo; alpha = block entity 0, particle .25, cutout .5, entity .75, solid 1

layout(std140) uniform u_ReconstructSettings {
    vec2  u_SourceTexelSize;
    vec2  u_OutputTexelSize;
    vec2  u_JitterOffsetNdc;
    float u_BlendFactor;
    float u_Sharpen;     // unused here -- layout parity with reconstruct.fsh
    float u_RatioIsOne;  // unused here -- always 1.0 by validator construction
    mat4  u_SkyReprojection;
};

in vec2 texCoord;
out vec4 fragColor;

#define DISOCCLUSION_DEPTH_THRESHOLD 0.05
#define CONFIDENCE_FRAMES 32.0
#define SKY_DEPTH_EPSILON 0.0000001
// Midpoints around the animated-entity class 0.75. Static block entities remain class 0.
#define ENTITY_SURFACE_CLASS_MIN 0.625
#define ENTITY_SURFACE_CLASS_MAX 0.875

// Same homography as reconstruct.fsh's skyMotion -- see SkyReprojection.java for the derivation.
vec2 skyMotion(vec2 uv) {
    vec3 prevH = mat3(u_SkyReprojection) * vec3(uv * 2.0 - 1.0, 1.0);
    if (prevH.z <= 0.0) {
        return uv + 2.0; // behind the previous eye: force the bounds test to reject
    }
    return uv - ((prevH.xy / prevH.z) * 0.5 + 0.5);
}

float karisWeight(vec3 c) {
    return 1.0 / (1.0 + max(max(c.r, c.g), c.b));
}

void main() {
    vec3 currentColor = texture(u_Source, texCoord).rgb;

    // Neighborhood min/max for the history clamp -- seeded from the centre sample, not [0,1]:
    // HDR has no a-priori bounds.
    vec3 nmin = currentColor;
    vec3 nmax = currentColor;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec3 c = texture(u_Source, texCoord + vec2(float(x), float(y)) * u_SourceTexelSize).rgb;
            nmin = min(nmin, c);
            nmax = max(nmax, c);
        }
    }

    // Point motion fetch, with the sky-reprojection fallback for depth-cleared pixels -- the same
    // condition and the same reasoning as reconstruct.fsh (gMotion's cleared zero is the absence
    // of a measurement, not a measurement of zero motion).
    float motionDepth = texture(u_Depth, texCoord).r;
    vec2 motion = texture(u_Motion, texCoord).rg;
    if (motionDepth <= SKY_DEPTH_EPSILON) {
        motion = skyMotion(texCoord);
    }

    vec2 prevUv = texCoord - motion;
    bool valid = prevUv.x >= 0.0 && prevUv.x <= 1.0 && prevUv.y >= 0.0 && prevUv.y <= 1.0;
    if (valid) {
        float dFrag = texture(u_Depth, texCoord).r;
        float dPrev = texture(u_Depth, prevUv).r;
        if (abs(dFrag - dPrev) > DISOCCLUSION_DEPTH_THRESHOLD) valid = false;
    }

    vec4 hist = texture(u_History, prevUv);
    vec3 clampedHist = clamp(hist.rgb, nmin, nmax);
    // Animated entity geometry has no previous-frame pose. Its motion vector therefore contains
    // camera motion only, so reprojected history lands inside a different cloth/limb pose and
    // appears as light flashes or dark internal wisps. Render those classified pixels from the
    // current frame and reset their age. The depth gate keeps an unwritten attachment from ever
    // becoming responsive even if a future pack changes its clear value.
    float surfaceClass = texture(u_SurfaceClass, texCoord).a;
    bool responsiveEntity = motionDepth > SKY_DEPTH_EPSILON
            && surfaceClass > ENTITY_SURFACE_CLASS_MIN
            && surfaceClass < ENTITY_SURFACE_CLASS_MAX;
    float validF = valid && !responsiveEntity ? 1.0 : 0.0;
    float ageIn = hist.a * CONFIDENCE_FRAMES * validF;
    float weight = min(1.0 - 1.0 / (ageIn + 1.0), u_BlendFactor);

    // Karis-weighted blend: renormalized inverse-luma weighting on both terms. Equivalent to the
    // plain mix for in-range content; tames HDR flicker where it is not.
    float wCur = karisWeight(currentColor) * (1.0 - weight);
    float wHist = karisWeight(clampedHist) * weight;
    vec3 outColor = (currentColor * wCur + clampedHist * wHist) / max(wCur + wHist, 1e-6);

    // Clamp-event age reset, storage-only -- identical semantics to reconstruct.fsh. The 0.02/0.1
    // luma window is display-scaled there; in HDR the same absolute window is conservative (bright
    // content resets more readily), which errs toward responsiveness, not ghosting.
    float clampShift = smoothstep(0.02, 0.1, length(clampedHist - hist.rgb));
    // Store zero age as well as zero blend. If the entity vacates this pixel next frame, its stale
    // color must not come back at the confidence ramp's first 50% history step.
    float ageOut = responsiveEntity ? 0.0
            : min(ageIn * (1.0 - clampShift) + 1.0, CONFIDENCE_FRAMES);

    fragColor = vec4(outColor, ageOut / CONFIDENCE_FRAMES);
}
