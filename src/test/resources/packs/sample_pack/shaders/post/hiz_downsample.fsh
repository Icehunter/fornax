#version 330

// Mipchain pass (see MipchainRunner): bind group is just u_Input0 + u_PassParams -- no u_Globals, no
// u_PackOptions (Hi-Z generation has no tunables). u_Param2 carries the seed/reduce flag in place of
// the old hardcoded pass's dedicated u_HiZParams.u_SeedPass field.

layout(std140) uniform u_PassParams {
    vec2 u_PassTexelSize;
    float u_Param2; // 1.0 = seed (copy depth 1:1 into mip 0); 0.0 = 2x2 max-reduce from the previous level
    float u_Param3;
};

uniform sampler2D u_Input0; // seed: builtin.depth; reduce: level i-1 of this same mipchain

in vec2 texCoord;
out float fragColor;

void main() {
    if (u_Param2 > 0.5) {
        fragColor = texture(u_Input0, texCoord).r;
        return;
    }

    // 2x2 max-reduce. Reversed-Z: LARGER depth = CLOSER; the pyramid stores each tile's CLOSEST
    // surface so the trace can prove "this ray segment is in front of everything here" and skip.
    // This fragment (x,y) in the DESTINATION level covers source texels (2x,2y)..(2x+1,2y+1).
    //
    // Odd-dimension handling: Mojang sizes mip N as floor(srcDim / 2) (plain right-shift), so for
    // an odd source dimension the last source row/column would never be covered by any flat 2x2
    // footprint -- silently dropping closest-surface data and breaking the pyramid's conservative
    // guarantee. The last destination row/column therefore widens its footprint to 3 texels along
    // each odd axis, folding the orphaned edge texels into its max.
    ivec2 srcSize = textureSize(u_Input0, 0);
    ivec2 dstSize = srcSize >> 1; // matches Mojang's floor-based mip sizing
    ivec2 dst = ivec2(gl_FragCoord.xy);
    ivec2 srcBase = dst * 2;
    int extraX = ((srcSize.x & 1) == 1 && dst.x == dstSize.x - 1) ? 1 : 0;
    int extraY = ((srcSize.y & 1) == 1 && dst.y == dstSize.y - 1) ? 1 : 0;
    float closest = 0.0; // 0.0 = far plane (reversed-Z clear value), the identity for max()
    for (int dx = 0; dx <= 1 + extraX; dx++) {
        for (int dy = 0; dy <= 1 + extraY; dy++) {
            ivec2 src = min(srcBase + ivec2(dx, dy), srcSize - 1);
            closest = max(closest, texelFetch(u_Input0, src, 0).r);
        }
    }
    fragColor = closest;
}
