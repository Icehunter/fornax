#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;
using namespace metal::raytracing;

// Engine-owned general ray query: one thread per submitted ray, intersects the frame's instance
// acceleration structure and writes the closest hit. Unlike rt_trace, nothing here is tied to the
// sun: origin and direction come from the caller's buffer, which is the whole point of this kernel.
//
// Cutout alpha is tested against the block atlas for any record that carries UVs, at the same 0.1
// cutoff the raster shadow pipeline uses, so leaves and glass are see-through to a ray query the
// same way they are to a shadow. A record with no UVs (an rt_expand voxel box) is solid by
// construction and is accepted without a sample; a caller can tell which it got from the
// UV-known flag.
//
// Winding matches rt_trace's own native facing probe (clockwise front-facing), so a front-facing
// result here means the same thing it means there.

struct RayQueryConstants {
    uint abiVersion;
    uint rayCount;
    // RayTier.ordinal() of the traversal this dispatch is. Copied into every record, hit and miss
    // alike, so a reader can tell an answer from an untouched buffer without a second flag.
    uint tier;
    // Nonzero: this dispatch is filling a buffer another tier may already have written, so it must
    // read each record's tier first and leave answered ones alone. Zero: this dispatch owns the
    // buffer and writes every record, which is what a benchmark and a single-tier caller want.
    uint fillMode;
    // The caller's origin is camera-relative. Each structure is built in a frame of its own, the
    // mesh tier's rebased onto a coarse grid and the voxel tier's onto its window's first section,
    // and a caller has no way to know either. This is the camera in that frame, so adding it puts
    // a camera-relative ray where the geometry is. packed_float3 for the same reason RayHit uses
    // one: a float3 member carries 16 bytes of size and would move the pad.
    packed_float3 originOffset;
    uint pad;
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
    // What the atlas cannot say: the vertex tint in the low three bytes, the block's own light
    // level in the top one. Grass and leaves are grey in the atlas and take their colour from the
    // tint; a glowing block's light is not in its texture at all.
    uint tint;
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
        texture2d<float, access::sample> atlasIn [[texture(0)]],
        uint index [[thread_position_in_grid]])
{
    if (index >= constants.rayCount) {
        return;
    }

    // A higher tier may already own this record. One word read per ray is what makes the buffer
    // form cascade the same way the image form does: without it a lower tier overwrites an exact
    // answer with an approximate one, and nothing in the record would say it happened.
    //
    // This rests on the engine clearing the hit buffer at the start of every ray_query pass. A
    // pack-declared buffer persists between frames, so without that clear last frame's tier words
    // would block this frame's trace entirely.
    if (constants.fillMode != 0u && hits[index].tier != 0u) {
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
    // White and unlit: a miss met no block, so it has neither a tint nor a light of its own.
    miss.tint = 0x00FFFFFFu;

    // A caller built for other offsets gets an unanswered record rather than a buffer read at the
    // wrong stride. Tier zero, not the miss above: the caller's own layout may put the tier
    // elsewhere, and an all-zero record reads as unanswered under every layout this kernel has had.
    if (constants.abiVersion != 4u) {
        RayHit unanswered;
        unanswered.distance = 0.0f;
        unanswered.flags = 0u;
        unanswered.surface = 0u;
        unanswered.atlasUv = 0u;
        unanswered.normal = float3(0.0);
        unanswered.tier = 0u;
        unanswered.tint = 0u;
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
    r.origin = originAndMin.xyz + constants.originOffset;
    r.min_distance = originAndMin.w;
    r.direction = direction * rsqrt(lengthSquared);
    r.max_distance = directionAndMax.w;

    intersection_params params;
    params.set_triangle_cull_mode(triangle_cull_mode::none);
    params.set_triangle_front_facing_winding(winding::clockwise);
    // Non-opaque so every candidate reaches this loop: a leaf or a pane of glass is a triangle the
    // ray has to see through, and the atlas is the only thing that knows which texels are holes.
    params.force_opacity(forced_opacity::non_opaque);

    intersection_query<triangle_data, instancing> query;
    query.reset(r, accelerationStructure, params);
    while (query.next()) {
        if (query.get_candidate_intersection_type() != intersection_type::triangle) {
            continue;
        }
        device const uint* candidate = (device const uint*) query.get_candidate_primitive_data();
        uint candidateSurface = candidate != nullptr ? *candidate : 0u;
        uint candidateUvWords = (candidateSurface & SURFACE_UV_AT_BYTE_4) ? 1u
                : (candidateSurface & SURFACE_UV_AT_BYTE_8) ? 2u : 0u;
        if (candidateUvWords == 0u) {
            // A record with no UVs carries no alpha to test. An rt_expand voxel box is solid by
            // construction, so accepting it here matches what the shadow trace does with one.
            query.commit_triangle_intersection();
            continue;
        }
        device const float* candidateUvs = (device const float*)(candidate + candidateUvWords);
        float2 bary = query.get_candidate_triangle_barycentric_coord();
        float2 uv = float2(candidateUvs[0], candidateUvs[1]) * (1.0f - bary.x - bary.y)
                + float2(candidateUvs[2], candidateUvs[3]) * bary.x
                + float2(candidateUvs[4], candidateUvs[5]) * bary.y;
        constexpr sampler nearestAtlas(coord::normalized, address::clamp_to_edge, filter::nearest);
        // The same 0.1 cutoff the raster shadow pipeline and rt_mesh_shadow both use. A ray that
        // disagrees with the rasteriser about which texels are holes lights foliage differently
        // from the way it shadows.
        if (atlasIn.sample(nearestAtlas, uv).a >= 0.1f) {
            query.commit_triangle_intersection();
        }
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

    // Word 1 of a mesh primitive. The voxel tiers build their own records and have no vertex to
    // read, so they report white and unlit rather than a tint that is not theirs.
    hit.tint = (primitiveData != nullptr && (surface & SURFACE_UV_AT_BYTE_8))
            ? primitiveData[1] : 0x00FFFFFFu;

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
