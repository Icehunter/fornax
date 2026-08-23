#version 330

// Presentation blit for the engine-owned M1 DDA sun-shadow prototype debug view.
// CelestialShadowVoxelDebugPass samples the pack's own celestialVisVoxel graph target (r8: 1.0 = lit,
// 0.0 = occluded) directly and shows it as grayscale over the native frame, overriding whatever the
// pack's own resolve pass presented -- mirrors water_prepass_debug_blit.fsh's single-sampler shape.
uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float visibility = texture(u_Source, texCoord).r;
    fragColor = vec4(vec3(visibility), 1.0);
}
