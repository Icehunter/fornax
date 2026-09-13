#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;
using namespace metal::raytracing;

// Engine-owned sun-shadow trace: one thread per output pixel, reads this pixel's depth,
// reconstructs where that surface is in the voxel grid, and walks a manual intersection_query
// loop toward the sun through the frame's instance acceleration structure, alpha-testing cutout
// geometry (leaves, glass, doors) against the real block atlas instead of treating it as solid.
//
// Reversed-Z: depth 0.0 is far/sky, so a depth of exactly 0.0 has no surface to shade and the
// kernel writes 1.0 (fully sun-visible) and 0.0 (not traced) without tracing. `invProjModelView`
// is the same matrix GlobalUniformsWriteMixin builds for u_InvProjModelView: applying it to this
// pixel's NDC (including its real depth) gives a camera-relative position directly, with no
// separate camera translation needed. Grid space then follows the pack's own
// voxel_visibility.glsl convention: p_grid = camAbs - first*16 + p_camRel, where first*16 is
// handed in already multiplied (firstSectionTimes16) so this kernel never needs the window radius
// or section size.
//
// The ray origin is nudged off the reconstructed surface so it starts outside the very face it
// came from, rather than immediately re-hitting it. The bias follows the flat geometric normal
// packed into gNormalOut's alpha channel (0.01, about a third of a 1/16-block texel), never the
// bump-mapped shading normal in its rgb: a textured face's per-texel bump detail can tilt rgb far
// enough off vertical to shrink the bias's clearance below the reconstruction's own floating-point
// slack, letting the ray self-intersect the voxel it started on. The alpha channel's exact bit
// layout is the pack's own gbuffer_resolve/terrain contract (see plagueDecodeGeometricNormal
// below); a bias along the sun direction instead shrinks toward zero at a grazing light angle,
// which is exactly when acne is worst, so a channel that decodes as "no geometric normal supplied"
// (nothing bound, or a synthetic test's all-zero texture) is the only case that falls back to a
// 0.05 sun-direction bias, a safety margin comfortably larger than half a 1/16-block texel
// (0.03125, the finest box geometry this pass builds).
//
// Depth binding contract: `depthIn` must be the real depth-format texture (e.g. D32Float) bound
// directly at texture(1), not a color-format view of it. depth2d reads the depth value straight
// off a depth texture, so no pixel-format reinterpretation step is needed or expected on the Java
// side.
//
// Normal binding contract: `normalIn` at texture(2) must be the real G-buffer world-space normal
// texture (RGBA16Snorm), read directly with no rotation into grid space needed since rgb is
// already a direction, not a position.
//
// Window validity: mode 2 consumes a coherent, per-owner toroidal readiness buffer. Only owners
// whose expanded bounds meet the ray before a current hit (or domain exit for a miss) matter.
// Mode 0 is invalid and mode 1 retains synthetic legacy-fixture behavior. Production never
// reduces the cube to one bit: unrelated queued sections cannot invalidate this ray.
// rtSunValid describes only finite-domain tracing; it says nothing about distant casters.

