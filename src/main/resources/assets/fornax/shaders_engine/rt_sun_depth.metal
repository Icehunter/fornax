// Compiled after rt_trace.metal: the alpha intersection and readiness code is shared verbatim.
// Byte offsets: inverse light VP 0, camera grid position 64, light VP 80, bias 144,
// diameter 148, output uint2 152, first section int4 160. Total 176 bytes.
struct RtSunDepthConstants {
    float4x4 inverseSunViewProj;
    float4 cameraGridPosition;
    float4x4 sunViewProj;
    float bias;
    uint diameter;
    uint2 size;
    int4 firstSection;
};

// Inverse of q=p/(|p|*bias + 1-bias). Beyond q-radius 1/bias the warp has no inverse.
inline bool rtUnwarp(float2 q, float bias, thread float2& p) {
    float denominator = 1.0 - length(q) * bias;
    if (!(denominator > 0.0)) return false;
    p = q * ((1.0 - bias) / denominator);
    return all(isfinite(p));
}

kernel void rt_sun_depth(
        constant RtSunDepthConstants& constants [[buffer(0)]],
        instance_acceleration_structure accelerationStructure [[buffer(1)]],
        device const uint* palette [[buffer(2)]],
        device const uint* faceTexture [[buffer(3)]],
        device const uint* instanceSlotMap [[buffer(4)]],
        constant uint& cutoutFlags [[buffer(5)]],
        device const uint* sectionReadiness [[buffer(10)]],
        texture2d<float, access::sample> atlasIn [[texture(4)]],
        texture2d<float, access::write> sunDepthOut [[texture(0)]],
        uint2 gid [[thread_position_in_grid]]) {
    if (any(gid >= constants.size)) return;
    // Invalid is always zero, including rays outside the finite cube or the inverse warp domain.
    sunDepthOut.write(float4(0.0), gid);
    float2 p;
    if (!rtUnwarp((float2(gid) + 0.5) / float2(constants.size) * 2.0 - 1.0, constants.bias, p)) return;
    float4 nearClip = constants.inverseSunViewProj * float4(p, 0.0, 1.0);
    float4 farClip = constants.inverseSunViewProj * float4(p, 1.0, 1.0);
    float3 origin = nearClip.xyz / nearClip.w + constants.cameraGridPosition.xyz;
    float3 extent = farClip.xyz / farClip.w - nearClip.xyz / nearClip.w;
    float lengthToFar = length(extent);
    float3 direction = extent / lengthToFar;
    if (!all(isfinite(origin)) || !all(isfinite(direction)) || !(lengthToFar > 0.0)) return;
    float entryT, exitT;
    // The supplementary geometry ABI permits at most one block beyond an owner's section.
    // Removing the outer shell prevents absent outside owners certifying an interior miss.
    if (!rtRayBox(origin, direction, float3(1.0), float3(float(constants.diameter) * 16.0 - 1.0), entryT, exitT)) return;
    entryT = max(entryT, 0.0);
    exitT = min(exitT, lengthToFar);
    if (!(exitT > entryT)) return;
    ray r(origin, direction, entryT, exitT);
    float2 intersection = rtNearestIntersection(r, accelerationStructure, palette, faceTexture,
            instanceSlotMap, cutoutFlags, atlasIn, true);
    float stopT = min(intersection.x, exitT);
    if (intersection.y <= stopT || !rtRaySectionsReady(origin, direction, entryT, stopT, isfinite(intersection.x),
            constants.diameter, constants.firstSection.xyz, sectionReadiness)) return;
    // Orthographic forward depth is affine along this exact inverse-projection ray. 0 and 1
    // are its near/far endpoints, so these ratios match raster without cancellation at large Z.
    float hitDepth = isfinite(intersection.x) ? intersection.x / lengthToFar : 1.0;
    sunDepthOut.write(float4(hitDepth, entryT / lengthToFar, exitT / lengthToFar, 1.0), gid);
}
