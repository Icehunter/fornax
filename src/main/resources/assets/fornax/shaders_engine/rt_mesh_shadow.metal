#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace metal::raytracing;

// The surface word first, then three UV pairs: 32 bytes, matching the Java primitive-data stride.
//
// Word 0 is the surface word every geometry record in this engine starts with, so one kernel can
// decode a face from any of them without knowing which structure it traced. Bits 12-15 name the
// face; bit 30 says six UV floats follow at byte 8; bit 31 says they follow at byte 4, which is
// where the voxel supplement record keeps them. The order matters: the voxel box record is a single
// word, so a reader that assumed UVs at a fixed offset would read a neighbouring triangle's word
// as a coordinate.
struct MeshShadowPrimitive { uint surface; uint pad; float2 uv0; float2 uv1; float2 uv2; };

// Set in a mesh record's surface word. Distinct from the voxel supplement's bit 31 so one decode
// rule covers both, and high enough to stay clear of the voxel index, face and palette fields.
constant uint MESH_SURFACE_BIT = 1u << 30;

kernel void mesh_shadow_decode(device const ushort* packed [[buffer(0)]],
        device float* positions [[buffer(1)]], device MeshShadowPrimitive* primitives [[buffer(2)]],
        constant uint& triangleCount [[buffer(3)]], uint triangle [[thread_position_in_grid]]) {
    if (triangle >= triangleCount) return;
    // The uploaded quad index ABI is (0,1,2),(2,3,0). Preserve its selected diagonal/winding.
    constexpr uint corner[6] = {0,1,2,2,3,0};
    float2 uv[3];
    for (uint v=0; v<3; ++v) {
        // FornaxChunkVertex's 24-byte stride, position bytes 0/2/4 and UV bytes 8/10.
        uint source = ((triangle / 2) * 4 + corner[(triangle % 2) * 3 + v]) * 12;
        float3 p = float3(packed[source],packed[source+1],packed[source+2]) / 2048.0 - 8.0;
        uint destination = triangle*9 + v*3;
        positions[destination]=p.x; positions[destination+1]=p.y; positions[destination+2]=p.z;
        uv[v]=float2(packed[source+4],packed[source+5]) / 65535.0;
    }
    // FornaxChunkVertex byte 20 holds Direction.ordinal() for the whole quad, so the face comes
    // from its first vertex: ushort index 10 of that vertex, low byte. A value outside 0..5 is not
    // a face this engine can name, and writes 15 so rt_face_normal returns the zero vector rather
    // than a plausible wrong axis.
    uint face = packed[(triangle / 2) * 4 * 12 + 10] & 0xFFu;
    if (face > 5u) face = 0xFu;
    primitives[triangle] = {MESH_SURFACE_BIT | (face << 12u), 0u, uv[0], uv[1], uv[2]};
}

// Column-major matrices, camera float4, radius/bias floats, resolution uint, filter guard float and
// the tier. Java supplies exactly 176 bytes; never place a scalar after float3.
// 176 bytes: the float4x4 members give the struct 16-byte alignment, so the five trailing scalars
// occupy bytes 144..163 and three words of tail padding follow. MeshShadowTracer.CONSTANT_BYTES
// must agree; a short allocation reads the tier out of unmapped memory.
struct MeshShadowConstants {
    float4x4 inverseLightVp;
    float4x4 lightVp;
    float4 camera;
    float radius;
    float bias;
    uint resolution;
    float filterGuardUv;
    // RayTier.ordinal() of whichever traversal this dispatch is. Written into G on a traced texel
    // so a reader can tell which tier answered without knowing which pass ran.
    uint tier;
};

// A receiver filter can request a neighboring shadow UV even when that ray's center misses the
// cylinder. Bound the displacement of that ray over the declared per-axis UV guard. Differentiating
// p(q)=(1-b)q/(1-b|q|) gives radial eigenvalue (1-b)/(1-b|q|)^2, which bounds the tangential one.
// The farthest corner of q +/- 2*guard bounds |q|; sqrt(2)*2*guard bounds the square's displacement.
inline bool meshShadowReceiverRayRelevant(constant MeshShadowConstants& c,float2 q,
        float3 origin,float3 extent) {
    float radius=c.radius;
    if (c.filterGuardUv>0.0) {
        float2 farthest=abs(q)+2.0*c.filterGuardUv;
        float denominator=1.0-c.bias*length(farthest);
        // A warp pole or a non-affine projection has no finite bound from these two columns.
        // Keep the center ray; a conservative extra trace cannot remove a receiver's shadow.
        if (!(denominator>0.0) || c.inverseLightVp[0].w!=0.0 || c.inverseLightVp[1].w!=0.0
                || c.inverseLightVp[2].w!=0.0 || c.inverseLightVp[3].w==0.0) return true;
        float2 worldX=c.inverseLightVp[0].xz/c.inverseLightVp[3].w;
        float2 worldY=c.inverseLightVp[1].xz/c.inverseLightVp[3].w;
        // Frobenius norm bounds the world-XZ projection's operator norm, including tilted sun axes.
        float projectionBound=sqrt(dot(worldX,worldX)+dot(worldY,worldY));
        float warpBound=(1.0-c.bias)/(denominator*denominator);
        radius+=projectionBound*warpBound*length(float2(2.0*c.filterGuardUv));
        if (!isfinite(radius)) return true;
    }
    // Closest point on the FULL near/far segment to the infinite vertical receiver cylinder.
    // Vertical light rays have no horizontal extent and keep their constant XZ distance.
    float horizontalLengthSquared=dot(extent.xz,extent.xz);
    if (!isfinite(horizontalLengthSquared)) return true;
    float t=horizontalLengthSquared>0.0
        ? clamp(-dot(origin.xz,extent.xz)/horizontalLengthSquared,0.0,1.0) : 0.0;
    float2 closest=origin.xz+t*extent.xz;
    if (!all(isfinite(closest))) return true;
    return dot(closest,closest)<=radius*radius;
}

