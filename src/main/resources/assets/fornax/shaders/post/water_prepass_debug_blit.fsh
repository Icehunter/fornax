#version 330

// Presentation blit for the engine-owned water pre-pass debug view (Deferred Water Task 1 spike).
// WaterPrepassDebugPass samples WaterSurfaceManager's own waterNormal texture (RGBA16_SNORM: xyz =
// signed surface world-normal, a = water-present flag) directly -- no compute pass, unlike
// voxel_debug_blit.fsh's compute-result presentation -- and remaps it into the native frame,
// overriding whatever the pack's own resolve pass presented. xyz is remapped from signed [-1,1] to
// displayable [0,1] (matching gNormalOut's own display convention); alpha is forced to 1.0 so a
// "no water here" pixel (cleared to zero, remaps to mid-gray) is still opaque, never blended against
// the underlying frame. Mirrors voxel_debug_blit.fsh's screenquad + single-sampler shape.
uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 signedNormal = texture(u_Source, texCoord).xyz;
    fragColor = vec4(signedNormal * 0.5 + 0.5, 1.0);
}
