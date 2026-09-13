#include <metal_stdlib>

using namespace metal;

// Engine-owned brick-grid to triangle expansion for the Metal ray tracing sun-shadow spike.
//
// One threadgroup per slot, each thread striding across the slot's 4096 voxels by however many
// threads the Java side actually dispatched per group. The stride comes from
// [[threads_per_threadgroup]], read from the dispatch itself rather than assumed, so the kernel
// stays correct regardless of the group size Java picks (e.g. 1024, to stay under a typical
// maxTotalThreadsPerThreadgroup of 1024). The Java side binds every buffer at that slot's own byte
// offset before dispatch, so this kernel never sees a slot index: buffer index 0 already starts at
// the target slot's first voxel.
//
// voxelIndex packs a voxel's local (x, y, z) exactly as BrickGridUpload writes it:
// (y << 8) | (z << 4) | x, each axis 0..15. Occupancy is one bit per voxel, LSB-first within its
// byte (bit v % 8 of byte v / 8). Payload and face-seal are one byte per voxel. Palette entries
// are 16 uint words each; word 0's low 4 bits are boxCount (0 = FULL cube, else PARTIAL/CROSS),
// see BrickGridUpload's own palette layout comment for the rest of that word. A packed box word
// holds six 5-bit coordinates in 1/16-block units 0..16: minX | minY<<5 | minZ<<10 | maxX<<15 |
// maxY<<20 | maxZ<<25.
//
// Face index order matches Direction.get3DDataValue(): 0 down, 1 up, 2 north (-z), 3 south (+z),
// 4 west (-x), 5 east (+x). Opposite faces pair by flipping bit 0: down/up, north/south, west/east.
//
// Face-seal bit N is shape-only information about THIS voxel: it means this voxel's own shape
// fully covers that face's 16x16 boundary square (FaceSealResolver; a FULL cube has all six bits
// set). It says nothing about whether a neighbor is actually there to be hidden behind. A FULL
// voxel's face is exposed, and emitted, unless the neighbor in that direction also lies inside
// this slot, is itself occupied, is not a cross entry (a real plant has gaps in its shape and
// never seals a neighbor's face), NEITHER side of the shared face is cutout (leaves: a full cube
// with a see-through texture: rt_trace.metal alpha-tests cutout geometry per face, and needs
// this exact face's own triangle to test the right point rather than skip through to whichever
// face the ray reaches next), and both this voxel's own seal bit for the face and the neighbor's
// seal bit for the opposite face are set (both surfaces fully cover the shared boundary). See
// face_exposed. A PARTIAL voxel's boxes can sit anywhere inside the cell, so every box still emits
// all six faces regardless of any seal bit. A CROSS voxel (grass, ferns, flowers, crops, saplings)
// emits the real diagonal "X" its model bakes, not its bounding box: two crossing quads built from
// the one box the palette carries (box slot 0, boxCount 1), reconstructed as the corner-to-corner
// planes FaceColorResolver.resolveCrossGeometry's own doc describes (see emit_cross below).
// Neither plane ever seals a neighbor's face: a real plant has gaps a shadow ray must stay free
// to pass through, so a CROSS neighbor is never treated as occluding in face_exposed, same as
// before. rt_trace.metal alpha-tests each plane against the palette's own embedded atlas UV rect
// (words 13/14, the same words a FULL cutout entry's rect lives in), not against voxelFaceTexture,
// which has no per-face concept for a diagonal plane.

constant uint VOXELS_PER_SLOT = 4096;
constant uint PALETTE_ENTRY_WORDS = 16;
constant uint MAX_PALETTE_ENTRIES = 96; // SectionHarvester.MAX_PALETTE_ENTRIES
constant uint MAX_BOXES = 8; // VoxelShapeClassifier.MAX_BOXES, palette words 7..14
constant uint CUTOUT_BIT = 1u << 30; // palette word 0, matches BrickGridUpload's packing
constant uint CROSS_BIT = 1u << 31;  // palette word 0, matches BrickGridUpload's packing

// Local (x, y, z) offset to the neighbor across each face, in Direction.get3DDataValue() order.
constant int3 FACE_DELTA[6] = {
    int3(0, -1, 0), int3(0, 1, 0), int3(0, 0, -1), int3(0, 0, 1), int3(-1, 0, 0), int3(1, 0, 0)
};

// Claims one triangle slot in the shared counter. Past maxTriangles, counts the drop instead and
// reports the overflow through `overflowed` so the caller skips writing vertex/primitive data for
// a slot that was never really claimed.
inline uint claim_triangle(device atomic_uint* counters, constant uint& maxTriangles,
        thread bool& overflowed) {
    uint index = atomic_fetch_add_explicit(&counters[0], 1u, memory_order_relaxed);
    if (index >= maxTriangles) {
        atomic_fetch_add_explicit(&counters[1], 1u, memory_order_relaxed);
        overflowed = true;
        return 0u;
    }
    overflowed = false;
    return index;
}

