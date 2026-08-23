#version 330 core

// Fornax sun/moon shadow-pass terrain fragment stage: depth-only, engine-owned minimal plumbing
// (like the debug blit), not pack content, for v1. Writes nothing -- depth writes implicitly via
// the fixed-function depth test/write state every render pass already carries, the same as every
// other terrain pass's depth attachment; there is deliberately no color output declared here at
// all, matching the zero-color-attachment RenderPassDescriptor/pipeline
// (DefaultChunkRendererRenderPassMixin / ShaderChunkRendererDeferredPipelineMixin's shadow
// branches) this shader is always paired with.
//
// The only fragment-stage work is a hard alpha cutout so foliage/glass-style CUTOUT geometry
// doesn't cast a solid silhouette. v1 decision: this fixed 0.1 threshold applies unconditionally to
// every shadow-caster -- SOLID and CUTOUT terrain alike -- since the shadow pass draws all opaque
// geometry through the one shared FornaxRenderPasses.SHADOW TerrainRenderPass, unlike terrain.fsh's
// ALPHA_CUTOUT define which distinguishes SOLID (no cutout) from CUTOUT (0.5 threshold) per pass.
// Solid terrain's block textures don't carry sub-threshold alpha, so applying the test to SOLID
// geometry too is a no-op there in practice; revisit only if that assumption ever proves wrong.

in vec2 v_TexCoord;

uniform sampler2D u_BlockTex;

void main() {
    float alpha = texture(u_BlockTex, v_TexCoord).a;

    if (alpha < 0.1) {
        discard;
    }
}
