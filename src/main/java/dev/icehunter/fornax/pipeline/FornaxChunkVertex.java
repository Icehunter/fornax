package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.core.Direction;

/**
 * Fornax's own terrain vertex format, consumed only by {@code assets/fornax/shaders/blocks/
 * terrain.vsh}/{@code chunk_vertex.glsl}. No Sodium shader or Java class reads this format's byte
 * layout, so it can use its own packing scheme instead of mirroring {@code CompactChunkVertex}'s
 * bit-interleaved encoding.
 *
 * <p>Registered in place of {@code CompactChunkVertex} at the one construction site the official
 * jar has for it -- {@code ChunkMeshFormats}'s static initializer -- via {@code
 * CompactChunkVertexMixin}'s {@code @Redirect}. This works because {@code ChunkMeshFormats.COMPACT}
 * is declared as the {@code ChunkVertexType} interface type, and every real consumer of vertex
 * layout ({@code RenderRegion}, {@code RenderRegion$DeviceResources}, {@code ChunkBuildBuffers},
 * {@code ChunkMeshBufferBuilder}, {@code ShaderChunkRenderer}) resolves the concrete format
 * dynamically through {@link ChunkVertexType#getVertexFormat()}/{@link
 * ChunkVertexType#getEncoder()}. (An unread constant-pool reference to {@code CompactChunkVertex}
 * lingers in {@code UniformBufferManager} from a constant-folded expression, but no bytecode there
 * consumes it.)
 *
 * <p>Extends {@code CompactChunkVertex} only so the {@code @Redirect}'s replaced {@code NEW}/
 * {@code INVOKESPECIAL} pair keeps its original return type; none of the parent's fields, methods,
 * or packing logic are used here.
 *
 * <p><b>a_Position.w packs per-block facts into one 16-bit code</b> -- light emission 0..15 in bits
 * 0-3 and {@link BlockClasses} flags in bits 4-10 (see {@link #packBlockFacts}). Six of the seven
 * flag bits are spare. Bits 11-15 are reserved for the block-atlas PAGE INDEX -- see {@link
 * #PAGE_INDEX_BIT_OFFSET}. The lane briefly carried a per-quad UV-rectangle index for parallax,
 * which cost a concurrent map lookup per quad in this encoder -- the hottest loop in terrain
 * meshing -- and dropped the frame rate to single digits while chunks rebuilt. Sprite rectangles
 * are resolved from the atlas by position instead, so the lookup was pure overhead; the lane now
 * carries emission, read straight off {@link MaterialIdContext#getLightEmission()}, which the
 * block/fluid mesh mixins fill from vanilla's {@code getLightEmission()} -- one field read per
 * quad, not a lookup.
 *
 * <p>Chosen over the three spare bits in a_Normal.w (which holds a 0..2 precipitation type in a
 * whole byte): that byte is read by wetness, ripples, splashes and snow dusting, where a masking
 * slip is a weather bug rather than a lighting one. a_Position.w was already written every vertex,
 * so this adds no bandwidth and shares no bits with anything.
 *
 * <p>The lane is exact, not approximate: it is written as a raw UNORM16 code and the shader
 * recovers it with {@code uint(a_Position.w * 65535.0 + 0.5)}, so all 65536 bit patterns survive
 * unchanged and emission/flags unpack independently. The low nibble carries the level, exact
 * because 65535/15 = 4369 with no remainder over that 4-bit sub-range.
 *
 * <p>The lane is 16-bit UNORM because the format is RGBA16_UNORM and nothing narrower was
 * available for a fourth position component.
 *
 * <p><b>Encoding:</b> a_Position is 4x16-bit UNORM (fixed-point, normalized over a
 * {@value #MODEL_MIN}..{@value #MODEL_MIN}+{@value #MODEL_SIZE} cube -- wide enough for the mesh
 * builder's per-section overhang). a_Color is RGB = the vertex's biome tint (Sodium's own {@code
 * vertex.color}, unmultiplied -- white/identity for most untinted blocks) and A = vanilla's
 * per-face directional shade times AO (Sodium's own {@code vertex.ao}, a scalar broadcast to all
 * three colour channels -- see {@code ColorMixer.mul}). Keeping tint (RGB) and shade/AO (A)
 * separate rather than fusing them into one RGB product ({@code ColorARGB.mulRGB(vertex.color,
 * vertex.ao)}) before either reaches the shader matters because that fusion is irreversible (a
 * colour times a scalar cannot be un-multiplied from the product alone): decoding a fused value
 * either treats shade/AO as a picked colour (wrong: raises every linear shade/AO factor to the 2.4
 * power, the LabPBR-shine defect) or never decodes tint at all (wrong the other way: a saturated
 * dye/biome tint flattens toward grey, since {@code decode(tint*scalar) != decode(tint)*scalar} for
 * any scalar != 1).
 * Sodium itself never fuses them -- {@code vertex.color} (pure tint, from {@code
 * BlockRenderer.tintQuad}) and {@code vertex.ao} (shade x AO, from {@code
 * SmoothLightPipeline.applyAmbientLighting}) are separate fields on {@code
 * ChunkVertexEncoder.Vertex} -- so carrying them separately costs nothing: same 4 bytes, zero
 * extra bandwidth, and the alpha byte was otherwise wasted (a colour's own alpha is always opaque;
 * {@code mulRGB} preserved it unchanged and nothing read it). See {@code chunk_vertex.glsl}'s
 * {@code a_Color} declaration and {@code terrain.fsh}'s two consumers for the shader side --
 * anything that still treats {@code v_RawTint} as a fused product is wrong. a_TexCoord is a direct
 * 2x16-bit UNORM atlas UV (no centroid/bias encoding -- {@code terrain.fsh}'s own texel-snapping
 * sampler handles atlas-edge bleed at the fragment level instead). a_LightAndData packs a discrete
 * 0-15 block/sky light pair plus the material and draw-id bytes. a_Normal carries the flat face
 * index terrain.fsh needs for its deferred G-buffer normal output in byte x, plus the block's u16
 * blocks.toml material category ID in bytes y (low) / z (high) -- byte w reserved. The face normal
 * is derived from the quad's own vertex positions (a two-edge cross product -- any axis-aligned
 * block quad's 4 vertices are coplanar) and snapped to its dominant cardinal axis (see {@link
 * #deriveFaceIndex} for why the exact-match {@code ModelQuadFacing.fromNormal} doesn't work here),
 * since the official {@code ChunkVertexEncoder.Vertex} (only {@code x/y/z/color/ao/u/v/light})
 * carries no normal of its own.
 *
 * <p><b>a_Normal.w</b> is the biome precipitation type (0 none, 1 rain, 2 snow -- see {@link
 * MaterialIdContext}; it was a 0/1 flag until snow needed telling apart from rain, at no extra
 * cost since the lane was already a whole byte), and a_Normal.yz is the u16 material ID {@code
 * BlockRendererMaterialIdMixin} stashes into {@link MaterialIdContext} from the {@code BlockState}
 * being meshed (no {@code BlockState} reaches this encoder directly). A pack's {@code
 * chunk_vertex.glsl} decodes the same two bytes back into {@code _material_id} -- the byte order
 * (low byte first) must match on both sides.
 */
