package dev.icehunter.fornax.voxel;

import net.minecraft.client.Minecraft;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

/** Holds exact per-quad UV mapping plus a separate opaque-face-coverage flag.
 * This buffer sits beside the 16-word palette layout and does not change it. */
public final class VoxelFaceTexture {
    public static final String TARGET = "voxelFaceTexture";
    /** Header bit 26: the second flag bit in the top byte, separate from the usable-UV and
     * alpha-test bits. */
    public static final int OPAQUE_COVERAGE = 1 << 26;
    // Header bits 27..28 carry the atlas's existing 1-based static overflow page (0 is base).
    public static final int ATLAS_PAGE_SHIFT = 27;
    // One header word for RGB and flags, then six raw float words for UV. Tint alpha is not read.
    public static final int FACE_WORDS = 7;
    public static final int ENTRY_WORDS = 6 * FACE_WORDS;
    public static final int WORDS_PER_SLOT = SectionHarvester.MAX_PALETTE_ENTRIES * ENTRY_WORDS;
    public static final int BYTES_PER_SLOT = WORDS_PER_SLOT * Integer.BYTES;
    private VoxelFaceTexture() { }

    public record SourceFaces(int[] textureWords, List<MaterialSourceIndex.Summary> summaries,
                              long atlasGeneration, boolean materialsKnownNonpositive) {
        public SourceFaces {
            if (textureWords.length != ENTRY_WORDS || summaries.size() != Direction.values().length)
                throw new IllegalArgumentException("source faces require 42 texture words and six summaries");
            textureWords = textureWords.clone();
            summaries = List.copyOf(summaries);
        }
        @Override public int[] textureWords() { return this.textureWords.clone(); }
    }

    static SourceFaces resolveSources(BlockState state, VoxelShapeKind kind, int tint) {
        return resolveSources(state, kind, tint, MaterialSourceIndex.current());
    }

