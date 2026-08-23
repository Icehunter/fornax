#version 330

// Presentation half of the engine reconstruct: reads the UNSHARPENED temporal accumulation that
// reconstruct.fsh just wrote into sceneHistory's write slot and sharpens it into the presented
// native target. Deliberately OUTSIDE the temporal feedback loop -- sharpening inside it (the
// first-revision layout, where one sharpened output was both presented and copied into
// sceneHistory) re-applies edge enhancement to its own output every frame at the blend's ~0.9
// recycle rate, which diverges into saturated speckle on high-frequency content (live-caught as
// red/rainbow webs on distant foliage).
//
// The adaptivity is LUMA-driven: one scalar weight for all three channels. Per-channel weights
// (the other first-revision bug) let a flat channel sharpen hard while a busy one doesn't --
// chromatic ringing exactly where channels disagree, e.g. red speckle on green foliage.
uniform sampler2D u_Source; // native unsharpened accumulation (sceneHistory write slot, this frame)

layout(std140) uniform u_ReconstructSettings {
    vec2  u_SourceTexelSize;
    vec2  u_OutputTexelSize;
    vec2  u_JitterOffsetNdc;
    float u_BlendFactor;
    float u_Sharpen;
    float u_RatioIsOne;
};

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 c = texture(u_Source, texCoord).rgb;

    // Ratio-scaled floor: temporal upscaling reconstructs softer than native rendering, so below
    // ratio 1.0 the effective strength never drops under the upscale deficit (1 - ratio: 0.23/
    // 0.33/0.42 for the Quality/Balanced/Performance TAAU tiers). The deficit is 0 at ratio 1.0,
    // so TAA honors the setting exactly -- and at 0.0 this pass degenerates to a pure copy.
    float sharpen = max(u_Sharpen, 1.0 - u_OutputTexelSize.x / u_SourceTexelSize.x);
    if (sharpen <= 0.0) {
        fragColor = vec4(c, 1.0);
        return;
    }

    // The accumulation is native-res regardless of render scale (sceneHistory is OUTPUT-basis),
    // so taps step by the OUTPUT texel size; the render-res u_SourceTexelSize would overreach
    // past the immediate neighbors under TAAU.
    vec3 n = texture(u_Source, texCoord + vec2(0.0,  u_OutputTexelSize.y)).rgb;
    vec3 s = texture(u_Source, texCoord + vec2(0.0, -u_OutputTexelSize.y)).rgb;
    vec3 e = texture(u_Source, texCoord + vec2( u_OutputTexelSize.x, 0.0)).rgb;
    vec3 w = texture(u_Source, texCoord + vec2(-u_OutputTexelSize.x, 0.0)).rgb;

    float lc = luma(c);
    float lmn = min(min(min(luma(n), luma(s)), min(luma(e), luma(w))), lc);
    float lmx = max(max(max(luma(n), luma(s)), max(luma(e), luma(w))), lc);
    float amt = sharpen * (1.0 - (lmx - lmn));            // contrast-adaptive: sharpen low-contrast areas more
    vec3 sharpened = clamp(c + amt * (c * 4.0 - (n + s + e + w)) * 0.125, 0.0, 1.0);

    // Alpha returns to 1.0 here: the accumulation age stays private to sceneHistory, and the
    // presented framebuffer keeps vanilla's opaque-alpha expectations downstream (GUI, blit).
    fragColor = vec4(sharpened, 1.0);
}