inline void write_triangle(device float* vertexOut, device uint* primitiveOut, uint triangleIndex,
        float3 v0, float3 v1, float3 v2, uint voxelIndex, uint face, uint paletteIndex) {
    uint base = triangleIndex * 9u;
    vertexOut[base + 0u] = v0.x;
    vertexOut[base + 1u] = v0.y;
    vertexOut[base + 2u] = v0.z;
    vertexOut[base + 3u] = v1.x;
    vertexOut[base + 4u] = v1.y;
    vertexOut[base + 5u] = v1.z;
    vertexOut[base + 6u] = v2.x;
    vertexOut[base + 7u] = v2.y;
    vertexOut[base + 8u] = v2.z;
    primitiveOut[triangleIndex] = voxelIndex | (face << 12u) | (paletteIndex << 16u);
}

// The four corners of one axis-aligned box face, in Direction order, walked clockwise as seen
// from outside the box (see emit_face, which reverses each triangle it builds from these corners
// to make the actual emitted geometry counter-clockwise from outside). Winding is not load-bearing
// at trace time, since triangle culling is disabled there, but a consistent winding keeps the
// geometry sane to inspect.
inline void face_corners(float3 boxMin, float3 boxMax, uint face,
        thread float3& c0, thread float3& c1, thread float3& c2, thread float3& c3) {
    switch (face) {
        case 0u: // down, y = boxMin.y
            c0 = float3(boxMin.x, boxMin.y, boxMin.z);
            c1 = float3(boxMin.x, boxMin.y, boxMax.z);
            c2 = float3(boxMax.x, boxMin.y, boxMax.z);
            c3 = float3(boxMax.x, boxMin.y, boxMin.z);
            break;
        case 1u: // up, y = boxMax.y
            c0 = float3(boxMin.x, boxMax.y, boxMin.z);
            c1 = float3(boxMax.x, boxMax.y, boxMin.z);
            c2 = float3(boxMax.x, boxMax.y, boxMax.z);
            c3 = float3(boxMin.x, boxMax.y, boxMax.z);
            break;
        case 2u: // north, z = boxMin.z
            c0 = float3(boxMin.x, boxMin.y, boxMin.z);
            c1 = float3(boxMax.x, boxMin.y, boxMin.z);
            c2 = float3(boxMax.x, boxMax.y, boxMin.z);
            c3 = float3(boxMin.x, boxMax.y, boxMin.z);
            break;
        case 3u: // south, z = boxMax.z
            c0 = float3(boxMin.x, boxMin.y, boxMax.z);
            c1 = float3(boxMin.x, boxMax.y, boxMax.z);
            c2 = float3(boxMax.x, boxMax.y, boxMax.z);
            c3 = float3(boxMax.x, boxMin.y, boxMax.z);
            break;
        case 4u: // west, x = boxMin.x
            c0 = float3(boxMin.x, boxMin.y, boxMin.z);
            c1 = float3(boxMin.x, boxMax.y, boxMin.z);
            c2 = float3(boxMin.x, boxMax.y, boxMax.z);
            c3 = float3(boxMin.x, boxMin.y, boxMax.z);
            break;
        default: // east (5), x = boxMax.x
            c0 = float3(boxMax.x, boxMin.y, boxMin.z);
            c1 = float3(boxMax.x, boxMin.y, boxMax.z);
            c2 = float3(boxMax.x, boxMax.y, boxMax.z);
            c3 = float3(boxMax.x, boxMax.y, boxMin.z);
            break;
    }
}

inline void emit_face(device float* vertexOut, device uint* primitiveOut,
        device atomic_uint* counters, constant uint& maxTriangles,
        float3 boxMin, float3 boxMax, uint face, uint voxelIndex, uint paletteIndex) {
    float3 c0, c1, c2, c3;
    face_corners(boxMin, boxMax, face, c0, c1, c2, c3);

    // face_corners lists each face's corners walking around it in the +u,+v sense of its own two
    // in-plane axes, which puts (c0, c1, c2) and (c0, c2, c3) clockwise as seen from outside the
    // box; reversing each triangle here (c0, c2, c1) and (c0, c3, c2) is what actually makes the
    // emitted geometry counter-clockwise from outside, matching face_corners' own doc comment.
    bool overflowed;
    uint t0 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t0, c0, c2, c1, voxelIndex, face, paletteIndex);
    }
    uint t1 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t1, c0, c3, c2, voxelIndex, face, paletteIndex);
    }
}

