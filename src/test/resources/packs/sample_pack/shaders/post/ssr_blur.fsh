#version 330

// Used unchanged for both the Fancy (full-res) and Fast (half-res raw, upsampled by ordinary hardware
// bilinear filtering through the texture() calls below) quality tiers -- see graph.toml's
// ssr_blur_fancy/ssr_blur_fast passes, which bind this same shader to differently-scaled raw/output
// targets. Deliberate v0.1 simplification: the old hardcoded resolve pass additionally ran a
// depth-aware 4-tap bilateral upsample specifically for the Fast half-res case (to avoid reflections
// bleeding across silhouette edges); that extra pass was not ported here, so Fast-preset SSR is very
// slightly softer at object edges than the original build. Not a temporal-correctness concern: the
// ping-pong history read (u_Input1 below) is unaffected by this spatial simplification.

uniform sampler2D u_Input0; // ssrRaw or ssrRawFull, depending on which pass variant binds this shader
uniform sampler2D u_Input1; // ssr.history (last frame's blended SSR -- engine ping-pong swap)
uniform sampler2D u_Input2; // builtin.gMotion
uniform sampler2D u_Input3; // builtin.depth
uniform sampler2D u_Input4; // builtin.gMaterial -- r = smoothness -> blur radius

#define SSR_TEMPORAL_BLEND 0.85
#define SSR_DISOCCLUSION_DEPTH_THRESHOLD 0.05

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(u_Input0, 0));
    float smoothness = texture(u_Input4, texCoord).r;
    // Radius 0 (sharp mirror) at smoothness 1.0 -> radius 3 at the 0.1 trace floor.
    int radius = int(round((1.0 - smoothness) * 3.0));

    vec4 sum = vec4(0.0);
    float count = 0.0;
    for (int x = -radius; x <= radius; x++) {
        for (int y = -radius; y <= radius; y++) {
            sum += texture(u_Input0, texCoord + vec2(float(x), float(y)) * texelSize);
            count += 1.0;
        }
    }
    vec4 blurred = sum / max(count, 1.0);

    vec2 motion = texture(u_Input2, texCoord).rg;
    vec2 previousUv = texCoord - motion;
    bool validHistory = previousUv.x >= 0.0 && previousUv.x <= 1.0 && previousUv.y >= 0.0 && previousUv.y <= 1.0;
    if (validHistory) {
        float depthAtFragment = texture(u_Input3, texCoord).r;
        float depthAtReprojected = texture(u_Input3, previousUv).r;
        if (abs(depthAtFragment - depthAtReprojected) > SSR_DISOCCLUSION_DEPTH_THRESHOLD) validHistory = false;
    }
    fragColor = validHistory ? mix(blurred, texture(u_Input1, previousUv), SSR_TEMPORAL_BLEND) : blurred;
}
