// Fornax terrain vertex format decode. Matches the attribute layout FornaxChunkVertex.java encodes
// on the Java side (see that class for the packing rationale). Fornax-owned format with a single
// consumer (fornax:blocks/terrain): fixed-point UNORM position, a direct normalized atlas UV, and a
// discrete 0-15 light index pair.
//
// Direction ordinals below match net.minecraft.core.Direction (DOWN=0, UP=1, NORTH=2, SOUTH=3,
// WEST=4, EAST=5) -- Minecraft's own enum ordering, not shader-side.
vec3 _vert_position;
float _vert_light_emission;
uint _vert_block_class;
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

// One class flag is defined so far; FORNAX_BLOCK_CLASS_COAL is bit 0 of the flag field, i.e. bit 4
// of the packed code. The flag field is 7 bits wide (code bits 4-10, six spare); code bits 11-15
// are reserved for the paged-atlas page index -- see BlockClasses.java and FornaxChunkVertex's
// PAGE_INDEX constants. Nothing writes the page bits yet, so they read zero.
const uint FORNAX_BLOCK_CLASS_COAL = 1u;

in vec4 a_Position;        // RGBA16_UNORM: xyz = normalized [0,1] position, w = a packed 16-bit
                           // code of this BLOCK's own facts: Block.getLightEmission() level 0-15 in
                           // bits 0-3, BlockClasses flags in bits 4-10, atlas page index reserved
                           // in bits 11-15 (always zero until the paged atlas goes live)
in vec4 a_Color;           // RGBA8_UNORM: vertex color, already combined with baked ambient occlusion
in vec2 a_TexCoord;        // RG16_UNORM: normalized atlas UV, direct (no bias/shrink baked in here)
in uvec4 a_LightAndData;   // RGBA8_UINT: x=blockLight(0-15) y=skyLight(0-15) z=materialParams w=drawId
in uvec4 a_Normal;         // RGBA8_UINT: x=face index (0-5, see FORNAX_FACE_NORMALS),
                           // yz = u16 blocks.toml material id (low byte first),
                           // w = biome precipitation type (0 none, 1 rain, 2 snow)

void _vert_init() {
    _vert_position = a_Position.xyz * FORNAX_MODEL_SIZE + FORNAX_MODEL_MIN;
    // THE BLOCK'S OWN FACTS, unpacked from one 16-bit code. a_Position.w is a UNORM16 channel, so
    // the value arrives as code/65535 and the +0.5 recovers the integer code exactly; the encoder
    // writes that code as a raw short for the same reason (FornaxChunkVertex.writeUnorm16Code).
    //
    // The lane previously carried a per-quad sprite index and then, for a long while, a written
    // constant 1.0 -- the index cost a concurrent map lookup in the hottest loop in terrain meshing
    // and was removed. See FornaxChunkVertex for why these facts live here and not in a_Normal.w's
    // spare bits.
    uint blockFacts = uint(a_Position.w * 65535.0 + 0.5);

    // How much light THIS BLOCK emits: vanilla's own Block.getLightEmission() level 0-15, mapped
    // onto 0..1. Glowstone and lava are 1.0, a torch 14/15, plain stone 0.
    // A DIVISION, deliberately: correctly-rounded division makes float(level)/15.0
    // bit-identical to the fl(code/65535) this lane used to arrive as, while
    // level * fl(1/15) is one ulp off on six of the sixteen levels (3,6,7,12,13,14 --
    // measured), and a torch is level 14.
    _vert_light_emission = float(blockFacts & 15u) / 15.0;

    // WHICH VANILLA CATEGORIES this block belongs to, as engine-resolved flags. Today only COAL is
    // defined, from Minecraft's own #minecraft:coal_ores block tag.
    //
    // A CATEGORY, NOT A MATERIAL. This says what the game calls the block and nothing about how it
    // should look -- no smoothness, no emission, no colour. That is what separates it from a
    // shaderpack's per-block-id table: there is nothing here for anyone to author, a modded coal
    // ore that declares the tag arrives for free, and a pack is free to ignore the flag entirely.
    //
    // MASKED to the class field's own seven bits: bits 11-15 above it are the reserved page-index
    // slice (FornaxChunkVertex.PAGE_INDEX_BIT_OFFSET), and an unmasked shift would fold whatever
    // ever lands there into the class flags.
    _vert_block_class = (blockFacts >> 4u) & 0x7Fu;
    _vert_color = a_Color;
    _vert_tex_diffuse_coord = a_TexCoord;

    // u_LightTex is Minecraft's 16x16 light map; sampling at a texel's center avoids picking up
    // its neighbor under bilinear filtering.
    _vert_tex_light_coord = (vec2(a_LightAndData.xy) + 0.5) / 16.0;

    _material_params = uint(a_LightAndData.z);
    _draw_id = uint(a_LightAndData.w);

    _vert_face_normal = FORNAX_FACE_NORMALS[a_Normal.x];
}