// Reconstructs a CROSS entry's two diagonal "X" planes from its one packed box (see
// FaceColorResolver.resolveCrossGeometry's own doc for why only a bounding box is stored rather
// than the model's exact quad corners) and emits both, 2 triangles each: plane A runs corner to
// corner from (boxMin.x, *, boxMin.z) to (boxMax.x, *, boxMax.z), plane B the other diagonal, from
// (boxMin.x, *, boxMax.z) to (boxMax.x, *, boxMin.z), both spanning the box's full height. Face id
// 6/7 (past the six real Direction values 0-5) marks which plane a triangle belongs to; rt_trace's
// own CROSS alpha test derives (s, t) from the hit position and this same box directly, needing no
// face-specific math, but the id is kept distinct for anything else that ever inspects primitive
// data. Two-sided by construction (no back-face culling is enabled at trace time), so one winding
// per plane is enough.
inline void emit_cross(device float* vertexOut, device uint* primitiveOut,
        device atomic_uint* counters, constant uint& maxTriangles,
        float3 boxMin, float3 boxMax, uint voxelIndex, uint paletteIndex) {
    float3 a0 = float3(boxMin.x, boxMin.y, boxMin.z);
    float3 a1 = float3(boxMax.x, boxMin.y, boxMax.z);
    float3 a2 = float3(boxMax.x, boxMax.y, boxMax.z);
    float3 a3 = float3(boxMin.x, boxMax.y, boxMin.z);

    float3 b0 = float3(boxMin.x, boxMin.y, boxMax.z);
    float3 b1 = float3(boxMax.x, boxMin.y, boxMin.z);
    float3 b2 = float3(boxMax.x, boxMax.y, boxMin.z);
    float3 b3 = float3(boxMin.x, boxMax.y, boxMax.z);

    bool overflowed;
    uint t0 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t0, a0, a1, a2, voxelIndex, 6u, paletteIndex);
    }
    uint t1 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t1, a0, a2, a3, voxelIndex, 6u, paletteIndex);
    }
    uint t2 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t2, b0, b1, b2, voxelIndex, 7u, paletteIndex);
    }
    uint t3 = claim_triangle(counters, maxTriangles, overflowed);
    if (!overflowed) {
        write_triangle(vertexOut, primitiveOut, t3, b0, b2, b3, voxelIndex, 7u, paletteIndex);
    }
}

// Whether a FULL voxel's face should actually be emitted. A face is exposed unless a real
// occluding neighbor is sitting right behind it: the neighbor must lie inside this slot, be
// occupied, not be a cross entry, NEITHER this voxel nor the neighbor may be cutout (see the file
// header comment), and both this voxel's own seal bit for the face and the neighbor's seal bit for
// the opposite face must be set (see the file header comment for why both bits are required). A
// neighbor outside the slot, unoccupied, cross, or one whose payload byte is out of range is
// treated as not sealing, the same conservative default the payload bound check above uses.
inline bool face_exposed(
        device const uchar* occupancy, device const uchar* payload,
        device const uchar* faceSeal, device const uint* palette,
        uint seal, uint face, int3 local, bool ownCutout) {
    int3 neighbor = local + FACE_DELTA[face];
    if (any(neighbor < 0) || any(neighbor > 15)) {
        return true;
    }
    uint neighborIndex = (uint(neighbor.y) << 8u) | (uint(neighbor.z) << 4u) | uint(neighbor.x);

    uint neighborOccupancyByte = occupancy[neighborIndex >> 3u];
    if (((neighborOccupancyByte >> (neighborIndex & 7u)) & 1u) == 0u) {
        return true;
    }

    uint neighborPaletteIndex = uint(payload[neighborIndex]);
    if (neighborPaletteIndex >= MAX_PALETTE_ENTRIES) {
        return true;
    }
    uint neighborWord0 = palette[neighborPaletteIndex * PALETTE_ENTRY_WORDS];
    if ((neighborWord0 & CROSS_BIT) != 0u) {
        return true;
    }
    if (ownCutout || (neighborWord0 & CUTOUT_BIT) != 0u) {
        return true;
    }

    uint neighborSeal = uint(faceSeal[neighborIndex]);
    uint oppositeFace = face ^ 1u;
    bool ownSealed = ((seal >> face) & 1u) != 0u;
    bool neighborSealed = ((neighborSeal >> oppositeFace) & 1u) != 0u;
    return !(ownSealed && neighborSealed);
}

