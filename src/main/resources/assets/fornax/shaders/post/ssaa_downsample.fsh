#version 330

// Paired with vanilla's core/screenquad.vsh (gl_VertexID-driven full-screen triangle, no vertex
// buffer -- see SsaaDownsamplePass), the same technique gbuffer_resolve.fsh already reuses. Matches
// that pairing's plain "#version 330" (no "core" suffix), since screenquad.vsh itself declares no
// "core" profile.

uniform sampler2D u_Source;

layout(std140) uniform u_DownsampleSettings {
    int u_TapRadius; // linear scale factor rounded to the nearest int, e.g. 2 for the 4x preset
    vec2 u_SourceTexelSize; // 1.0 / scaledTarget dimensions
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 sum = vec4(0.0);
    int sampleCount = 0;

    // Box filter: average every source texel that maps into this output pixel's footprint.
    // u_TapRadius is the linear scale factor (e.g. 2 for a 4x pixel-count / 2x-per-dimension preset),
    // so the footprint is a TapRadius x TapRadius block of source texels per output pixel.
    for (int y = 0; y < u_TapRadius; y++) {
        for (int x = 0; x < u_TapRadius; x++) {
            vec2 offset = (vec2(x, y) - float(u_TapRadius - 1) * 0.5) * u_SourceTexelSize;
            sum += texture(u_Source, texCoord + offset);
            sampleCount++;
        }
    }

    fragColor = sum / float(sampleCount);
}
