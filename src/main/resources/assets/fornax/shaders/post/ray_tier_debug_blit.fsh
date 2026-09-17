#version 330

// Paints which tier answered each texel of the celestial ray result, so the cascade is visible
// instead of inferred from a log line. R is the answer, G the RayTier ordinal, A the validity.
//
// Read A first: an untraced or invalidated target is all zeros, and zero is a legal value for both
// R and G. A texel inside a tier's reach that it could not answer stays black here, which is the
// same thing the pack sees and the reason it falls back to raster there.

uniform sampler2D u_Source;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 answer = texture(u_Source, texCoord);
    if (answer.a <= 0.5) {
        // Nothing answered this texel. Raster owns it.
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    int tier = int(answer.g + 0.5);
    vec3 colour;
    if (tier == 3) {
        colour = vec3(0.2, 1.0, 0.3);       // exact chunk meshes
    } else if (tier == 2) {
        colour = vec3(0.25, 0.5, 1.0);      // hardware voxel boxes
    } else if (tier == 1) {
        colour = vec3(1.0, 0.75, 0.1);      // software voxel march
    } else {
        // Validity set with no tier: a provider wrote one word and not the other, which the
        // engine's own law forbids. Magenta so it cannot be mistaken for a real answer.
        colour = vec3(1.0, 0.0, 1.0);
    }
    // Shade by the depth the tier reported, so blockers stay readable inside each flat tier colour.
    fragColor = vec4(colour * mix(0.35, 1.0, clamp(answer.r, 0.0, 1.0)), 1.0);
}