// ---- Cutout alpha testing: cross-file formats, derived from the real producing code ----
//
// primitive_data bit layout (rt_expand.metal's write_triangle, this file's own sibling):
//   primitiveOut[i] = voxelIndex | (face << 12u) | (paletteIndex << 16u)
// voxelIndex occupies bits 0..11 (VOXELS_PER_SLOT = 4096 = 2^12), face bits 12..14 (6 faces fit in
// 3 bits), paletteIndex bits 16..22 (MAX_PALETTE_ENTRIES = 96 fits in 7 bits). This kernel only
// needs face and paletteIndex; voxelIndex is not decoded here.
//
// Palette word 0 cutout bit (voxel/BrickGridUpload.java, packPaletteFlagsWord): boxCount in bits
// 0-3, extinction in bits 4-11, CUTOUT flag at bit 30, CROSS flag at bit 31: `word |= 1 << 30`
// for cutout. CUTOUT_BIT below matches that exactly, and PALETTE_ENTRY_WORDS/MAX_PALETTE_ENTRIES
// mirror rt_expand.metal's own copies of the same two constants (BrickGridUpload's layout comment:
// word N of entry E in slot S lives at word S*PALETTE_ENTRY_WORDS*MAX_PALETTE_ENTRIES +
// E*PALETTE_ENTRY_WORDS + N).
//
// voxelFaceTexture word layout (voxel/VoxelFaceTexture.java): one entry per (slot, paletteIndex)
// is ENTRY_WORDS = 6 faces * FACE_WORDS(7) = 42 words, addressed the same way as a palette entry
// but with its own per-slot stride WORDS_PER_SLOT = MAX_PALETTE_ENTRIES * ENTRY_WORDS; a face's own
// 7 words start at faceEntryBase + face * FACE_WORDS (face indexed by Direction.get3DDataValue(),
// the same order rt_expand.metal's face_corners uses). Word 0 (the header) packs RGB tint in bits
// 0..23 and flags in the top byte: VoxelFaceTexture.mapping() sets `flags = 1 | (cutout layer ? 2
// : 0)` then `words[0] = (flags << 24) | (rgb & 0xFFFFFF)`, so bit 24 is "this face has a real,
// usable UV mapping" (only ever set for a FULL-shape block, see VoxelFaceTexture.pack()) and bit
// 25 is "this face's layer is CUTOUT" (informational here; the palette's own cutout bit is what
// gates this branch). Words 1..6 are u0, v0, du/ds, dv/ds, du/dt, dv/dt as raw float bits (pack()'s
// own comment): u(s,t) = u0 + s*du/ds + t*du/dt, v(s,t) = v0 + s*dv/ds + t*dv/dt, where (s, t) is
// the hit point's position within the voxel's own unit cell. VoxelFaceTexture's own doc: "Local
// st uses (y,z) for X faces, (x,z) for Y faces and (x,y) for Z faces", i.e. face 0/1 (down/up, Y
// axis) -> s=local.x, t=local.z; face 2/3 (north/south, Z axis) -> s=local.x, t=local.y; face 4/5
// (west/east, X axis) -> s=local.y, t=local.z. A face with bit 24 clear (no usable mapping: every
// PARTIAL-shape cutout block: doors, trapdoors, glass panes, iron bars, per SectionHarvester's own
// CUTOUT_MAX_BOXES comment) is unknown in production rather than guessed opaque. Synthetic
// legacy-mode tests retain their historical approximation.
//
// Production CROSS triangles carry the actual per-position model offset and barycentric UVs in
// RtSectionGeometry's separate 32-byte primitive records (bit31 identifier, six float UV values,
// trailing cull flag). Their UVs do not use fract(hitPos), so a displaced primitive retains the
// original texture mapping across voxel boundaries. Reverse baked faces retain separate winding
// and alpha mapping. The palette-rectangle branch remains only for old synthetic fixtures.
// Unsupported forms are unknown in the owner-readiness map. Missing alpha inputs encountered
// before the closest supported hit also invalidate that ray rather than committing opaque boxes.

// The alpha threshold below (0.1) is the same value src/main/resources/assets/fornax/shaders/
// blocks/shadow.fsh uses for its own cutout test: `if (alpha < 0.1) discard;`. That fragment
// shader is engine-owned and un-overridable by any pack (GeometrySlot's own doc: nothing routes to
// fornax:blocks/shadow), so it is unconditionally the real cutout threshold every rasterized
// shadow caster in this engine tests against today; matching it here means RT and rasterized
// shadows agree on the same leaf rather than picking a second, independent number.
constant float CUTOUT_ALPHA_THRESHOLD = 0.1;

