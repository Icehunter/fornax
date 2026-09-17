#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;
using namespace metal::raytracing;

// Engine-owned general ray query: one thread per submitted ray, intersects the frame's instance
// acceleration structure and writes the closest hit. Unlike rt_trace, nothing here is tied to the
// sun: origin and direction come from the caller's buffer, which is the whole point of this kernel.
//
// Opaque closest-hit only. Cutout alpha testing needs the atlas, the palette, the face-texture
// table and the instance slot map, all of which rt_trace already carries; a caller that needs
// leaves and glass to be see-through wants that kernel's path, not this one. Stated here because a
// ray query that silently treats a leaf block as solid is the kind of wrong answer that reads as a
// shading bug three layers away.
//
// Winding matches rt_trace's own native facing probe (clockwise front-facing), so a front-facing
// result here means the same thing it means there.

struct RayQueryConstants {
    uint abiVersion;
    uint rayCount;
    // RayTier.ordinal() of the traversal this dispatch is. Copied into every record, hit and miss
    // alike, so a reader can tell an answer from an untouched buffer without a second flag.
    uint tier;
    uint pad0;
};

// Mirrors RayQueryAbi's word table: one float, three uints, the normal, then the tier. The normal
// is packed_float3 on purpose. A plain float3 member carries 16 bytes of SIZE, not just alignment,
// which would place the tier at byte 32 and make this record 48 bytes while RayQueryAbi still said
// 8 words; every field past the normal would then be read at the wrong offset with nothing to
// report it. Packed keeps the normal at bytes 16..27 and the tier at 28: 32 bytes, 8 words.
struct RayHit {
    float distance;
    uint flags;
    uint surface;
    uint atlasUv;
    packed_float3 normal;
    uint tier;
};

// Flag bits, mirroring RayQueryAbi's FLAG_ constants.
constant uint RAY_FLAG_FRONT_FACING = 1u;
constant uint RAY_FLAG_FACE_SHIFT = 8u;
constant uint RAY_FACE_UNKNOWN = 0xFu;
constant uint RAY_FLAG_UV_KNOWN = 1u << 12;

// Where a record keeps its UVs, read off the surface word. See rt_face_normal.metal for the rule
// and for why a fixed offset guess samples a real texel and looks like a shading bug.
constant uint SURFACE_UV_AT_BYTE_4 = 1u << 31;   // RtSectionGeometry's exact-triangle supplement
constant uint SURFACE_UV_AT_BYTE_8 = 1u << 30;   // an uploaded chunk mesh

kernel void rt_ray_query(
        constant RayQueryConstants& constants [[buffer(0)]],
        instance_acceleration_structure accelerationStructure [[buffer(1)]],
        device const float4* requests [[buffer(2)]],
        device RayHit* hits [[buffer(3)]],
        uint index [[thread_position_in_grid]])
{
    if (index >= constants.rayCount) {
        return;
    }

    RayHit miss;
    // Negative, not zero: zero is a legitimate self-hit. The tier below, not this sign, is what
    // separates a traced miss from a record nothing ever wrote. See RayQueryAbi's own note.
    miss.distance = -1.0f;
    miss.flags = RAY_FACE_UNKNOWN << RAY_FLAG_FACE_SHIFT;
    miss.surface = 0u;
    miss.atlasUv = 0u;
    miss.normal = float3(0.0);
    miss.tier = constants.tier;

    // A caller built for other offsets gets an unanswered record rather than a buffer read at the
    // wrong stride. Tier zero, not the miss above: the caller's own layout may put the tier
    // elsewhere, and an all-zero record reads as unanswered under every layout this kernel has had.
    if (constants.abiVersion != 3u) {
        RayHit unanswered;
        unanswered.distance = 0.0f;
        unanswered.flags = 0u;
        unanswered.surface = 0u;
        unanswered.atlasUv = 0u;
        unanswered.normal = float3(0.0);
        unanswered.tier = 0u;
        hits[index] = unanswered;
        return;
    }

    float4 originAndMin = requests[index * 2u];
    float4 directionAndMax = requests[index * 2u + 1u];

    float3 direction = directionAndMax.xyz;
    float lengthSquared = dot(direction, direction);
    // A zero-length direction is a caller bug, not a miss to trace: normalize would produce NaN and
    // Metal's behaviour on a NaN ray is unspecified.
    if (!(lengthSquared > 0.0f)) {
        hits[index] = miss;
        return;
    }

    ray r;
    r.origin = originAndMin.xyz;
    r.min_distance = originAndMin.w;
    r.direction = direction * rsqrt(lengthSquared);
    r.max_distance = directionAndMax.w;

    intersection_params params;
    params.set_triangle_cull_mode(triangle_cull_mode::none);
    params.set_triangle_front_facing_winding(winding::clockwise);

    intersection_query<triangle_data, instancing> query;
    query.reset(r, accelerationStructure, params);
    // Opaque geometry auto-commits, so this loop runs to completion once and leaves the closest
    // hit committed. The loop body is empty on purpose: every candidate this kernel cares about is
    // already committed by the time next() returns false.
    while (query.next()) {
    }

    if (query.get_committed_intersection_type() == intersection_type::none) {
        hits[index] = miss;
        return;
    }

    RayHit hit;
    hit.distance = query.get_committed_distance();
    hit.tier = constants.tier;
    // Metal serves the per-triangle word rt_expand wrote because that buffer was attached to the
    // geometry descriptor at build time; this needs no bindless resource table. Zero vector when
    // the face field names nothing, which the caller must not normalize. See rt_face_normal.metal.
    device const uint* primitiveData = (device const uint*) query.get_committed_primitive_data();
    uint surface = primitiveData != nullptr ? *primitiveData : 0u;
    hit.normal = primitiveData != nullptr ? rt_face_normal(surface) : float3(0.0);
    // Face 0..5 names a direction; 15 means the record names none, which is also what a triangle
    // with no primitive data at all reports.
    uint face = primitiveData != nullptr ? ((surface >> 12u) & 0xFu) : RAY_FACE_UNKNOWN;
    uint flags = (query.is_committed_triangle_front_facing() ? RAY_FLAG_FRONT_FACING : 0u)
            | (face << RAY_FLAG_FACE_SHIFT);

    // A mesh triangle has no palette entry, so its surface word carries nothing a caller can look
    // up: its identity is the atlas UV below. A voxel record's word is the one a pack already
    // decodes for palette colour, emission and extinction, so it passes through.
    uint uvOffsetWords = 0u;
    if (surface & SURFACE_UV_AT_BYTE_4) {
        uvOffsetWords = 1u;
        hit.surface = surface;
    } else if (surface & SURFACE_UV_AT_BYTE_8) {
        uvOffsetWords = 2u;
        hit.surface = 0u;
    } else {
        hit.surface = surface;
    }

    hit.atlasUv = 0u;
    if (uvOffsetWords != 0u) {
        device const float* uvs = (device const float*)(primitiveData + uvOffsetWords);
        float2 bary = query.get_committed_triangle_barycentric_coord();
        float2 uv = float2(uvs[0], uvs[1]) * (1.0f - bary.x - bary.y)
                + float2(uvs[2], uvs[3]) * bary.x
                + float2(uvs[4], uvs[5]) * bary.y;
        // Two halves in one word, low half u. Half precision resolves better than one part in
        // 2048 over the unit square, finer than an atlas texel at any page size this engine builds.
        hit.atlasUv = as_type<uint>(half2(uv));
        flags |= RAY_FLAG_UV_KNOWN;
    }
    hit.flags = flags;
    hits[index] = hit;
}
