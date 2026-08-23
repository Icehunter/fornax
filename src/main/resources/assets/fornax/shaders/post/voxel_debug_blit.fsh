#version 330

// Presentation blit for the engine-owned voxel debug raymarch (Task 12). The compute pass writes its
// RGBA8 result into a plain engine texture (via CommandEncoder.writeToTexture); this trivial pass
// samples that texture across a full-screen triangle and writes it straight into the native frame,
// overriding whatever the pack's own resolve pass presented. Alpha is forced to 1.0 so a "miss"
// (sky) pixel is opaque, never blended against the underlying frame. Mirrors ssaa_downsample.fsh's
// screenquad + single-sampler shape; texCoord (0,0) samples the source's top-left, matching the
// compute pass's row-0-is-top pixel layout, so no vertical flip is needed here.
uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(u_Source, texCoord).rgb, 1.0);
}
