// Fornax terrain vertex format decode. Matches the attribute layout FornaxChunkVertex.java encodes
// on the Java side (see that class for the packing rationale). Fornax-owned format with a single
// consumer (fornax:blocks/terrain): fixed-point UNORM position, a direct normalized atlas UV, and a
// discrete 0-15 light index pair.
//
// Direction ordinals below match net.minecraft.core.Direction (DOWN=0, UP=1, NORTH=2, SOUTH=3,
// WEST=4, EAST=5) -- Minecraft's own enum ordering, not shader-side.
vec3 _vert_position;
vec2 _vert_tex_diffuse_coord;
vec2 _vert_tex_light_coord;
vec4 _vert_color;
uint _draw_id;
uint _material_params;
vec3 _vert_face_normal;

// The mesh builder emits vertex-local coordinates for a 16-block chunk section with some overhang
// for shared edge geometry between sections; this range (-8..+24 on each axis) is wide enough to
// cover that overhang while still fitting a 16-bit fixed-point channel with useful precision.
const float FORNAX_MODEL_MIN = -8.0;
const float FORNAX_MODEL_SIZE = 32.0;

const vec3 FORNAX_FACE_NORMALS[6] = vec3[](
    vec3(0.0, -1.0, 0.0),
    vec3(0.0,  1.0, 0.0),
    vec3(0.0,  0.0, -1.0),
    vec3(0.0,  0.0,  1.0),
    vec3(-1.0, 0.0, 0.0),
    vec3(1.0,  0.0, 0.0)
);

in vec4 a_Position;        // RGBA16_UNORM: xyz = normalized [0,1] position, w unused
in vec4 a_Color;           // RGBA8_UNORM: vertex color, already combined with baked ambient occlusion
in vec2 a_TexCoord;        // RG16_UNORM: normalized atlas UV, direct (no bias/shrink baked in here)
in uvec4 a_LightAndData;   // RGBA8_UINT: x=blockLight(0-15) y=skyLight(0-15) z=materialParams w=drawId
in uvec4 a_Normal;         // RGBA8_UINT: x=face index (0-5, see FORNAX_FACE_NORMALS), yzw reserved

void _vert_init() {
    _vert_position = a_Position.xyz * FORNAX_MODEL_SIZE + FORNAX_MODEL_MIN;
    _vert_color = a_Color;
    _vert_tex_diffuse_coord = a_TexCoord;

    // u_LightTex is Minecraft's 16x16 light map; sampling at a texel's center avoids picking up
    // its neighbor under bilinear filtering.
    _vert_tex_light_coord = (vec2(a_LightAndData.xy) + 0.5) / 16.0;

    _material_params = uint(a_LightAndData.z);
    _draw_id = uint(a_LightAndData.w);

    _vert_face_normal = FORNAX_FACE_NORMALS[a_Normal.x];
}
