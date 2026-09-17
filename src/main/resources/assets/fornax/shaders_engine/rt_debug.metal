#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;
using namespace metal::raytracing;

// Engine-owned scene debug trace for the Metal ray tracing spike. One thread per output pixel:
// builds a genuine primary ray from the camera through this pixel and does a full closest-hit
// trace against the frame's instance acceleration structure, then colors the pixel by whichever
// mode was picked, rather than tracing a shadow ray off a G-buffer surface the way rt_trace does.
//
// Ray reconstruction reuses rt_trace.metal's own invProjModelView convention for consistency,
// simplified for a primary ray: the eye sits at camera-relative (0, 0, 0) by construction (this
// engine renders camera-relative, with the camera's own translation folded out of modelView), so
// the ray origin is the camera's own position in grid space, handed in already computed
// (camAbs - firstSectionTimes16), and the ray direction is the DIFFERENCE of two unprojected
// points on this pixel's view ray, near minus far.
//
// Two points, not one. The argument that any single point along a perspective ray gives the same
// direction from the eye assumes the eye sits exactly at camera-relative (0, 0, 0). View bobbing
// breaks that: the bob is a small translation and rotation carried inside modelView, and it is not
// folded out the way the camera's own translation is, so the true eye is offset by a few
// hundredths of a block. Against a near plane only centimetres away that offset is a large
// fraction of the distance, and normalising the near point as if from the origin swings the
// direction by degrees every step, so the whole view shakes and throws itself off screen. The
// difference of two points on the same ray cancels the eye offset exactly, whatever it is.
//
// rt_sun_depth and rt_mesh_shadow have always built their rays this way. This kernel was the one
// place taking a shortcut.
//
// Modes: 0 hit/miss, 1 distance heatmap, 2 normal (placeholder, see below), 3 instance id hash,
// 4 primitive id hash, 5 ray direction. Every mode but 0 writes black on a miss; 0 uses red for
// miss and green for hit instead, since hit/miss is the one mode where miss is itself the answer.
//
// Mode 2 (normal) reads the hit primitive's face from the per-triangle primitive-data word
// rt_expand.metal writes (voxelIndex | face<<12 | paletteIndex<<16), decoded by rt_face_normal.metal
// which is prepended to this kernel at compile time. No bindless MTLResourceID table is needed to
// reach that buffer: it was attached to the geometry descriptor when the primitive acceleration
// structure was built, so Metal serves it per hit through the intersector's own primitive_data tag,
// the same route rt_trace.metal already takes for its cutout UVs. The face is exact and axis
// aligned, so no vertex fetch or interpolation is involved. A surface whose face field names
// nothing, notably RtSectionGeometry's exact-triangle supplement, still paints magenta.

// Byte layout of the constant buffer this kernel reads at buffer(1) (matched on the Java side that
// fills it, RtDebugMode's shaderMode() supplying `mode`):
//   0   invProjModelView   float4x4, column-major                          64 bytes
//   64  camPosGrid         float4, xyz used, w unused (camAbs - first*16)  16 bytes
//   80  maxDistance        float                                            4 bytes
//   84  mode               uint                                             4 bytes
//   88  size               uint2 (output width, height)                     8 bytes
//   96  total
//
// camPosGrid is carried as a float4 so its slot is a full 16 bytes on both sides, the same
// vec3-then-scalar footgun avoidance rt_trace.metal's own layout comment describes; mode sits
// right after maxDistance rather than before the trailing uint2, keeping that pair naturally
// 8-byte aligned with no manual padding needed.
struct RtDebugConstants {
    float4x4 invProjModelView;
    float4 camPosGrid;
    float maxDistance;
    uint mode;
    uint2 size;
};

constant uint MODE_HIT_MISS = 0u;
constant uint MODE_DISTANCE = 1u;
constant uint MODE_NORMAL = 2u;
constant uint MODE_INSTANCE_ID = 3u;
constant uint MODE_PRIMITIVE_ID = 4u;
constant uint MODE_RAY_DIRECTION = 5u;

// Three authored gradient stops for the distance heatmap: near reads cool, far reads hot, a
// familiar "thermal" ordering with no meaning beyond letting distance bands be told apart on sight.
constant float3 HEATMAP_NEAR = float3(0.0, 0.0, 1.0);
constant float3 HEATMAP_MID = float3(0.0, 1.0, 0.0);
constant float3 HEATMAP_FAR = float3(1.0, 0.0, 0.0);

// Authored, independent of the ray's own max trace distance (MetalRtShadowPass.MAX_DISTANCE, 512,
// sized to clear the loaded window's full diagonal with headroom): most hits land well under that
// cap, so normalizing the heatmap against it left the hot half of the gradient never shown. 128
// keeps the gradient spanning the range hits actually land in.
constant float DEBUG_HEATMAP_DISTANCE = 128.0;

