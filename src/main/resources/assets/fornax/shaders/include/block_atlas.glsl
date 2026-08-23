// Paged block atlas sampling (M13 phase 3). The block atlas may carry more sprites than one
// GPU-sized page: page 0 is the vanilla atlas texture, whose bottom-quarter GHOST STRIP holds a
// quarter-scale copy of every spilled sprite, and the full-resolution truth lives on the
// u_BlockPagesTex array texture (one layer per overflow page). Model-baked UVs always point at
// page 0 -- a real page-0 rect, or a ghost rect inside the strip -- so a shader that never calls
// these helpers still renders every sprite correctly, merely soft for spilled ones. The helpers
// upgrade a ghost sample to the full-resolution layer by PURE ARITHMETIC:
//
//   the strip occupies v in [0.75, 1); cell k (k = 0,1,2) holds overflow page k+1 at
//   quarter scale; pageUV = (uv - vec2(0.25 * k, 0.75)) * 4.0, layer = k.
//
// Cell 3 is the ANIMATED cell: spilled animated sprites' ghosts live there, kept animating by
// vanilla's own per-frame blit, and are NEVER remapped -- their overflow placement is not even
// composited. Hence the `uv.x < 0.75` half of the strip test below.
//
// FORNAX_ATLAS_OVERFLOW_PAGES is engine-injected per terrain compile (see Fornax's
// ShaderChunkRendererConstantsMixin); at 0 (unpaged atlas -- the common case) every helper
// collapses to the plain page-0 sample and u_BlockPagesTex is never declared, so this include
// costs nothing to import unconditionally.
//
// Derivatives: dFdx/dFdy are taken on the INCOMING uv before any branch-dependent math and scaled
// by the ghost factor, so filtering across the remap matches the ghost's own screen-space
// footprint. Call these from uniform control flow (the ordinary per-fragment sample site); for
// loops that must sample repeatedly (a POM march), hoist fornax_blockAtlasGrad* once and use
// textureGrad directly.

#ifndef FORNAX_ATLAS_OVERFLOW_PAGES
#define FORNAX_ATLAS_OVERFLOW_PAGES 0
#endif

#if FORNAX_ATLAS_OVERFLOW_PAGES > 0

uniform sampler2DArray u_BlockPagesTex;
// The labPBR sidecar lanes' overflow layers, engine-built at the same generation and the same
// scale as their page-0 atlases (u_NormalTex / u_MaterialTex), so textureSize-derived LOD math
// carries over unchanged; remap uv exactly as for albedo (same strip, same cells, same affine)
// and scale gradients by 4 like the albedo helpers do.
uniform sampler2DArray u_NormalPagesTex;
uniform sampler2DArray u_MaterialPagesTex;

// Sample-provenance diagnostic (engine-injected define, live-toggled by the Fornax debug keybind):
// red = overflow-layer sample, yellow = ghost sample; page-0 samples stay untinted (including the
// animated cell, which deliberately never remaps).
#ifdef FORNAX_ATLAS_DEBUG_TINT
#define FORNAX_ATLAS_TINT_LAYER(c) vec4(mix((c).rgb, vec3(1.0, 0.1, 0.1), 0.4), (c).a)
#define FORNAX_ATLAS_TINT_GHOST(c) vec4(mix((c).rgb, vec3(1.0, 1.0, 0.1), 0.4), (c).a)
#else
#define FORNAX_ATLAS_TINT_LAYER(c) (c)
#define FORNAX_ATLAS_TINT_GHOST(c) (c)
#endif

// True when uv is a STATIC ghost rect -- strip row, cells 0..2. (Page-0 sprites cannot reach the
// strip: their stitch is capped at three-quarter height. The animated cell, x >= 0.75, stays a
// plain page-0 sample so its vanilla-driven animation shows.)
bool fornax_isOverflowGhostUv(vec2 uv) {
    return uv.y >= 0.75 && uv.x < 0.75;
}

// The overflow layer coordinate for a static ghost uv. layer receives the 0-based array layer.
vec2 fornax_overflowPageUv(vec2 uv, out float layer) {
    float cell = floor(uv.x * 4.0);
    layer = cell;
    return (uv - vec2(cell * 0.25, 0.75)) * 4.0;
}

// Full-resolution block-atlas sample with caller-owned gradients (the POM convention: gradients
// of the ORIGINAL interpolated UV, even when uv itself was marched): page 0 outside the static
// ghost cells, the overflow layer inside them.
vec4 fornax_sampleBlockAtlasGrad(sampler2D blockTex, vec2 uv, vec2 gradX, vec2 gradY) {
    if (!fornax_isOverflowGhostUv(uv)) {
        return textureGrad(blockTex, uv, gradX, gradY);
    }
    float layer;
    vec2 pageUv = fornax_overflowPageUv(uv, layer);
    return FORNAX_ATLAS_TINT_LAYER(
            textureGrad(u_BlockPagesTex, vec3(pageUv, layer), gradX * 4.0, gradY * 4.0));
}

// Distance-graded sample: ghostShare 0 = full resolution, 1 = the quarter-scale ghost (what a
// plain texture() would return). Lets a pack keep full 512x within a few chunks of the camera and
// ease spilled sprites down to ghost resolution farther out -- the blend band pays for both
// samples, everything else pays for one.
vec4 fornax_sampleBlockAtlasBlendGrad(sampler2D blockTex, vec2 uv, vec2 gradX, vec2 gradY,
                                      float ghostShare) {
    if (!fornax_isOverflowGhostUv(uv)) {
        return textureGrad(blockTex, uv, gradX, gradY);
    }
    float share = clamp(ghostShare, 0.0, 1.0);
    if (share >= 1.0) {
        return FORNAX_ATLAS_TINT_GHOST(textureGrad(blockTex, uv, gradX, gradY));
    }
    float layer;
    vec2 pageUv = fornax_overflowPageUv(uv, layer);
    vec4 full = FORNAX_ATLAS_TINT_LAYER(
            textureGrad(u_BlockPagesTex, vec3(pageUv, layer), gradX * 4.0, gradY * 4.0));
    if (share <= 0.0) {
        return full;
    }
    return mix(full, FORNAX_ATLAS_TINT_GHOST(textureGrad(blockTex, uv, gradX, gradY)), share);
}

vec4 fornax_sampleBlockAtlas(sampler2D blockTex, vec2 uv) {
    return fornax_sampleBlockAtlasGrad(blockTex, uv, dFdx(uv), dFdy(uv));
}

vec4 fornax_sampleBlockAtlasBlend(sampler2D blockTex, vec2 uv, float ghostShare) {
    return fornax_sampleBlockAtlasBlendGrad(blockTex, uv, dFdx(uv), dFdy(uv), ghostShare);
}

#else

vec4 fornax_sampleBlockAtlasGrad(sampler2D blockTex, vec2 uv, vec2 gradX, vec2 gradY) {
    return textureGrad(blockTex, uv, gradX, gradY);
}

vec4 fornax_sampleBlockAtlasBlendGrad(sampler2D blockTex, vec2 uv, vec2 gradX, vec2 gradY,
                                      float ghostShare) {
    return textureGrad(blockTex, uv, gradX, gradY);
}

vec4 fornax_sampleBlockAtlas(sampler2D blockTex, vec2 uv) {
    return texture(blockTex, uv);
}

vec4 fornax_sampleBlockAtlasBlend(sampler2D blockTex, vec2 uv, float ghostShare) {
    return texture(blockTex, uv);
}

#endif
