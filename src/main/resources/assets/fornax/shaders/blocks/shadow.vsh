#version 330 core

// Fornax sun/moon shadow-pass terrain vertex stage: depth-only, engine-owned minimal plumbing (like
// the debug blit), not pack content, for v1 -- see ShaderChunkRendererShaderLocationMixin's header
// for why this is never routed to a pack's own shader. Reuses the exact same vertex decode and
// region-translation idiom terrain.vsh uses, since every chunk mesh in this engine is built in
// FornaxChunkVertex's format regardless of which TerrainRenderPass draws it (see
// chunk_vertex.glsl's header) -- there is no separate "shadow vertex format" to decode.
//
// The true update() model (see UniformBufferManagerMixin's append-site comment): Sodium's
// UniformBufferManager.update() is guarded by a hasUpdatedThisFrame flag reset once per frame --
// only the FIRST update() call each frame actually writes u_ProjectionMatrix/u_ModelViewMatrix;
// every later call this frame (including this shadow pass's own renderLayer calls, which run
// first, before SOLID/CUTOUT/TRANSLUCENT) is a no-op against those classic slots. Because every
// terrain draw this frame -- main camera AND shadow -- shares that one write, those classic slots
// MUST always carry the MAIN camera's matrices (see SodiumWorldRendererOrchestrationMixin, which
// now feeds the shadow renderLayer calls the main ChunkRenderMatrices, not the light's). The light
// transform instead rides u_SunViewProj, a frame-constant extension member appended unconditionally
// to every update() call (see globals.glsl / UniformBufferManagerMixin) -- committed by
// ShadowFrameState BEFORE this frame's first update(), so it's always this frame's fresh light
// matrix regardless of which update() call happens to be the one that actually writes the buffer.
// This shader therefore projects with u_SunViewProj directly, never u_ProjectionMatrix/
// u_ModelViewMatrix.

#moj_import <fornax:globals.glsl>
#moj_import <fornax:chunk_vertex.glsl>

// Atlas UV only -- shadow.fsh needs nothing else (no lit color, no normal, no motion vector; this
// pass writes depth only). There is no per-vertex "is this a cutout material" bit in the current
// vertex format (chunk_vertex.glsl's _material_params/a_LightAndData.z is decoded but unused
// engine-wide) to forward as a separate flag, so shadow.fsh's hard alpha<0.1 discard (documented
// there as a v1 decision) applies uniformly via this one UV instead of a second varying.
out vec2 v_TexCoord;

// Vulkan restricts a shader stage to one push_constant block; mirrors terrain.vsh's block exactly
// so this shader stays layout-compatible with the same FornaxPushConstants data every terrain draw
// call (including this one, since it goes through the same DefaultChunkRenderer.render() path)
// writes regardless of pass -- only u_RegionOffset is actually read below, the rest ride along
// unused, same as terrain.vsh's own unused fields do for whichever branch doesn't need them.
#ifdef VULKAN
layout(push_constant) uniform FornaxPushConstants {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
    vec3 u_SunDirection;
    vec3 u_PrevRegionOffset;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
uniform vec3 u_SunDirection;
uniform vec3 u_PrevRegionOffset;
#endif

// A region groups sections into an 8x4x8 grid; _draw_id encodes a section's position within that
// grid (bits 0-4 = X, bits 5-6 = Y, bits 7-9 = Z -- the shift/mask pair below reads exactly that).
// Identical decode to terrain.vsh's copy -- see that file for the full bit-layout rationale.
uvec3 sectionGridCoord(uint drawId) {
    return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 sectionWorldOffset(uint drawId) {
    return sectionGridCoord(drawId) * vec3(16.0);
}

void main() {
    _vert_init();

    // Camera-relative position (u_RegionOffset is camera-relative, same convention terrain.vsh
    // uses) -- u_SunViewProj is declared camera-relative too (see globals.glsl), so it composes
    // directly with this position with no extra translation.
    vec3 cameraRelativePos = u_RegionOffset + sectionWorldOffset(_draw_id) + _vert_position;

    gl_Position = u_SunViewProj * vec4(cameraRelativePos, 1.0);

    // Radial (polar) XY distortion -- the write-side half of the shadow-acne fix's two-part model
    // (distortion + the hardware comparison sampler, see ShadowCamera's class javadoc). Pushes
    // shadow-map texel density toward the map center (where the player camera looks -- see
    // ShadowCamera's texel-snapping doc), matched by sampleSunShadow's read-side warp on the pack
    // side (same u_ShadowMapParams.x bias, same formula -- see ShadowCamera.distortFactor's own
    // doc comment for the shared reference). u_ShadowMapParams.x is read directly from u_Globals,
    // never recomputed here. Depth is written UNSCALED: a z-scale constant used to sit here and
    // proved vestigial on a D32_FLOAT target (relative precision -- scaling both sides of the
    // comparison identically changes no outcome; ShadowCamera's class javadoc carries the full
    // argument), so the write and every read now share the plain [0,1] light-clip depth.
    float lVertexPos = length(gl_Position.xy);
    float distortFactor = lVertexPos * u_ShadowMapParams.x + (1.0 - u_ShadowMapParams.x);
    gl_Position.xy /= distortFactor;

    v_TexCoord = _vert_tex_diffuse_coord;
}
