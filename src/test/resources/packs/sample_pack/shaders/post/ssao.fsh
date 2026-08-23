#version 330

#moj_import <fornax:globals.glsl>

#define SSAO_SAMPLE_COUNT 16

#define SSAO_ENABLED //[] compile "Ambient Occlusion"
#define u_SsaoRadius 0.5 //[0.1..2.0 step 0.05] runtime "SSAO Radius"
#define u_SsaoBias 0.02 //[0.0..0.1 step 0.005] runtime "SSAO Bias"

uniform sampler2D u_Input0; // builtin.gNormal
uniform sampler2D u_Input1; // builtin.depth

// Hemisphere sample kernel, tangent space (+Z = fragment normal). Baked at pack-author time from the
// same generation approach the old hardcoded SsaoManager.generateKernel() used (random direction,
// hemisphere-restricted Z, accelerating interpolation biasing samples toward the origin) -- ported to
// a compile-time constant array rather than a Java-uploaded uniform buffer, since the generic pack
// pipeline (FullscreenPassRunner) has no channel for a pack-custom uniform block beyond
// u_Globals/u_PackOptions/u_PassParams. Fixed forever (not reseeded per launch); this is purely a
// "break up sampling artifacts" pattern, not something that needs to vary.
const vec3 KERNEL_SAMPLES[SSAO_SAMPLE_COUNT] = vec3[](
    vec3(0.025027, -0.049714, 0.015797),
    vec3(-0.024795, 0.052541, 0.067379),
    vec3(-0.071973, -0.034038, 0.004856),
    vec3(0.066791, -0.079264, 0.028402),
    vec3(0.097175, 0.077044, 0.067486),
    vec3(0.109938, -0.058505, 0.076658),
    vec3(0.000269, 0.138408, 0.126971),
    vec3(-0.019184, -0.008823, 0.042492),
    vec3(0.132409, 0.081430, 0.115542),
    vec3(0.138479, -0.074834, 0.071639),
    vec3(-0.170936, -0.027832, 0.276604),
    vec3(-0.025985, 0.023225, 0.032827),
    vec3(-0.291182, -0.093356, 0.156325),
    vec3(-0.082207, 0.142687, 0.055715),
    vec3(-0.181711, -0.182211, 0.288666),
    vec3(0.070546, -0.263338, 0.230369)
);

in vec2 texCoord;
out float fragColor; // R8_UNORM: raw ambient occlusion, 1.0 = fully unoccluded, 0.0 = fully occluded

// Reconstructs a camera-relative position from a screen-space UV and an NDC (reversed-Z) depth --
// the same technique gbuffer_resolve.fsh already uses for its own fog-distance reconstruction.
vec3 reconstructPosition(vec2 uv, float ndcDepth) {
    vec4 clipPos = vec4(uv * 2.0 - 1.0, ndcDepth, 1.0);
    vec4 posH = u_InvProjModelView * clipPos;
    return posH.xyz / posH.w;
}

// Per-pixel rotation vector, replacing the old hardcoded pass's small tiled 4x4 noise texture (which
// had no equivalent slot in the generic pack pipeline -- no builtin.* binding exists for it). A
// standard hash-based procedural rotation achieves the same goal (break up banding from a fixed
// kernel orientation) without needing a texture at all, and has no tiling period.
vec2 rotationNoise(vec2 fragCoord) {
    float a = fract(sin(dot(fragCoord, vec2(12.9898, 78.233))) * 43758.5453);
    float b = fract(sin(dot(fragCoord, vec2(39.3468, 11.135))) * 24634.6345);
    return vec2(a, b) * 2.0 - 1.0;
}

void main() {
    float depth = texture(u_Input1, texCoord).r;

    // No opaque terrain here (see gbuffer_resolve.fsh's identical discard-depth convention) --
    // fully unoccluded, not a discard: this pass's own destination has no prior content worth
    // preserving, and every fragment must produce a defined value for ssao_blur to read.
    if (depth <= 0.0) {
        fragColor = 1.0;
        return;
    }

    vec3 normal = normalize(texture(u_Input0, texCoord).rgb);
    vec3 fragPos = reconstructPosition(texCoord, depth);

    vec3 randomVec = normalize(vec3(rotationNoise(gl_FragCoord.xy), 0.0));

    // Hemisphere basis: Gram-Schmidt orthogonalize the noise-derived randomVec directly against
    // normal (no separately-constructed tangent involved), then build the final TBN matrix used to
    // orient every kernel sample into this fragment's hemisphere.
    vec3 rotatedTangent = normalize(randomVec - normal * dot(randomVec, normal));
    vec3 rotatedBitangent = cross(normal, rotatedTangent);
    mat3 tbn = mat3(rotatedTangent, rotatedBitangent, normal);

    float fragDist = length(fragPos);

    float occlusion = 0.0;
    for (int i = 0; i < SSAO_SAMPLE_COUNT; i++) {
        vec3 samplePos = fragPos + (tbn * KERNEL_SAMPLES[i]) * u_SsaoRadius;

        vec4 sampleClip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(samplePos, 1.0);
        vec2 sampleUv = (sampleClip.xy / sampleClip.w) * 0.5 + 0.5;

        float actualNdcDepth = texture(u_Input1, sampleUv).r;
        vec3 actualPos = reconstructPosition(sampleUv, actualNdcDepth);

        // Linear-distance occlusion test, in blocks -- NOT a raw depth-buffer (NDC) comparison.
        // Reversed-Z depth is extremely non-linearly compressed near the camera, so a fixed
        // NDC-space bias has wildly different effective tolerance depending on distance and
        // geometry: a bias tight enough to register on nearby geometry suppresses all AO on
        // distant flat surfaces, and vice versa. Comparing camera-relative DISTANCES instead
        // (reusing the same reconstructed positions the range check below already needs) keeps
        // u_SsaoBias's meaning consistent in blocks, matching u_SsaoRadius's own units, regardless
        // of what's being looked at or how far away it is.
        float sampleDist = length(samplePos);
        float actualDist = length(actualPos);
        bool isOccluded = actualDist < (sampleDist - u_SsaoBias);

        // Range check: only count occlusion from geometry actually near the sample (by
        // camera-distance), so a distant unrelated surface that happens to be nearer to the camera
        // than this kernel sample doesn't wrongly darken it.
        float rangeCheck = smoothstep(0.0, 1.0, u_SsaoRadius / max(abs(fragDist - actualDist), 0.0001));

        occlusion += (isOccluded ? 1.0 : 0.0) * rangeCheck;
    }

    fragColor = 1.0 - (occlusion / float(SSAO_SAMPLE_COUNT));
}