// Decodes gNormalOut's alpha channel exactly as the pack's shaders/include/geometric_normal.glsl
// does: an SNORM16 code in [0, 32767]. Codes 2..7 are the six exact axis-aligned normals
// (2 + axis*2 + sign), codes 8..16391 are a 7+7 bit octahedral encoding of a general geometric
// normal (for a block face that is not axis-aligned), and any other code (0, 1, or >= 16392) means
// no geometric normal was supplied that frame, so the caller gets `fallback` back unchanged. RT
// geometry is itself axis-aligned boxes, so the common case is the fast exact-axis path; the
// octahedral path still gives a usable "away from this surface" direction for a block RT
// approximates as a box even though its real shape is sloped.
float3 plagueDecodeGeometricNormal(float encodedAlpha, float3 fallback) {
    int code = int(round(encodedAlpha * 32767.0));
    if (code >= 2 && code < 8) {
        float3 n = float3(0.0);
        n[(code - 2) / 2] = ((code & 1) == 1) ? 1.0 : -1.0;
        return n;
    }
    if (code < 8 || code >= 16392) {
        return fallback;
    }
    code -= 8;
    float2 p = float2(float(code % 128), float(code / 128)) / 127.0 * 2.0 - 1.0;
    float3 n = float3(p, 1.0 - abs(p.x) - abs(p.y));
    if (n.z < 0.0) {
        float2 signs = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signs;
    }
    return normalize(n);
}

// rt_expand.metal's own copies of these two: see that file's header comment for why a shared
// palette layout constant is duplicated per shader rather than factored out (no shared #include
// wired between the engine compute shaders at this milestone).
constant uint PALETTE_ENTRY_WORDS = 16;
constant uint MAX_PALETTE_ENTRIES = 96; // SectionHarvester.MAX_PALETTE_ENTRIES
constant uint CUTOUT_BIT = 1u << 30; // palette word 0, matches BrickGridUpload's packing
constant uint CROSS_BIT = 1u << 31; // palette word 0, matches BrickGridUpload's packing

// Bits of the `cutoutFlags` buffer(5) argument (see its own doc below). Split in two because the
// CROSS alpha test only ever reads the palette (always bound) and the atlas: a pack that captures
// the atlas but never enables VoxelFaceTexture.TARGET still has everything CROSS needs, and gating
// CROSS on the faceTexture bit too would silently drop plant alpha testing back to a solid
// silhouette for no reason tied to the data CROSS actually reads.
constant uint CUTOUT_ATLAS_BIT = 1u;
constant uint CUTOUT_FACE_TEXTURE_BIT = 2u;

// voxelFaceTexture's own per-slot layout (VoxelFaceTexture.java): six faces per palette entry,
// seven words per face (header + 6 UV floats).
constant uint FACE_WORDS = 7;
constant uint FACE_TEXTURE_ENTRY_WORDS = 6u * FACE_WORDS;
constant uint FACE_TEXTURE_WORDS_PER_SLOT = MAX_PALETTE_ENTRIES * FACE_TEXTURE_ENTRY_WORDS;
// Header bit 24, set by VoxelFaceTexture.mapping()'s `flags = 1 | ...`, means a real, usable UV mapping was
// packed for this face (always true for a FULL-shape block, always false otherwise).
constant uint FACE_TEXTURE_VALID_BIT = 1u << 24;

// Byte layout of the constant buffer this kernel reads at buffer(0) (matched on the Java side
// that fills it):
//   0    invProjModelView     float4x4, column-major                 64 bytes
//   64   camAbs               float4, xyz used, w unused             16 bytes
//   80   firstSectionTimes16  float4, xyz used, w unused             16 bytes
//   96   sunDir               float4, xyz used, w unused             16 bytes
//   112  maxDistance          float                                   4 bytes
//   116  windowDiameter       uint (centered RT domain diameter, sections) 4 bytes
//   120  size                 uint2 (output width, height)            8 bytes
//   128  snapshotReady        uint (complete current geometry snapshot)     4 bytes
//   132  firstSectionX        int  (window.centerX() - radius)        4 bytes
//   136  firstSectionY        int  (window.centerY() - radius)        4 bytes
//   140  firstSectionZ        int  (window.centerZ() - radius)        4 bytes
//   144  total
//
// Every float3 quantity is carried as a float4 so its slot is a full 16 bytes on both sides. The
// vec3-then-scalar std140 footgun this project has hit before (see AGENTS.md) is avoided by never
// placing a bare scalar right after one; maxDistance and windowDiameter after it fill their own
// slot before the trailing uint2. snapshotReady/firstSectionX/Y/Z are plain scalars with no
// vector sibling to misalign against, appended after size in the same low-risk shape.
struct RtTraceConstants {
    float4x4 invProjModelView;
    float4 camAbs;
    float4 firstSectionTimes16;
    float4 sunDir;
    float maxDistance;
    uint windowDiameter;
    uint2 size;
    uint snapshotReady;
    int firstSectionX;
    int firstSectionY;
    int firstSectionZ;
};