inline float3 heatmap(float t) {
    t = clamp(t, 0.0, 1.0);
    return t < 0.5 ? mix(HEATMAP_NEAR, HEATMAP_MID, t * 2.0)
                    : mix(HEATMAP_MID, HEATMAP_FAR, (t - 0.5) * 2.0);
}

// Plain multiply-xor-shift integer hash (a bit-mixing shape common to general-purpose integer
// hashes, chosen only for well-distributed low bits, no cryptographic purpose or specific paper
// behind the exact constants) turning an id into a stable pseudo-random colour: a common way to
// make many ids visually distinct with no ordering implied between them.
inline float3 hash_color(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return float3(float(x & 0xFFu), float((x >> 8u) & 0xFFu), float((x >> 16u) & 0xFFu)) / 255.0;
}

kernel void rt_debug(
        instance_acceleration_structure accelerationStructure [[buffer(0)]],
        constant RtDebugConstants& constants [[buffer(1)]],
        texture2d<float, access::write> debugOut [[texture(0)]],
        uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= constants.size.x || gid.y >= constants.size.y) {
        return;
    }

    float2 texCoord = (float2(gid) + 0.5) / float2(constants.size);
    float2 ndc = texCoord * 2.0 - 1.0;
    // Reversed-Z: 1.0 is the near plane and 0.0 the far one.
    float4 nearClip = constants.invProjModelView * float4(ndc, 1.0, 1.0);
    float4 farClip = constants.invProjModelView * float4(ndc, 0.0, 1.0);
    float3 extent = farClip.xyz / farClip.w - nearClip.xyz / nearClip.w;
    float extentLength = length(extent);
    // A degenerate projection has no ray to trace; writing black beats normalising a zero vector.
    if (!(extentLength > 0.0) || !all(isfinite(extent))) {
        debugOut.write(float4(0.0, 0.0, 0.0, 1.0), gid);
        return;
    }
    float3 direction = extent / extentLength;
    float3 origin = constants.camPosGrid.xyz;

    // intersection_query rather than the intersector convenience API: only the query form exposes
    // get_committed_primitive_data(), which MODE_NORMAL needs, and this Metal version has no
    // primitive_data tag for the intersector. Same form rt_trace.metal uses, for the same reason.
    ray r(origin, direction, 0.0, constants.maxDistance);
    intersection_params params;
    params.set_triangle_cull_mode(triangle_cull_mode::none);
    params.assume_geometry_type(geometry_type::triangle);
    intersection_query<triangle_data, instancing> query;
    query.reset(r, accelerationStructure, params);
    // Opaque geometry auto-commits, so this runs to completion once and leaves the closest hit
    // committed. Empty body on purpose.
    while (query.next()) {
    }
    bool didHit = query.get_committed_intersection_type() != intersection_type::none;

    float3 color = float3(0.0);
    switch (constants.mode) {
        case MODE_HIT_MISS:
            color = didHit ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
            break;
        case MODE_DISTANCE:
            color = didHit ? heatmap(query.get_committed_distance() / DEBUG_HEATMAP_DISTANCE) : float3(0.0);
            break;
        case MODE_NORMAL: {
            // Remapped from [-1,1] to [0,1] the way MODE_RAY_DIRECTION remaps its own vector, so
            // the six axes read as six flat constant colours: a face whose shading varies across it
            // means the decode is reading interpolated data it should not be.
            device const uint* primitiveData = didHit
                    ? (device const uint*) query.get_committed_primitive_data() : nullptr;
            float3 normal = primitiveData != nullptr ? rt_face_normal(*primitiveData) : float3(0.0);
            // Magenta means "hit a surface this cannot name a face for". That is a real answer:
            // RtSectionGeometry's exact-triangle supplement carries UVs, not one of the six faces.
            color = !didHit ? float3(0.0)
                  : rt_face_normal_unknown(normal) ? float3(1.0, 0.0, 1.0)
                  : normal * 0.5 + 0.5;
            break;
        }
        case MODE_INSTANCE_ID:
            // +1u: hash_color(0) lands on pure black, indistinguishable from this mode's own
            // black-on-miss, so id 0 (a real, valid instance) would otherwise read as a miss.
            color = didHit ? hash_color(query.get_committed_instance_id() + 1u) : float3(0.0);
            break;
        case MODE_PRIMITIVE_ID:
            color = didHit ? hash_color(query.get_committed_primitive_id() + 1u) : float3(0.0);
            break;
        case MODE_RAY_DIRECTION:
            color = didHit ? (direction * 0.5 + 0.5) : float3(0.0);
            break;
        default:
            break;
    }

    debugOut.write(float4(color, 1.0), gid);
}
