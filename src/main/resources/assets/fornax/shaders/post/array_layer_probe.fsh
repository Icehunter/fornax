#version 330

// Sampling half of ArrayTextureLayerProbe (phase-3 de-risk tool for the paged block atlas's future
// GPU array-texture holders -- see that class's own doc comment). u_Source is a real 2-layer
// GpuTexture bound through ArrayTextures' hand-built VK_IMAGE_VIEW_TYPE_2D_ARRAY view spanning all
// layers (a stock Blaze3D view is single-layer 2D and could never reach layer 1 -- see ArrayTextures'
// doc for the receipts) -- so this is exactly how a future atlas holder will read its own array
// texture too: one full-array sampler2DArray binding, with the layer picked per-sample via the
// array-index texture coordinate rather than anything on the Java side. Paired with vanilla's
// core/screenquad.vsh, same shape as every other screenquad blit in this codebase (e.g.
// ssaa_downsample.fsh).
uniform sampler2DArray u_Source;

layout(std140) uniform u_LayerSelect {
    int u_Layer;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = texture(u_Source, vec3(texCoord, float(u_Layer)));
}
