#version 330 core

// Fornax FALLBACK terrain vertex stage, used whenever no shaderpack graph is active. The engine
// owns the chunk vertex format unconditionally (FornaxChunkVertex is installed by a class-init
// @Redirect and cannot be toggled off at runtime), so it must also own a terrain shader able to
// read that format -- stock Sodium's own shader declares CompactChunkVertex's packed-uint layout
// and can never compile against Fornax's attribute formats (live-caught on MoltenVK:
// "a_Position(0) of type uint2 cannot be read using MTLAttributeFormatUShort4Normalized").
// Compiled WITHOUT USE_DEFERRED in fallback mode: forward-only, single color target.
//
// Fornax terrain vertex stage. Decodes Fornax's own vertex format (chunk_vertex.glsl), places the
// chunk-local position into world space, and builds everything the fragment stage needs for both
// the forward (translucent) and deferred (opaque/cutout, USE_DEFERRED) output paths: lit vertex
// color, fog distance, the flat face normal + its cardinal index, the approximate sun direction,
// the per-vertex block-light level, and a jitter-corrected screen-space motion vector.

#moj_import <fornax:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <fornax:chunk_vertex.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;
out vec3 v_SunDirection;
out vec3 v_FaceNormal;

// Raw 0-5 direction index this quad's normal was decoded from (see chunk_vertex.glsl's
// FORNAX_FACE_NORMALS table). Forwarded so the deferred fragment stage can pack it into the
// G-buffer normal attachment's alpha channel -- the resolve pass has no per-vertex varyings of its
// own, only G-buffer textures, so the flat index has to ride along as data instead.
flat out int v_FaceNormalIndex;

// Block-light level at this vertex, 0 (unlit) to ~1 (max block light). Lets the deferred resolve
// keep ambient occlusion from darkening self-emitting surfaces (lava, glowstone, lanterns) --
// LabPBR material maps commonly carry no emission data of their own, so block light is the only
// reliable "this surface emits" signal available.
out float v_BlockLight;

// Screen-space UV delta (this frame minus last frame, both post perspective-divide) for TAA/SSR
// history reprojection. Computed unconditionally, same as the outputs above, so the vertex stage's
// output set never changes shape between the forward and deferred fragment variants.
out vec2 v_MotionVector;

// Unguarded: terrain.fsh declares both as plain `in`, so a guard here would make the stage
// interface depend on a define only one stage sees.
out vec2 v_FragDistance;
out float v_FadeFactor;

uniform isamplerBuffer u_SectionTimeInfo;

// Vulkan restricts a shader stage to one push_constant block; the sun direction and previous
// region offset ride alongside the base region offset/frame time/region id here rather than in a
// second block, then get forwarded to the fragment stage as ordinary varyings.
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

uniform sampler2D u_LightTex;

// A region groups sections into an 8x4x8 grid; _draw_id encodes a section's position within that
// grid (bits 0-4 = X, bits 5-6 = Y, bits 7-9 = Z -- the shift/mask pair below reads exactly that),
// which is the protocol the engine's region-management code (RenderRegion) uses to place draws.
// This decode has to match that indexing exactly or a draw lands in the wrong 16-block cell.
// Bit layout dictated by Sodium's RenderRegion section indexing.
uvec3 sectionGridCoord(uint drawId) {
    return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 sectionWorldOffset(uint drawId) {
    return sectionGridCoord(drawId) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 worldPosition = u_RegionOffset + sectionWorldOffset(_draw_id) + _vert_position;

    v_FragDistance = getFragDistance(worldPosition);

    // Both times are milliseconds from the owning region's creation, so the epochs match. A slot
    // reads negative until that section's mesh uploads; negative means settled.
    int sectionSlot = int((u_RegionID * 256u) + uint(_draw_id));
    int sectionBuiltAt = texelFetch(u_SectionTimeInfo, sectionSlot).r;
    float fadeProgress = clamp(float(u_CurrentTime - sectionBuiltAt) * u_FadePeriodInv, 0.0, 1.0);
    v_FadeFactor = (sectionBuiltAt < 0) ? 1.0 : fadeProgress;

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(worldPosition, 1.0);

    // Reproject the same vertex using last frame's region offset and camera matrices (all
    // snapshotted by PreviousFrameCameraTransform/UniformBufferManagerMixin before this frame's
    // camera moved) to recover where it rendered a frame ago.
    vec3 previousWorldPosition = u_PrevRegionOffset + sectionWorldOffset(_draw_id) + _vert_position;
    vec4 previousClipPosition = u_PrevProjectionMatrix * u_PrevModelViewMatrix * vec4(previousWorldPosition, 1.0);

    // TAA's jitter is baked into both frames' projection matrices as a constant NDC-space offset
    // (see CameraJitter/GameRendererMixin) -- subtracting each frame's own jitter offset before the
    // UV remap cancels it out losslessly, leaving only genuine scene motion in the result.
    vec2 currentNdc = (gl_Position.xy / gl_Position.w) - u_JitterOffset;
    vec2 previousNdc = (previousClipPosition.xy / previousClipPosition.w) - u_PrevJitterOffset;
    v_MotionVector = (currentNdc * 0.5 + 0.5) - (previousNdc * 0.5 + 0.5);

    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
    v_BlockLight = _vert_tex_light_coord.x;
    v_TexCoord = _vert_tex_diffuse_coord;

    v_SunDirection = u_SunDirection;

    // The chunk transform above is translation-only, so the model-space face normal already
    // points the right way in world space; no normal matrix is needed.
    v_FaceNormal = _vert_face_normal;
    v_FaceNormalIndex = int(a_Normal.x);
}
