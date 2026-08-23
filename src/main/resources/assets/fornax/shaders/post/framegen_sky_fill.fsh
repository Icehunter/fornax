#version 330

// Frame generation UNIFIED real-frame fill: MTLFXFrameInterpolator has no valid ground truth for
// three classes of generated-frame pixel, each fixed here by replacing the generated pixel with the
// CURRENT real frame's own color -- correct wherever the two frames have effectively zero parallax
// (sky), or where "correct" simply means no motion-vector-based reprojection was ever possible for
// that pixel at all (responsive/edge). One shader, one pass (this file was originally sky-only; see
// FrameGenSkyFillPass's own header for why it was extended in place instead of adding a second pass).
//
//   1. SKY: reversed-Z depth at the far-plane clear value -- sky/clouds never write gMotion (only
//      terrain does), so the interpolator hallucinates dither/stipple there. Unchanged from the
//      original sky-only version; see SKY_DEPTH_EPSILON below.
//
//   2. RESPONSIVE PIXELS: vanilla-drawn content with no motion vectors at all -- particles, the
//      first-person hand, translucent overlays -- identified by the SAME scene-depth-vs-G-buffer-depth
//      mismatch post/metalfx_reactive_mask.fsh already uses to build MTLFXTemporalScaler's own
//      reactive mask: u_SceneDepth is the render-res off-screen target's OWN depth attachment (what
//      vanilla actually rasterized this pixel to), u_GBufferDepth is the deferred G-buffer's opaque
//      terrain depth (what gMotion was written against). See RESPONSIVE_DEPTH_EPSILON below for the
//      reused threshold, cited by file:line.
//
//   3. EDGE DISOCCLUSION: content the interpolator's reprojection has no source pixel for, because
//      that source would have to lie outside the frame:
//        (a) a border band (FILL_EDGE_MARGIN_PX, native-res) around all four screen edges, gated on
//            actual per-pixel motion (a static scene has nothing disoccluding, so it keeps full
//            interpolation all the way to the edge). Feathered over FILL_EDGE_FEATHER_PX at the
//            band's inner boundary so the fill doesn't leave a visible seam line.
//        (b) any pixel whose reprojected source UV falls outside [0,1], checked in BOTH directions.
//            This engine's motion convention (gMotion = currentUV - previousUV, consumers reproject
//            via prevUV = uv - motion -- see MetalFxUpscalePass.java:36-37 and this same convention
//            note in FrameGenPass.java's own header) means `uv - motion` is the standard BACKWARD
//            reprojection source: if it falls outside [0,1], this pixel's own content entered from
//            off-screen since last frame (classic disocclusion) and the interpolator has nothing to
//            reproject it from. `uv + motion` (FORWARD) is checked too, for the symmetric case: content
//            currently near this pixel that is moving OFF-screen by next frame has no future sample to
//            interpolate TOWARD either, which produces the identical class of artifact (the
//            interpolator's blend has a real sample on only one side of the pair, not both) -- so both
//            directions are ORed rather than only the backward one.
//      This sub-class is inherently edge-adjacent already (a pixel's prevUV/nextUV can only leave
//      [0,1] within |motion| of an edge to begin with), so (b) needs no separate margin gate.
//
// Blend state: the original sky-only version wrote opaque (no blend, `discard` for the non-sky case).
// This version keeps that `discard` for "matches none of the three classes" (the overwhelming common
// case -- zero blend work), but classes 1/2/3b still write alpha=1.0 (full replace, identical to the
// old opaque-overwrite behavior) while class 3a's edge-band feather writes a partial alpha; the pass's
// ColorTargetState now carries a straight (non-premultiplied) alpha-over blend function to make that
// partial write possible (see FrameGenSkyFillPass for the exact BlendFunction).
uniform sampler2D u_GBufferDepth; // render-res, reversed-Z deferred G-buffer depth (what gMotion matches)
uniform sampler2D u_SceneDepth;   // render-res, reversed-Z off-screen target's own depth (what vanilla drew)
uniform sampler2D u_Motion;       // render-res, RG16F UV-delta (currentUV - previousUV)
uniform sampler2D u_RealColor;    // native-res, the CURRENT frame's own resolved (scene-only) color

in vec2 texCoord;
out vec4 fragColor;

