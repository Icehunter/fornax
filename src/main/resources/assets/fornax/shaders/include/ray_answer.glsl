// Fornax ray-answer decode, shared by every pack that reads a ray-traced result.
//
// One logical record in two physical shapes. The image shape is an RGBA32F texel:
//   R = value (forward light depth along the ray, 1.0 for a traced miss)
//   G = the tier that wrote this texel, RayTier.ordinal() as an exact float
//   B = reserved, 0.0
//   A = validity, 1.0 when a provider answered this texel this frame
//
// Read A first. When A is not set nothing else in the texel means anything: an untraced or
// invalidated target reads back all zeros, and zero is a legal value for R and for G. A provider
// writes value, tier and validity in one store, so G is never nonzero with A zero.
//
// The tier constants below are RayTier's ordinals. A test reads this file and the enum together,
// because a drifted copy compiles, samples and lights a frame with the wrong fallback.

const int FORNAX_RAY_TIER_NONE = 0;
const int FORNAX_RAY_TIER_SOFTWARE_VOXEL = 1;
const int FORNAX_RAY_TIER_HARDWARE_VOXEL = 2;
const int FORNAX_RAY_TIER_HARDWARE_MESH = 3;

// True when a provider wrote this answer. Everything else is only meaningful after this returns true.
bool fornaxRayAnswered(vec4 answer) {
    return answer.a > 0.5;
}

// Which tier wrote it. Compare with >= for a minimum, == for an exact source. The rounding is
// deliberate: the channel is a float carrying a small integer, and a sampler may hand back a value
// a fraction off its stored one.
int fornaxRayTier(vec4 answer) {
    return int(answer.g + 0.5);
}