kernel void mesh_shadow_clear(texture2d<float,access::write> output [[texture(0)]],
        uint2 pixel [[thread_position_in_grid]]) {
    if (pixel.x<output.get_width() && pixel.y<output.get_height())
        output.write(float4(1.0,0.0,0.0,0.0),pixel);
}

kernel void mesh_shadow_trace(constant MeshShadowConstants& c [[buffer(0)]],
        instance_acceleration_structure scene [[buffer(1)]],
        texture2d<float,access::sample> atlas [[texture(0)]],
        texture2d<float,access::write> output [[texture(1)]],
        uint2 pixel [[thread_position_in_grid]]) {
    if (any(pixel >= uint2(c.resolution))) return;
    float depth=1.0;
    // Inverse of shadow.vsh's radial warp q=p/(|p|*bias+1-bias).
    float2 q=(float2(pixel)+0.5)/float(c.resolution)*2.0-1.0;
    float denominator=1.0-length(q)*c.bias;
    if (!(denominator>0.0)) { output.write(float4(depth,0.0,0.0,0.0),pixel); return; }
    float2 p=q*((1.0-c.bias)/denominator);
    // The caller captured the unwarped light volume only; never certify its exterior as a miss.
    if (any(abs(p)>1.0)) { output.write(float4(depth,0.0,0.0,0.0),pixel); return; }
    float4 nearPoint=c.inverseLightVp*float4(p,0.0,1.0);
    float4 farPoint=c.inverseLightVp*float4(p,1.0,1.0);
    float3 relativeOrigin=nearPoint.xyz/nearPoint.w;
    float3 origin=relativeOrigin+c.camera.xyz;
    float3 extent=farPoint.xyz/farPoint.w-relativeOrigin;
    float rayLength=length(extent);
    if (!all(isfinite(origin)) || !all(isfinite(extent)) || !isfinite(rayLength) || !(rayLength>0.0)
            || !meshShadowReceiverRayRelevant(c,q,relativeOrigin,extent)) {
        output.write(float4(depth,0.0,0.0,0.0),pixel);return;
    }
    {
        intersection_params params;
        params.force_opacity(forced_opacity::non_opaque);
        // Raster shadow pipelines disable face culling. Both sides of an uploaded triangle
        // can block light; only the shared alpha test below rejects its covered texels.
        params.set_triangle_cull_mode(triangle_cull_mode::none);
        intersection_query<triangle_data,instancing> query;
        // Cylinder membership only selects rays. Clipping this interval would discard distant
        // blockers that cast shadows on nearby receivers, especially when the sun is low.
        query.reset(ray(origin,extent/rayLength,0.0,rayLength),scene,params);
        while(query.next()) {
            if (query.get_candidate_intersection_type()!=intersection_type::triangle) continue;
            device const MeshShadowPrimitive* data=
                (device const MeshShadowPrimitive*)query.get_candidate_primitive_data();
            float2 bary=query.get_candidate_triangle_barycentric_coord();
            float2 uv=data->uv0*(1.0-bary.x-bary.y)+data->uv1*bary.x+data->uv2*bary.y;
            constexpr sampler nearestAtlas(coord::normalized,address::clamp_to_edge,filter::nearest);
            // Raster ABI: engine shaders/blocks/shadow.fsh discards alpha < 0.1 for BOTH passes.
            if (atlas.sample(nearestAtlas,uv).a>=0.1) query.commit_triangle_intersection();
        }
        if (query.get_committed_intersection_type()!=intersection_type::none) {
            float3 hit=origin+(extent/rayLength)*query.get_committed_distance();
            float4 clip=c.lightVp*float4(hit-c.camera.xyz,1.0);
            depth=clamp(clip.z/clip.w,0.0,1.0);
        }
    }
    // A traced miss is valid; skipped rays and empty scenes require raster fallback. G names the
    // answering tier and is written in the same store as validity, so G is never nonzero with A zero.
    output.write(float4(depth,float(c.tier),0.0,1.0),pixel);
}
