#version 330

uniform sampler2D u_Motion;
uniform sampler2D u_Depth;
layout(std140) uniform u_SkyMotionSettings {
    mat4 u_SkyReprojection;
    vec4 u_CurrentJitterNdc;
};

in vec2 texCoord;
out vec2 fragMotion;

// Same reversed-Z far-clear classification as temporal_accumulate.fsh/reconstruct.fsh.
#define SKY_DEPTH_EPSILON 0.0000001

void main() {
    ivec2 pixel = ivec2(texCoord * vec2(textureSize(u_Motion, 0)));
    float depth = texelFetch(u_Depth, pixel, 0).r;
    if (depth > SKY_DEPTH_EPSILON) {
        fragMotion = texelFetch(u_Motion, pixel, 0).rg;
        return;
    }

    // The motion convention is currentUV-previousUV. This finite sentinel sends previousUV to
    // (-2,-2), outside history, matching the engine temporal resolve's behind-eye rejection.
    // Reject before RG16F storage: a near-zero projective denominator must never export infinity.
    fragMotion = texCoord + vec2(2.0);
    // The input is rasterized with jitter, while gMotion measures unjittered positions. Remove
    // this frame's NDC jitter (UV spans half NDC) before applying the jitter-free homography.
    // Previous jitter stays excluded: MetalFX receives jitter separately from motion.
    vec2 jitterFreeUv = texCoord - u_CurrentJitterNdc.xy * 0.5;
    vec3 previousH = mat3(u_SkyReprojection) * vec3(jitterFreeUv * 2.0 - 1.0, 1.0);
    if (any(isnan(previousH)) || any(isinf(previousH)) || previousH.z <= 0.0) return;
    vec2 previousUv = previousH.xy / previousH.z * 0.5 + 0.5;
    if (any(isnan(previousUv)) || any(isinf(previousUv))
            || any(lessThan(previousUv, vec2(0.0)))
            || any(greaterThan(previousUv, vec2(1.0)))) return;
    fragMotion = jitterFreeUv - previousUv;
}
