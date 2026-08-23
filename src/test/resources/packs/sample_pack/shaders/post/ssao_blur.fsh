#version 330

uniform sampler2D u_Input0; // ssaoRaw
uniform sampler2D u_Input1; // ssao.history (last frame's blended AO -- engine ping-pong swap)
uniform sampler2D u_Input2; // builtin.gMotion
uniform sampler2D u_Input3; // builtin.depth

#define SSAO_BLUR_RADIUS 2

// Weight given to history (the reprojected previous frame's AO) when it's valid; (1.0 - this) is the
// weight given to this frame's own raw-blurred value. Standard exponential-moving-average weighting.
#define SSAO_TEMPORAL_BLEND 0.9

// Starting tuning constant, not a derived value -- reversed-Z depth is highly non-linear with
// distance, so a single fixed threshold is inherently approximate. Adjust if disocclusion rejects
// too eagerly (stale-AO ghosting persists) or too readily (valid history gets discarded
// constantly, AO never smooths out).
#define SSAO_DISOCCLUSION_DEPTH_THRESHOLD 0.05

in vec2 texCoord;
out float fragColor;

void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(u_Input0, 0));

    float sum = 0.0;
    float count = 0.0;
    for (int x = -SSAO_BLUR_RADIUS; x <= SSAO_BLUR_RADIUS; x++) {
        for (int y = -SSAO_BLUR_RADIUS; y <= SSAO_BLUR_RADIUS; y++) {
            sum += texture(u_Input0, texCoord + vec2(float(x), float(y)) * texelSize).r;
            count += 1.0;
        }
    }
    float blurred = sum / count;

    // Reprojection: v_MotionVector (see terrain.vsh) is currentUV - previousUV, so
    // subtracting it from this fragment's current UV recovers the same surface point's prior
    // screen position.
    vec2 motion = texture(u_Input2, texCoord).rg;
    vec2 previousUv = texCoord - motion;

    bool validHistory = previousUv.x >= 0.0 && previousUv.x <= 1.0 && previousUv.y >= 0.0 && previousUv.y <= 1.0;

    if (validHistory) {
        // Depth-similarity disocclusion check: both samples come from THIS frame's own depth buffer
        // (the previous frame's depth isn't separately retained) -- if nothing changed at this
        // screen position, the reprojected point should land on genuinely similar depth.
        float depthAtFragment = texture(u_Input3, texCoord).r;
        float depthAtReprojectedPosition = texture(u_Input3, previousUv).r;
        if (abs(depthAtFragment - depthAtReprojectedPosition) > SSAO_DISOCCLUSION_DEPTH_THRESHOLD) {
            validHistory = false;
        }
    }

    if (validHistory) {
        float previousAo = texture(u_Input1, previousUv).r;
        fragColor = mix(blurred, previousAo, SSAO_TEMPORAL_BLEND);
    } else {
        fragColor = blurred;
    }
}