// (s, t) of `local` (the fractional position within a hit voxel's own unit cell, see this file's
// header comment on why fract() alone is correct) on `face`'s own plane, matching
// VoxelFaceTexture's own convention exactly: face 0/1 (Y axis) -> (x, z), face 2/3 (Z axis) ->
// (x, y), face 4/5 (X axis) -> (y, z).
inline float2 faceLocalSt(uint face, float3 local) {
    if (face <= 1u) {
        return float2(local.x, local.z);
    }
    if (face <= 3u) {
        return float2(local.x, local.y);
    }
    return float2(local.y, local.z);
}

// Closest alpha-tested intersection shared by screen diagnostics and sun-space depth.
// x is hit distance or infinity; y is the nearest candidate whose representation is unavailable.
inline float2 rtNearestIntersection(ray r, instance_acceleration_structure accelerationStructure,
        device const uint* palette, device const uint* faceTexture,
        device const uint* instanceSlotMap, uint cutoutFlags,
        texture2d<float, access::sample> atlasIn, bool strictRepresentation) {
    float3 origin = r.origin;
    float3 sunDir = r.direction;
    // force_opacity(non_opaque) overrides every instance's own opaque geometry flag (set at
    // acceleration-structure build time, MetalRtAcceleration.buildTrianglePrimitiveDescriptor's own
    // setOpaque:true, which is what lets the closest-hit convenience API used elsewhere in this
    // pass skip any-hit work for the common solid case) for THIS query alone, so every candidate
    // surfaces through next() instead of being auto-committed before this kernel can inspect it.
    intersection_params params;
    params.set_triangle_cull_mode(triangle_cull_mode::none);
    // Native facing probe pins this Metal convention to an outward cross-product normal:
    // a ray pointing into that normal sees the front face.
    params.set_triangle_front_facing_winding(winding::clockwise);
    params.force_opacity(forced_opacity::non_opaque);

    float uncertain = INFINITY;
    intersection_query<triangle_data, instancing> query;
    query.reset(r, accelerationStructure, params);

    while (query.next()) {
        if (query.get_candidate_intersection_type() != intersection_type::triangle) {
            continue;
        }

        device const uint* primitiveData = (device const uint*) query.get_candidate_primitive_data();
        uint packed = *primitiveData;
        // Supplement triangles carry the rendered vertex UVs after the packed identifier.
        // Bit 31 is RtSectionGeometry's exact-triangle ABI; barycentric interpolation retains
        // flips/crops and the per-position offset is already in the triangle positions.
        if ((packed & (1u << 31u)) != 0u) {
            // Per-primitive flag mirrors raster culling. Keep both baked windings separate:
            // reverse faces may have different UVs and must not become a two-sided opaque union.
            if ((primitiveData[7] & 1u) != 0u && !query.is_candidate_triangle_front_facing()) continue;
            if ((cutoutFlags & CUTOUT_ATLAS_BIT) == 0u) {
                uncertain = min(uncertain, query.get_candidate_triangle_distance());
                continue;
            }
            device const float* uvData = (device const float*)(primitiveData + 1);
            float2 bary = query.get_candidate_triangle_barycentric_coord();
            float2 uv = float2(uvData[0], uvData[1]) * (1.0 - bary.x - bary.y)
                    + float2(uvData[2], uvData[3]) * bary.x
                    + float2(uvData[4], uvData[5]) * bary.y;
            constexpr sampler exactSampler(coord::normalized, address::clamp_to_edge, filter::nearest);
            if (atlasIn.sample(exactSampler, uv).a >= CUTOUT_ALPHA_THRESHOLD)
                query.commit_triangle_intersection();
            continue;
        }
        uint face = (packed >> 12u) & 0x7u;
        uint paletteIndex = (packed >> 16u) & 0x7Fu;
        uint slot = instanceSlotMap[query.get_candidate_instance_id()];

        uint entryBase = slot * PALETTE_ENTRY_WORDS * MAX_PALETTE_ENTRIES + paletteIndex * PALETTE_ENTRY_WORDS;
        uint paletteWord0 = palette[entryBase];
        if ((paletteWord0 & CUTOUT_BIT) == 0u) {
            // Opaque geometry blocks light unconditionally: no texture read, same cost as
            // today's any-hit fast path for the common case.
            query.commit_triangle_intersection();
            continue;
        }

        if ((paletteWord0 & CROSS_BIT) != 0u) {
            // CROSS geometry: alpha-test against the palette's own embedded UV rect rather than
            // voxelFaceTexture (see this file's own header comment for why). This only ever reads
            // the palette (always bound) and the atlas, so it is gated on CUTOUT_ATLAS_BIT alone,
            // never CUTOUT_FACE_TEXTURE_BIT.
            if ((cutoutFlags & CUTOUT_ATLAS_BIT) == 0u) {
                // No real atlas data this frame: fall back to opaque rather than sampling a
                // placeholder texture.
                if (strictRepresentation) uncertain = min(uncertain, query.get_candidate_triangle_distance());
                else query.commit_triangle_intersection();
                continue;
            }

            // box (word 7) and rect (words 13/14) both live in the same palette entry
            // rt_expand.metal already read to build this triangle.
            uint boxWord = palette[entryBase + 7u];
            float3 boxMin = float3(float(boxWord & 0x1Fu), float((boxWord >> 5u) & 0x1Fu),
                    float((boxWord >> 10u) & 0x1Fu)) / 16.0;
            float3 boxMax = float3(float((boxWord >> 15u) & 0x1Fu), float((boxWord >> 20u) & 0x1Fu),
                    float((boxWord >> 25u) & 0x1Fu)) / 16.0;

            float3 hitPos = origin + sunDir * query.get_candidate_triangle_distance();
            float3 local = fract(hitPos);
            float s = clamp((local.x - boxMin.x) / max(boxMax.x - boxMin.x, 1e-4), 0.0, 1.0);
            float t = clamp((local.y - boxMin.y) / max(boxMax.y - boxMin.y, 1e-4), 0.0, 1.0);

            uint uvWord0 = palette[entryBase + 13u];
            uint uvWord1 = palette[entryBase + 14u];
            float rectU0 = float(uvWord0 >> 16u) / 65535.0;
            float rectV0 = float(uvWord0 & 0xFFFFu) / 65535.0;
            float rectU1 = float(uvWord1 >> 16u) / 65535.0;
            float rectV1 = float(uvWord1 & 0xFFFFu) / 65535.0;
            // v runs the other way from t: atlas V follows Minecraft's top-of-texture-is-V0
            // convention, so the box's top (t = 1) samples rectV0 and its bottom (t = 0) rectV1.
            float u = mix(rectU0, rectU1, s);
            float v = mix(rectV1, rectV0, t);

            constexpr sampler crossSampler(coord::normalized, address::clamp_to_edge, filter::nearest);
            float alpha = atlasIn.sample(crossSampler, float2(u, v)).a;
            if (alpha >= CUTOUT_ALPHA_THRESHOLD) {
                query.commit_triangle_intersection();
            }
            continue;
        }

        if ((cutoutFlags & (CUTOUT_ATLAS_BIT | CUTOUT_FACE_TEXTURE_BIT))
                != (CUTOUT_ATLAS_BIT | CUTOUT_FACE_TEXTURE_BIT)) {
            // A FULL/PARTIAL cutout entry needs both the atlas and voxelFaceTexture; either one
            // missing this frame means the other may be an unrelated placeholder: fall back to
            // opaque rather than reading it.
            if (strictRepresentation) uncertain = min(uncertain, query.get_candidate_triangle_distance());
            else query.commit_triangle_intersection();
            continue;
        }

        uint faceBase = slot * FACE_TEXTURE_WORDS_PER_SLOT + paletteIndex * FACE_TEXTURE_ENTRY_WORDS
                + face * FACE_WORDS;
        uint header = faceTexture[faceBase];
        if ((header & FACE_TEXTURE_VALID_BIT) == 0u) {
            // No usable UV mapping for this face (a PARTIAL-shape cutout block: see this file's
            // header comment): fall back to opaque rather than guessing a texture coordinate,
            // matching this geometry's behaviour before cutout alpha testing existed.
            if (strictRepresentation) uncertain = min(uncertain, query.get_candidate_triangle_distance());
            else query.commit_triangle_intersection();
            continue;
        }

        float3 hitPos = origin + sunDir * query.get_candidate_triangle_distance();
        float3 local = fract(hitPos);
        float2 st = faceLocalSt(face, local);

        float u0 = as_type<float>(faceTexture[faceBase + 1u]);
        float v0 = as_type<float>(faceTexture[faceBase + 2u]);
        float duds = as_type<float>(faceTexture[faceBase + 3u]);
        float dvds = as_type<float>(faceTexture[faceBase + 4u]);
        float dudt = as_type<float>(faceTexture[faceBase + 5u]);
        float dvdt = as_type<float>(faceTexture[faceBase + 6u]);
        float u = u0 + st.x * duds + st.y * dudt;
        float v = v0 + st.x * dvds + st.y * dvdt;

        constexpr sampler atlasSampler(coord::normalized, address::clamp_to_edge, filter::nearest);
        float alpha = atlasIn.sample(atlasSampler, float2(u, v)).a;
        if (alpha >= CUTOUT_ALPHA_THRESHOLD) {
            query.commit_triangle_intersection();
        }
        // Otherwise: leave this candidate uncommitted and let the loop continue, so the ray keeps
        // travelling through the transparent gap in the texture.
    }

    bool hit = query.get_committed_intersection_type() != intersection_type::none;
    return float2(hit ? query.get_committed_distance() : INFINITY, uncertain);
}