public class FornaxChunkVertex extends CompactChunkVertex {
    public static final int STRIDE = 24;

    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.RGBA16_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UNORM)
            .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
            .addAttribute("a_Normal", GpuFormat.RGBA8_UINT).build();

    private static final float MODEL_MIN = -8.0f;
    private static final float MODEL_SIZE = 32.0f;

    /**
     * Reserved bit slice within {@link #packBlockFacts}'s code for the block-atlas PAGE INDEX (M13),
     * bits 11-15 -- 5 bits, addressing up to {@link #MAX_ATLAS_PAGES} pages. CONSTANTS ONLY: {@link
     * #packBlockFacts} does not write into this slice yet, and no shader reads it -- a block's real
     * page always encodes as page 0 today, since nothing populates {@code
     * dev.icehunter.fornax.atlas.BlockAtlasPages}' cache yet (see that class's own doc). Declared now
     * so the eventual write has a fixed, tested home that cannot silently collide with {@link
     * BlockClasses} growing into it -- see {@code FornaxChunkVertexTest} for the non-overlap
     * assertion and {@code dev.icehunter.fornax.mixin.vanilla.SpriteLoaderPagedStitchMixin}, which
     * already feeds {@link #MAX_ATLAS_PAGES} into {@code BlockAtlasPageBudget.maxPages}'s
     * {@code hardCeiling} so its measurement never plans for more pages than this lane could ever
     * encode, even before the lane is written.
     */
    public static final int PAGE_INDEX_BIT_OFFSET = 11;

    /** Width of {@link #PAGE_INDEX_BIT_OFFSET}'s slice; the remaining bits above light emission
     * that {@link BlockClasses#WIDTH} does not claim. */
    public static final int PAGE_INDEX_BIT_WIDTH = 16 - PAGE_INDEX_BIT_OFFSET;

    /** How many atlas pages {@link #PAGE_INDEX_BIT_WIDTH} bits can address, {@code 1 << width}. */
    public static final int MAX_ATLAS_PAGES = 1 << PAGE_INDEX_BIT_WIDTH;

    private static final int POSITION_OFFSET = 0;   // 4 x 16-bit UNORM, 8 bytes
    private static final int TEXCOORD_OFFSET = 8;   // 2 x 16-bit UNORM, 4 bytes
    private static final int COLOR_OFFSET = 12;     // RGBA8_UNORM, 4 bytes
    private static final int LIGHT_DATA_OFFSET = 16; // RGBA8_UINT, 4 bytes
    private static final int NORMAL_OFFSET = 20;     // RGBA8_UINT, 4 bytes

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, materialBits, vertices, section) -> {
            int faceIndex = deriveFaceIndex(vertices);
            int materialId = MaterialIdContext.get();
            int precipitationType = MaterialIdContext.getPrecipitation();
            int blockFacts = packBlockFacts(MaterialIdContext.getLightEmission(),
                                            MaterialIdContext.getBlockClass());

            for (int i = 0; i < 4; i++) {
                var vertex = vertices[i];

                writeUnorm16(ptr + POSITION_OFFSET + 0, normalizeAxis(vertex.x));
                writeUnorm16(ptr + POSITION_OFFSET + 2, normalizeAxis(vertex.y));
                writeUnorm16(ptr + POSITION_OFFSET + 4, normalizeAxis(vertex.z));
                // The block's own facts, packed. See packBlockFacts and the class doc for the layout,
                // for why this lane rather than a_Normal.w's spare bits, and for why the write is a
                // raw code rather than a float.
                writeUnorm16Code(ptr + POSITION_OFFSET + 6, blockFacts);

                writeUnorm16(ptr + TEXCOORD_OFFSET + 0, vertex.u);
                writeUnorm16(ptr + TEXCOORD_OFFSET + 2, vertex.v);

                // RGB = vertex.color UNMULTIPLIED (Sodium's own pure biome tint -- BlockRenderer
                // .tintQuad, never touched by shade or AO). A = vertex.ao (Sodium's own shade x AO
                // scalar -- SmoothLightPipeline.applyAmbientLighting/FlatLightPipeline.getShade),
                // packed via the SAME ColorARGB utility the old fused write used, so this changes
                // WHAT is written, not the byte layout the shader already decodes correctly. See the
                // class doc above for why this split is exact and free rather than approximate.
                MemoryIntrinsics.putInt(ptr + COLOR_OFFSET,
                        ColorARGB.withAlpha(vertex.color, ColorU8.normalizedFloatToByte(vertex.ao)));

                int blockLight = lightLevel(vertex.light, 0);
                int skyLight = lightLevel(vertex.light, 16);
                int lightAndData = blockLight | (skyLight << 8) | ((materialBits & 0xFF) << 16) | ((section & 0xFF) << 24);
                MemoryIntrinsics.putInt(ptr + LIGHT_DATA_OFFSET, lightAndData);

                // a_Normal.w carries the biome precipitation TYPE (0 none, 1 rain, 2 snow) -- the byte
                // this format has always reserved, which is why widening the lane from a boolean cost
                // no bandwidth and no format change. See MaterialIdContext.setPrecipitation for why
                // it is per-block and why the type (not just "precipitates") is the thing shaders
                // need. The mask stays 0xFF and is load-bearing: it is what keeps the type inside its
                // own byte and out of the material id in yz.
                MemoryIntrinsics.putInt(ptr + NORMAL_OFFSET,
                        (faceIndex & 0xFF) | (materialId << 8) | ((precipitationType & 0xFF) << 24));

                ptr += STRIDE;
            }

            return ptr;
        };
    }

    /**
     * A quad's four vertices are coplanar for axis-aligned block geometry, so the face normal is
     * recovered from two of its edges rather than needing a per-vertex normal field.
     *
     * <p>Classified by the cross product's dominant axis, NOT via {@code
     * ModelQuadFacing.fromNormal}: that method requires the input to equal a unit step vector
     * component-for-component ({@code Mth.equal}, ~1e-5 epsilon), but an edge cross product's
     * magnitude is twice the quad's area -- exactly 1.0 only for full 1x1 block faces. Any
     * partial-extent quad (slab and snow-layer sides, farmland, stairs, torches) falls through
     * to UNASSIGNED there and would take the UP fallback, stamping an upward normal into the
     * G-buffer for whole classes of faces and producing visibly inconsistent per-face
     * lighting/reflections. Dominant-axis selection gives the quad's light-face direction for
     * any axis-aligned quad regardless of area, with no normalization needed. Y is tested first
     * so a degenerate (zero-area) quad still lands on the same UP fallback as before.
     */
    private static int deriveFaceIndex(ChunkVertexEncoder.Vertex[] vertices) {
        var v0 = vertices[0];
        var v1 = vertices[1];
        var v2 = vertices[2];

        float ex1 = v1.x - v0.x, ey1 = v1.y - v0.y, ez1 = v1.z - v0.z;
        float ex2 = v2.x - v0.x, ey2 = v2.y - v0.y, ez2 = v2.z - v0.z;

        float nx = (ey1 * ez2) - (ez1 * ey2);
        float ny = (ez1 * ex2) - (ex1 * ez2);
        float nz = (ex1 * ey2) - (ey1 * ex2);

        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) {
            return ny >= 0.0f ? Direction.UP.ordinal() : Direction.DOWN.ordinal();
        }
        if (ax >= az) {
            return nx >= 0.0f ? Direction.EAST.ordinal() : Direction.WEST.ordinal();
        }
        return nz >= 0.0f ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
    }

    /**
     * Extracts a 0-15 light level from Minecraft's packed per-vertex light word, where each
     * channel is pre-scaled by 16 into its own byte (block light in bits 0-7, sky light in bits
     * 16-23) -- shifting right by 4 after masking the byte recovers the plain level.
     */
    private static int lightLevel(int packedLight, int byteShift) {
        return ((packedLight >>> byteShift) & 0xFF) >>> 4;
    }

    /**
     * Packs the per-BLOCK facts that ride {@code a_Position.w} into one 16-bit code.
     *
     * <pre>
     *   bits 0-3   vanilla's Block.getLightEmission() level, 0..15
     *   bits 4-10  BlockClasses flags, 7 bits, of which COAL is bit 4 and six are SPARE
     *   bits 11-15 RESERVED for the block-atlas page index (see PAGE_INDEX_BIT_OFFSET) -- UNWRITTEN
     *              today, always reads as 0
     * </pre>
     *
     * <p><b>Emission stays in the low nibble and keeps its exact meaning.</b> It occupied this whole
     * channel as {@code level/15.0} before the class flags joined it, and the shader still recovers
     * the identical 0..15 integer -- the change is that the recovery goes through the code rather
     * than through the normalized float, so the two facts can share the lane without either one
     * being approximate.
     *
     * <p><b>Why one packed code and not two channels.</b> There is no second channel. a_Position is
     * the format's only 16-bit-per-component attribute and its fourth component is the only spare
     * one; a_Normal.w is a live byte carrying the precipitation type and read by wetness, ripples,
     * splashes and snow dusting, where a masking slip is a weather bug rather than a lighting one.
     * Sixteen bits held four bits of emission and 65520 unused codes, so the flags cost no
     * bandwidth, no extra attribute and no format change.
     */
    static int packBlockFacts(int lightEmission, int blockClassFlags) {
        return (lightEmission & 0xF) | ((blockClassFlags & BlockClasses.MASK) << 4);
    }

    /**
     * Writes an exact UNORM16 CODE, bypassing the float round trip {@link #writeUnorm16} performs.
     *
     * <p>Used for the packed block-facts lane, where the channel carries a bit field rather than a
     * quantity: {@code code / 65535.0f} then {@code Math.round(x * 65535.0f)} does round-trip every
     * code exactly in binary32, but nothing about that is obvious at a glance, and a lane whose
     * correctness is a floating-point argument is a lane that will eventually be re-derived wrongly.
     * Writing the short directly makes the exactness structural instead. The shader's decode is the
     * mirror image -- {@code uint(a_Position.w * 65535.0 + 0.5)}.
     */
    private static void writeUnorm16Code(long address, int code) {
        MemoryIntrinsics.putShort(address, (short) (code & 0xFFFF));
    }

    private static float normalizeAxis(float v) {
        return (v - MODEL_MIN) / MODEL_SIZE;
    }

    private static void writeUnorm16(long address, float value) {
        float clamped = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
        int quantized = Math.round(clamped * 65535.0f);
        MemoryIntrinsics.putShort(address, (short) quantized);
    }
}