// ---- Class 1: sky/far-depth ----
// Reversed-Z far-plane clear value is exactly 0.0 (RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE); a small
// epsilon absorbs float round-trip noise from the depth copy chain without ever catching real
// close-range geometry (reversed-Z packs distant geometry into a razor-thin band just above 0.0, but
// "distant terrain" still writes a nonzero depth -- only truly untouched sky/void pixels sit at the
// exact clear value).
#define SKY_DEPTH_EPSILON 0.0000001

// ---- Class 2: responsive pixels ----
// == post/metalfx_reactive_mask.fsh:9's HAND_DEPTH_EPSILON, reused verbatim: sceneDepth - gbufferDepth
// >= this means vanilla drew something at this pixel the deferred G-buffer (and therefore gMotion)
// never saw at all. That shader's own FIRST_PERSON_PROXIMITY_DEPTH/TRANSLUCENT_REACTIVE_STRENGTH
// constants (metalfx_reactive_mask.fsh:10-11) tune how STRONGLY MTLFXTemporalScaler should reject
// HISTORY at such a pixel (full reject for first-person, half-strength for other translucent
// overlays so animated water isn't averaged against the static terrain motion underneath it) -- not
// needed here, where the only question is binary ("does this pixel have a usable motion vector at
// all"), which HAND_DEPTH_EPSILON alone already answers: metalfx_reactive_mask.fsh:16's
// translucentOverlay term, which that shader's own firstPerson term (line 17) is itself gated behind,
// so translucentOverlay > 0 already covers both cases as a single boolean predicate.
#define RESPONSIVE_DEPTH_EPSILON 0.005

// ---- Class 3: edge disocclusion ----
#define FILL_EDGE_MARGIN_PX 16.0        // native-res border band width (per-edge)
#define FILL_EDGE_FEATHER_PX 3.0        // smoothstep width at the band's inner boundary
#define FILL_EDGE_MOTION_EPSILON_PX 0.5 // below this apparent native-res motion, treat as static

void main() {
    // Native resolution, read off u_RealColor (native-res by construction -- see FrameGenPresenter):
    // both the edge margin and the motion-to-pixels conversion below are deliberately expressed in
    // this same basis so FILL_EDGE_MARGIN_PX means the same visible distance regardless of render
    // scale (SSAA/TAAU), per this pass's own resolution-independence requirement.
    vec2 resolution = vec2(textureSize(u_RealColor, 0));

    float gbufferDepth = texture(u_GBufferDepth, texCoord).r;
    float sceneDepth = texture(u_SceneDepth, texCoord).r;
    vec2 motion = texture(u_Motion, texCoord).rg;

    // ---- Class 1 ----
    bool isSky = gbufferDepth <= SKY_DEPTH_EPSILON;

    // ---- Class 2 (mirrors metalfx_reactive_mask.fsh's translucentOverlay term) ----
    bool isResponsive = (sceneDepth - gbufferDepth) >= RESPONSIVE_DEPTH_EPSILON;

    // ---- Class 3a: border band, feathered, gated on per-pixel motion ----
    vec2 distToEdgeAxes = min(texCoord, 1.0 - texCoord) * resolution;
    float distToEdgePx = min(distToEdgeAxes.x, distToEdgeAxes.y);
    float motionPx = length(motion * resolution);
    float bandWeight = 1.0 - smoothstep(
            FILL_EDGE_MARGIN_PX - FILL_EDGE_FEATHER_PX, FILL_EDGE_MARGIN_PX, distToEdgePx);
    bandWeight *= step(FILL_EDGE_MOTION_EPSILON_PX, motionPx);

    // ---- Class 3b: reprojection source out of [0,1], both directions ----
    vec2 prevUv = texCoord - motion;
    vec2 nextUv = texCoord + motion;
    bool reprojectionOutOfBounds =
            any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))
            || any(lessThan(nextUv, vec2(0.0))) || any(greaterThan(nextUv, vec2(1.0)));

    float hardWeight = (isSky || isResponsive || reprojectionOutOfBounds) ? 1.0 : 0.0;
    float weight = max(bandWeight, hardWeight);
    if (weight <= 0.0) {
        discard;
    }
    fragColor = vec4(texture(u_RealColor, texCoord).rgb, weight);
}