// Slab interval handles axis-parallel rays without 0*infinity NaNs.
inline bool rtRayBox(float3 origin, float3 direction, float3 lower, float3 upper,
        thread float& nearT, thread float& farT) {
    nearT = -INFINITY;
    farT = INFINITY;
    for (uint axis = 0; axis < 3; ++axis) {
        if (direction[axis] == 0.0) {
            if (origin[axis] < lower[axis] || origin[axis] > upper[axis]) return false;
        } else {
            float a = (lower[axis] - origin[axis]) / direction[axis];
            float b = (upper[axis] - origin[axis]) / direction[axis];
            nearT = max(nearT, min(a, b));
            farT = min(farT, max(a, b));
        }
    }
    return farT > nearT;
}

inline uint rtToroidalSlot(int3 section, int diameter) {
    int3 wrap = ((section % diameter) + diameter) % diameter;
    return uint((wrap.y * diameter + wrap.z) * diameter + wrap.x);
}

// Section DDA, bounded by three crossings per diameter plus the initial cell. Readiness belongs
// to owners, not geometric cells: accepted supplemental triangles may extend one block beyond
// their source section. Check a neighbor only if its one-block-expanded bounds intersect the
// tested ray segment. This preserves unrelated-section independence while catching displaced
// stale casters, including a caster exactly at the closest-hit endpoint. The entry boundary
// stays open because the coverage interval excludes that same one-block outer shell. A miss
// excludes the exit endpoint too; only a confirmed closest hit requires a closed stop.
inline bool rtRaySectionsReady(float3 origin, float3 direction, float entryT, float stopT,
        bool includeStop, uint diameter, int3 firstSection, device const uint* readiness) {
    int D = int(diameter);
    int3 cell = clamp(int3(floor((origin + direction * entryT) / 16.0)), int3(0), int3(D - 1));
    int3 step = int3(sign(direction));
    float3 nextT = float3(INFINITY);
    float3 delta = float3(INFINITY);
    for (uint axis = 0; axis < 3; ++axis) {
        if (direction[axis] != 0.0) {
            float boundary = float(cell[axis] + (step[axis] > 0 ? 1 : 0)) * 16.0;
            nextT[axis] = (boundary - origin[axis]) / direction[axis];
            delta[axis] = 16.0 / abs(direction[axis]);
        }
    }
    for (uint visited = 0; visited < 3u * diameter + 1u; ++visited) {
        for (int y = -1; y <= 1; ++y) for (int z = -1; z <= 1; ++z) for (int x = -1; x <= 1; ++x) {
            int3 owner = cell + int3(x, y, z);
            // This frame's readiness buffer is immutable. A ready interior owner cannot
            // invalidate any ray; avoid its slab intersection. Check bounds before toroidal
            // indexing so an absent outside owner cannot alias a ready interior slot.
            bool inside = all(owner >= 0) && all(owner < D);
            if (inside && readiness[rtToroidalSlot(firstSection + owner, D)] != 0u) continue;
            float nearOwner, farOwner;
            if (!rtRayBox(origin, direction, float3(owner) * 16.0 - 1.0,
                    float3(owner + 1) * 16.0 + 1.0, nearOwner, farOwner)
                    || farOwner <= entryT || nearOwner > stopT
                    || (!includeStop && nearOwner == stopT)) continue;
            return false; // An unknown or outside owner overlaps this ray segment.
        }
        float crossing = min(nextT.x, min(nextT.y, nextT.z));
        if (crossing >= stopT) return true;
        // Advance tied axes together: diagonal rays must not visit arbitrary off-ray cells.
        for (uint axis = 0; axis < 3; ++axis) if (nextT[axis] == crossing) {
            cell[axis] += step[axis];
            nextT[axis] += delta[axis];
        }
        if (any(cell < 0) || any(cell >= D)) return false;
    }
    return false;
}