    static SourceFaces resolveSources(BlockState state, VoxelShapeKind kind, int tint,
                                      MaterialSourceIndex index) {
        // A fluid block has no model quad to read, so its own sprites answer instead.
        var fluid = VoxelFluidFace.resolve(state);
        if (fluid != null) {
            return VoxelFluidFace.packSources(fluid, tint, index);
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0L), parts); // Match the existing palette model variant.
        return packSources(parts, kind, tint, index);
    }

    /** Whole-sprite source evidence is only usable for an exact single full-sprite face.
     * A crop, stacked face or partial shape retains candidate evidence but is explicitly unknown. */
    static SourceFaces packSources(List<BlockStateModelPart> parts, VoxelShapeKind kind, int tint,
                                   MaterialSourceIndex index) {
        int[] words = kind == VoxelShapeKind.FULL ? pack(parts, tint) : new int[ENTRY_WORDS];
        List<MaterialSourceIndex.Summary> summaries = new ArrayList<>();
        boolean knownNonpositive = true, hasQuad = false;
        for (Direction face : Direction.values()) {
            List<BakedQuad> candidates = new ArrayList<>();
            for (var part : parts) {
                candidates.addAll(part.getQuads(face));
                for (var quad : part.getQuads(null)) {
                    if (quad.direction() == face) candidates.add(quad);
                }
            }
            MaterialSourceIndex.Summary summary = null;
            int combinedFlags = 0;
            for (var quad : candidates) {
                var sprite = quad.materialInfo().sprite();
                var candidate = index.lookup(sprite);
                hasQuad = true;
                // Whether a source is missing has nothing to do with face geometry: check every
                // real quad before applying any crop/shape/page limit. Missing material data is
                // never proof of zero.
                knownNonpositive &= candidate.flags() == MaterialSourceIndex.MISSING_MAP
                        || (candidate.supported() && candidate.texelCount() > 0 && !candidate.authoredCandidate());
                // A static ghost carries its full-resolution page through the face header. An
                // animated ghost has no full-copy page, so it cannot claim static source coverage.
                if (sprite instanceof dev.icehunter.fornax.atlas.BlockAtlasGhostSprite ghost
                        && !ghost.hasOverflowCopy())
                    candidate = candidate.withFlags(MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE);
                combinedFlags |= candidate.flags();
                // Unsupported stacked faces retain one positive source's raw evidence, not a
                // fabricated average or a source-texel count represented as visible coverage.
                if (summary == null || (!summary.authoredCandidate() && candidate.authoredCandidate()))
                    summary = candidate;
            }
            if (summary == null) summary = MaterialSourceIndex.unavailable(MaterialSourceIndex.UNSUPPORTED_GEOMETRY);
            if (kind != VoxelShapeKind.FULL || candidates.size() != 1
                    || (words[face.get3DDataValue() * FACE_WORDS] >>> 24 & 1) == 0) {
                combinedFlags |= MaterialSourceIndex.UNSUPPORTED_GEOMETRY;
            } else if (!coversWholeSprite(candidates.getFirst())) {
                combinedFlags |= MaterialSourceIndex.CROPPED_UV;
            }
            summaries.add(summary.withFlags(combinedFlags));
        }
        return new SourceFaces(words, summaries, index.generation(), hasQuad && knownNonpositive);
    }

    private static boolean coversWholeSprite(BakedQuad quad) {
        var sprite = quad.materialInfo().sprite();
        if (sprite == null || sprite.getU0() >= sprite.getU1() || sprite.getV0() >= sprite.getV1()) return false;
        int corners = 0;
        for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            float u = UVPair.unpackU(quad.packedUV(i)), v = UVPair.unpackV(quad.packedUV(i));
            if ((u != sprite.getU0() && u != sprite.getU1()) || (v != sprite.getV0() && v != sprite.getV1())) return false;
            int corner = (u == sprite.getU1() ? 1 : 0) | (v == sprite.getV1() ? 2 : 0);
            corners |= 1 << corner;
        }
        // Four distinct rectangular sprite corners, independent of UV rotation or reflection.
        return corners == 0b1111;
    }

    static int[] resolve(BlockState state, VoxelShapeKind kind, int tint) {
        var fluid = VoxelFluidFace.resolve(state);
        if (fluid != null) return VoxelFluidFace.words(fluid, tint);
        if (kind != VoxelShapeKind.FULL) return new int[ENTRY_WORDS];
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0L), parts); // Same fixed model variant the palette colours use.
        return pack(parts, tint);
    }

    static int[] pack(List<BlockStateModelPart> parts, int tint) {
        int[] words = new int[ENTRY_WORDS];
        for (Direction face : Direction.values()) {
            List<BakedQuad> candidates = new ArrayList<>();
            for (var part : parts) {
                candidates.addAll(part.getQuads(face));
                for (var quad : part.getQuads(null)) {
                    if (quad.direction() == face) candidates.add(quad);
                }
            }
            // Stacked quads cannot be described by one straight UV map.
            if (candidates.size() == 1) {
                System.arraycopy(mapping(candidates.getFirst(), face, tint), 0, words,
                        face.get3DDataValue() * FACE_WORDS, FACE_WORDS);
            }
            for (var quad : candidates) {
                if (VoxelFaceOpacity.covers(quad, face)) {
                    words[face.get3DDataValue() * FACE_WORDS] |= OPAQUE_COVERAGE;
                    break;
                }
            }
        }
        return words;
    }

    /** Layout: RGB tint in header bits 0..23, flags (valid=1, alpha-tested=2) in bits 24..31, then
     * the raw floats u0, v0, du/ds, dv/ds, du/dt, dv/dt. Readers must use this seven-word layout.
     * pack() also sets flag 4 for opaque-face coverage on its own, even with no usable UV map.
     * Header bits 27..28 hold a static overflow page; UVs stay in base/ghost space.
     * Tint alpha is dropped; alpha testing uses the atlas's own alpha instead.
     * Local st uses (y,z) for X faces, (x,z) for Y faces and (x,y) for Z faces. */
    static int[] mapping(BakedQuad quad, Direction face, int tint) {
        int[] words = new int[FACE_WORDS];
        var layer = quad.materialInfo().layer();
        // Solid and alpha-tested faces only. Nothing about light getting through.
        if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT) return words;
        if (quad.materialInfo().tintIndex() > 0) return words; // Only layer-zero tint is harvested.
        float[][] uv = new float[4][];
        int axis = switch(face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
        for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            var p = quad.position(i);
            float normal = axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
            float s = axis == 0 ? p.y() : p.x();
            float t = axis == 2 ? p.y() : p.z();
            if (normal != plane || (s != 0f && s != 1f) || (t != 0f && t != 1f)) return words;
            int corner = (int)s + 2*(int)t;
            if (uv[corner] != null) return words;
            float u = UVPair.unpackU(quad.packedUV(i)), v = UVPair.unpackV(quad.packedUV(i));
            if (!Float.isFinite(u) || !Float.isFinite(v)) return words;
            uv[corner] = new float[]{u,v};
        }
        for (int i = 0; i < 4; i++) if (uv[i] == null) return words;
        for (int c = 0; c < 2; c++) {
            float expected = uv[1][c] + uv[2][c] - uv[0][c];
            // Rounding only: four ulps cover the three float sums above.
            if (Math.abs(expected - uv[3][c]) > 4 * Math.ulp(Math.max(Math.abs(expected), Math.abs(uv[3][c])))) return words;
        }
        int flags = 1 | (quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT ? 2 : 0);
        int rgb = quad.materialInfo().tintIndex() == 0 ? tint : -1;
        // RGB takes three bytes; the top byte holds flags and the static atlas page.
        words[0] = (flags << 24) | (rgb & 0x00ffffff);
        if (quad.materialInfo().sprite() instanceof dev.icehunter.fornax.atlas.BlockAtlasGhostSprite ghost
                && ghost.hasOverflowCopy()) {
            words[0] |= ghost.overflowPage() << ATLAS_PAGE_SHIFT;
        }
        words[1] = Float.floatToRawIntBits(uv[0][0]); words[2] = Float.floatToRawIntBits(uv[0][1]);
        words[3] = Float.floatToRawIntBits(uv[1][0]-uv[0][0]); words[4] = Float.floatToRawIntBits(uv[1][1]-uv[0][1]);
        words[5] = Float.floatToRawIntBits(uv[2][0]-uv[0][0]); words[6] = Float.floatToRawIntBits(uv[2][1]-uv[0][1]);
        return words;
    }
}