kernel void rt_expand(
        device const uchar* occupancy [[buffer(0)]],
        device const uchar* payload [[buffer(1)]],
        device const uchar* faceSeal [[buffer(2)]],
        device const uint* palette [[buffer(3)]],
        device float* vertexOut [[buffer(4)]],
        device uint* primitiveOut [[buffer(5)]],
        // counters[0] is the triangle count, counters[1] the overflow count. counters[0] keeps
        // incrementing on every claim attempt past maxTriangles, not just successful ones, so the
        // real triangle count to build the acceleration structure with is min(counters[0],
        // maxTriangles), not counters[0] itself.
        device atomic_uint* counters [[buffer(6)]],
        constant uint& maxTriangles [[buffer(7)]],
        constant uint& supplementalCross [[buffer(8)]],
        uint tid [[thread_position_in_threadgroup]],
        uint threadsPerGroup [[threads_per_threadgroup]]) {
    for (uint voxelIndex = tid; voxelIndex < VOXELS_PER_SLOT; voxelIndex += threadsPerGroup) {
        uint occupancyByte = occupancy[voxelIndex >> 3u];
        if (((occupancyByte >> (voxelIndex & 7u)) & 1u) == 0u) {
            continue;
        }

        uint paletteIndex = uint(payload[voxelIndex]);
        if (paletteIndex >= MAX_PALETTE_ENTRIES) {
            // A stale or garbage payload byte must never read past this slot's palette table.
            continue;
        }
        uint entryBase = paletteIndex * PALETTE_ENTRY_WORDS;
        uint boxCount = min(palette[entryBase] & 0xFu, MAX_BOXES);

        uint lx = voxelIndex & 0xFu;
        uint lz = (voxelIndex >> 4u) & 0xFu;
        uint ly = (voxelIndex >> 8u) & 0xFu;
        float3 voxelPos = float3(float(lx), float(ly), float(lz));

        if (boxCount == 0u) {
            uint seal = uint(faceSeal[voxelIndex]);
            bool ownCutout = (palette[entryBase] & CUTOUT_BIT) != 0u;
            int3 local = int3(int(lx), int(ly), int(lz));
            float3 boxMax = voxelPos + float3(1.0);
            for (uint face = 0u; face < 6u; face++) {
                if (!face_exposed(occupancy, payload, faceSeal, palette, seal, face, local, ownCutout)) {
                    continue;
                }
                emit_face(vertexOut, primitiveOut, counters, maxTriangles,
                        voxelPos, boxMax, face, voxelIndex, paletteIndex);
            }
        } else if ((palette[entryBase] & CROSS_BIT) != 0u) {
            // Production supplies the actual displaced baked triangles in a second geometry.
            // Retaining this approximation too would cast both shifted and unshifted silhouettes.
            if (supplementalCross != 0u) continue;
            // CROSS geometry (grass, ferns, flowers, crops, saplings): the single box (box slot 0,
            // boxCount 1) is the model's real bounding box, not an occluding shape: reconstruct
            // the two diagonal planes it bounds instead of treating it as a solid cube (see
            // emit_cross's own doc and the file header comment).
            uint word = palette[entryBase + 7u];
            float3 boxMin16 = float3(float(word & 0x1Fu), float((word >> 5u) & 0x1Fu),
                    float((word >> 10u) & 0x1Fu));
            float3 boxMax16 = float3(float((word >> 15u) & 0x1Fu), float((word >> 20u) & 0x1Fu),
                    float((word >> 25u) & 0x1Fu));
            float3 boxMin = voxelPos + boxMin16 * (1.0 / 16.0);
            float3 boxMax = voxelPos + boxMax16 * (1.0 / 16.0);
            emit_cross(vertexOut, primitiveOut, counters, maxTriangles, boxMin, boxMax, voxelIndex, paletteIndex);
        } else {
            // PARTIAL geometry (doors, panes, bars, trapdoors): real occluding boxes, so every one
            // emits all six faces.
            for (uint b = 0u; b < boxCount; b++) {
                uint word = palette[entryBase + 7u + b];
                float3 boxMin16 = float3(float(word & 0x1Fu), float((word >> 5u) & 0x1Fu),
                        float((word >> 10u) & 0x1Fu));
                float3 boxMax16 = float3(float((word >> 15u) & 0x1Fu), float((word >> 20u) & 0x1Fu),
                        float((word >> 25u) & 0x1Fu));
                float3 boxMin = voxelPos + boxMin16 * (1.0 / 16.0);
                float3 boxMax = voxelPos + boxMax16 * (1.0 / 16.0);
                for (uint face = 0u; face < 6u; face++) {
                    emit_face(vertexOut, primitiveOut, counters, maxTriangles,
                            boxMin, boxMax, face, voxelIndex, paletteIndex);
                }
            }
        }
    }
}