kernel void rt_trace(
        constant RtTraceConstants& constants [[buffer(0)]],
        instance_acceleration_structure accelerationStructure [[buffer(1)]],
        device const uint* palette [[buffer(2)]],
        device const uint* faceTexture [[buffer(3)]],
        device const uint* instanceSlotMap [[buffer(4)]],
        // Two independent bits (see CUTOUT_ATLAS_BIT/CUTOUT_FACE_TEXTURE_BIT above), not one
        // combined flag: CUTOUT_ATLAS_BIT is set from the first frame the block atlas holds a
        // texture onward (BlockAtlasView.texture() non-null, so `atlasIn`/texture(4) holds a real
        // Metal-visible copy, not a placeholder); CUTOUT_FACE_TEXTURE_BIT is set when the active
        // pack's graph enabled VoxelFaceTexture.TARGET (MetalRtGeometry.faceTexture() non-null).
        // A CROSS entry alpha-tests once CUTOUT_ATLAS_BIT alone is set, since it never reads
        // `faceTexture`; a FULL/PARTIAL cutout entry needs both bits, since it reads both buffers.
        // A clear bit means the matching buffer/texture may be an unrelated placeholder and must
        // never be read.
        constant uint& cutoutFlags [[buffer(5)]],
        device const uint* sectionReadiness [[buffer(10)]],
        depth2d<float, access::read> depthIn [[texture(1)]],
        texture2d<float, access::read> normalIn [[texture(2)]],
        texture2d<float, access::sample> atlasIn [[texture(4)]],
        texture2d<float, access::write> maskOut [[texture(0)]],
        texture2d<float, access::write> validOut [[texture(3)]],
        uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= constants.size.x || gid.y >= constants.size.y) {
        return;
    }

    // depth2d reads back a single depth scalar per texel, not a four-component color.
    float depth = depthIn.read(gid);
    if (depth == 0.0 || constants.snapshotReady == 0u) {
        maskOut.write(float4(1.0), gid);
        validOut.write(float4(0.0), gid);
        return;
    }

    float2 texCoord = (float2(gid) + 0.5) / float2(constants.size);
    float2 ndc = texCoord * 2.0 - 1.0;
    float4 clipPos = constants.invProjModelView * float4(ndc, depth, 1.0);
    float3 cameraRelative = clipPos.xyz / clipPos.w;

    // Normalized here rather than trusted from the constant buffer: the Java side may hand this
    // kernel a sun direction that was never itself normalized.
    float3 sunDir = normalize(constants.sunDir.xyz);
    float3 gridPos = constants.camAbs.xyz - constants.firstSectionTimes16.xyz + cameraRelative;

    // Finite-domain receiver validity: gridPos is relative to the window's own minimum corner (the same
    // origin MetalRtAcceleration.rebuildInstances places every instance's geometry against), so
    // floor(gridPos / 16) is this pixel's section position relative to that corner, and "inside the
    // currently loaded tier-0 window" is exactly that value landing in [0, windowDiameter) on every
    // axis: the same radius bound VoxelWindow.slotFor (VoxelWindow.java) applies before it ever
    // wraps a coordinate.
    int D = int(constants.windowDiameter);
    int3 localSection = int3(floor(gridPos / 16.0));
    if (any(localSection < 0) || any(localSection >= D)) {
        maskOut.write(float4(1.0), gid);
        validOut.write(float4(0.0), gid);
        return;
    }

    // See the header comment: bias along the flat geometric normal decoded from alpha when one was
    // supplied, else fall back to biasing along the sun direction. plagueDecodeGeometricNormal
    // returns exactly float3(0.0) when its own encoding says "no geometry" (a fresh, uninitialized
    // texture reads back all zero, decoding to alpha 0.0, which is one such code), so a zero-length
    // result is the fallback signal, not an error.
    float4 normalSample = normalIn.read(gid);
    float3 flatNormal = plagueDecodeGeometricNormal(normalSample.w, float3(0.0));
    float3 origin = length_squared(flatNormal) < 1e-6
            ? gridPos + sunDir * 0.05
            : gridPos + flatNormal * 0.01;

    // Mode2 certifies the same inner cube as sun depth. The one-block inset is the
    // supplemental-geometry spill bound: stopping at the full-cube exit would intersect an
    // unknown outside owner's expanded bounds and invalidate every otherwise-ready miss.
    // Both receiver and biased origin must remain inside; mode1 keeps its synthetic full cube.
    float domainMin = constants.snapshotReady == 2u ? 1.0 : 0.0;
    float domainMax = float(D) * 16.0 - domainMin;
    if (constants.snapshotReady == 2u && (any(gridPos < domainMin) || any(gridPos > domainMax)
            || any(origin < domainMin) || any(origin > domainMax))) {
        maskOut.write(float4(1.0), gid);
        validOut.write(float4(0.0), gid);
        return;
    }
    float distanceToExit = constants.maxDistance;
    for (uint axis = 0u; axis < 3u; axis++) {
        if (sunDir[axis] > 0.0) distanceToExit = min(distanceToExit, (domainMax - origin[axis]) / sunDir[axis]);
        else if (sunDir[axis] < 0.0) distanceToExit = min(distanceToExit, (domainMin - origin[axis]) / sunDir[axis]);
    }
    if (constants.snapshotReady == 2u && !(distanceToExit > 0.0)) {
        maskOut.write(float4(1.0), gid);
        validOut.write(float4(0.0), gid);
        return;
    }
    ray r(origin, sunDir, 0.0, max(distanceToExit, 0.0));

    float2 intersection = rtNearestIntersection(r, accelerationStructure, palette, faceTexture,
            instanceSlotMap, cutoutFlags, atlasIn, constants.snapshotReady == 2u);
    bool hit = isfinite(intersection.x);
    float stopT = min(intersection.x, max(distanceToExit, 0.0));
    bool valid = intersection.y > stopT;
    if (constants.snapshotReady == 2u) {
        valid = valid && rtRaySectionsReady(origin, sunDir, 0.0, stopT, hit,
                constants.windowDiameter,
                int3(constants.firstSectionX, constants.firstSectionY, constants.firstSectionZ), sectionReadiness);
    }
    maskOut.write(float4(hit ? 0.0 : 1.0), gid);
    validOut.write(float4(valid ? 1.0 : 0.0), gid);
}
