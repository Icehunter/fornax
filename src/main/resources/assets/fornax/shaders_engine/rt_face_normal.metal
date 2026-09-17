#include <metal_stdlib>
using namespace metal;

// Carries its own include and using-directive because it is CONCATENATED AHEAD of the kernel that
// consumes it, so the kernel's own header lines have not been seen yet when this code is parsed.
// Both are idempotent, so the kernel repeating them below is harmless.

// Shared prelude: the one place the engine turns a hit triangle into its surface normal.
// Prepended to rt_ray_query.metal and rt_debug.metal at compile time (MetalRtShaders.compileKernel),
// the same concatenation rt_sun_depth.metal already uses to reach rt_trace's helpers, because MSL
// resources here have no include path of their own.
//
// Every geometry record in this engine starts with the same surface word, which is what lets one
// decode serve all of them. rt_expand.metal writes it as
//     voxelIndex | face << 12 | paletteIndex << 16
// and Metal serves that word back per hit through get_committed_primitive_data(), because the
// buffer was attached to the geometry descriptor at build time. No bindless resource table is
// needed to reach it.
//
// Where a record keeps its UVs is read off the same word, and only off the same word:
//     bit 31 set: six UV floats follow at byte 4  (RtSectionGeometry's exact-triangle supplement)
//     bit 30 set: six UV floats follow at byte 8  (MeshShadowPrimitive, an uploaded chunk mesh)
//     neither:    the record is one word and has no UVs (an rt_expand voxel box)
// A reader that guesses a fixed offset instead reads a neighbouring triangle's word as a texture
// coordinate, which samples a real texel and looks like a shading bug rather than a decode one.
//
// Face order is Minecraft's Direction order, which rt_expand's own face_corners switch follows:
// 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. A voxel face is axis aligned, so its normal is
// exact rather than interpolated, and no vertex fetch is involved.
//
// Silent-failure note: a face field outside 0..5 returns a ZERO vector, not a guess. Callers must
// treat a zero-length normal as "unknown" and not normalize it. RtSectionGeometry's exact-triangle
// supplement (bit 31) carries rendered geometry whose orientation is not one of these six faces,
// so it stores 6 and lands on the zero vector. A mesh record (bit 30) does name a face: the quad
// it came from is axis aligned and FornaxChunkVertex already derived its direction.
inline float3 rt_face_normal(uint packed) {
    uint face = (packed >> 12u) & 0xFu;
    switch (face) {
        case 0u: return float3(0.0, -1.0, 0.0);
        case 1u: return float3(0.0, 1.0, 0.0);
        case 2u: return float3(0.0, 0.0, -1.0);
        case 3u: return float3(0.0, 0.0, 1.0);
        case 4u: return float3(-1.0, 0.0, 0.0);
        case 5u: return float3(1.0, 0.0, 0.0);
        default: return float3(0.0);
    }
}

/** True when rt_face_normal could not name a face for this hit. */
inline bool rt_face_normal_unknown(float3 normal) {
    return dot(normal, normal) < 0.5;
}
