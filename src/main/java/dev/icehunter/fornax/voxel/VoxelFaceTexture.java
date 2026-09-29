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
import org.joml.Vector3f;
import java.util.List;

/** Holds exact per-quad UV mapping plus a separate opaque-face-coverage flag.
 * This buffer sits beside the 16-word palette layout and does not change it. */
public final class VoxelFaceTexture {
    public static final String TARGET = "voxelFaceTexture";
    /** Header bit 26: the second flag bit in the top byte, separate from the usable-UV and
     * alpha-test bits. */
    public static final int OPAQUE_COVERAGE = 1 << 26;
    /** Header bit 29: an affine mapping for explicit model-boundary consumers. It does not
     * set the legacy opaque/cutout-valid bit, so existing visibility consumers keep their policy. */
    public static final int BOUNDARY_MAPPING = 1 << 29;
    /** Bit 25 with both mapping bits (24 and 29) clear selects material-sample words:
     * center atlas UV, rectangle area in block squares, then three zero words. It is not a map. */
    public static final int MATERIAL_SAMPLE = 1 << 25;
    public static final int MATERIAL_SAMPLE_MASK = (1 << 24) | MATERIAL_SAMPLE | BOUNDARY_MAPPING;
    /** Header bit 30: the complete model boundary matches the palette's union of boxes.
     * All six headers carry this geometry fact, independently of UV validity and light blocking. */
    public static final int CLOSED_BOX_BOUNDARY = 1 << 30;
    /** Header bit 31: this model direction contains a translucent-layer quad, even without UVs. */
    public static final int TRANSLUCENT_FACE = 1 << 31;
    // Header bits 27..28 carry the atlas's existing 1-based static overflow page (0 is base).
    public static final int ATLAS_PAGE_SHIFT = 27;
    // One header word for RGB and flags, then six raw float words for UV. Tint alpha is not read.
    public static final int FACE_WORDS = 7;
    public static final int ENTRY_WORDS = 6 * FACE_WORDS;
    public static int wordsPerSlot() { return SectionHarvester.maxPaletteEntries() * ENTRY_WORDS; }
    public static int bytesPerSlot() { return wordsPerSlot() * Integer.BYTES; }
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
        int[] words = kind == VoxelShapeKind.FULL ? pack(parts, tint)
                : kind == VoxelShapeKind.PARTIAL ? packPartial(parts, tint)
                : kind == VoxelShapeKind.CROSS ? packCross(parts, tint) : new int[ENTRY_WORDS];
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
            for (var quad : candidates) {
                var candidate = index.lookup(quad.materialInfo().sprite());
                hasQuad = true;
                knownNonpositive &= knownNonpositive(candidate);
            }
            summaries.add(sourceSummary(candidates, kind, words[face.get3DDataValue() * FACE_WORDS], index));
        }
        return new SourceFaces(words, summaries, index.generation(), hasQuad && knownNonpositive);
    }

    /** Material evidence comes from the emitted sprite, with the same whole-face limits as UVs. */
    static MaterialSourceIndex.Summary sourceSummary(List<BakedQuad> candidates, VoxelShapeKind kind,
                                                     int header, MaterialSourceIndex index) {
        MaterialSourceIndex.Summary summary = null;
        int combinedFlags = 0;
        for (var quad : candidates) {
            var sprite = quad.materialInfo().sprite();
            var candidate = index.lookup(sprite);
            if (sprite instanceof dev.icehunter.fornax.atlas.BlockAtlasGhostSprite ghost
                    && !ghost.hasOverflowCopy())
                candidate = candidate.withFlags(MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE);
            combinedFlags |= candidate.flags();
            // Preserve positive raw evidence for diagnostics, even when composition is unsupported.
            if (summary == null || (!summary.authoredCandidate() && candidate.authoredCandidate()))
                summary = candidate;
        }
        if (summary == null)
            summary = MaterialSourceIndex.unavailable(MaterialSourceIndex.UNSUPPORTED_GEOMETRY);
        if (kind != VoxelShapeKind.FULL || candidates.size() != 1
                || candidates.getFirst().materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT
                || (header >>> 24 & 1) == 0) {
            combinedFlags |= MaterialSourceIndex.UNSUPPORTED_GEOMETRY;
        } else if (!coversWholeSprite(candidates.getFirst())) {
            combinedFlags |= MaterialSourceIndex.CROPPED_UV;
        }
        return summary.withFlags(combinedFlags);
    }

    static boolean knownNonpositive(MaterialSourceIndex.Summary candidate) {
        return candidate.flags() == MaterialSourceIndex.MISSING_MAP
                || (candidate.supported() && candidate.texelCount() > 0 && !candidate.authoredCandidate());
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
        if (kind != VoxelShapeKind.FULL && kind != VoxelShapeKind.PARTIAL && kind != VoxelShapeKind.CROSS)
            return new int[ENTRY_WORDS];
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0L), parts); // Same fixed model variant the palette colours use.
        return kind == VoxelShapeKind.FULL ? pack(parts, tint)
                : kind == VoxelShapeKind.CROSS ? packCross(parts, tint) : packPartial(parts, tint);
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
            // A separate exterior layer does not change the exact opaque backing's UVs.
            // Coplanar layers remain unavailable: one map cannot represent their composition.
            BakedQuad mapped = mappedFace(candidates, face);
            if (mapped != null) {
                System.arraycopy(mapping(mapped, face, tint), 0, words,
                        face.get3DDataValue() * FACE_WORDS, FACE_WORDS);
            }
            for (var quad : candidates) {
                if (VoxelFaceOpacity.covers(quad, face)) {
                    words[face.get3DDataValue() * FACE_WORDS] |= OPAQUE_COVERAGE;
                    break;
                }
            }
        }
        return withBoundaryFacts(words, parts, VoxelShapeKind.FULL, List.of(), true);
    }

    /** Shared selection for static and emitted appearance; a map never composes coincident layers. */
    static BakedQuad mappedFace(List<BakedQuad> candidates, Direction face) {
        return candidates.size() == 1 ? candidates.getFirst() : backing(candidates, face);
    }

    private static BakedQuad backing(List<BakedQuad> candidates, Direction face) {
        BakedQuad base = null;
        for (var quad : candidates) if (VoxelFaceOpacity.covers(quad, face)) {
            if (base != null) return null;
            base = quad;
        }
        if (base == null) return null;
        int axis = VoxelBoundaryGeometry.axis(face);
        float sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : -1f;
        float plane = sign > 0 ? 1f : 0f;
        for (var quad : candidates) if (quad != base) {
            if (VoxelFaceOpacity.opaque(quad)) return null;
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                float coordinate = quad.position(vertex).get(axis);
                if (!Float.isFinite(coordinate) || sign * (coordinate - plane) <= 0f) return null;
            }
        }
        return base;
    }

    /** CROSS reuses four records in the 42-word entry, selected by the signed diagonal normal:
     * (nx > 0 ? 1 : 0) + (nz > 0 ? 2 : 0). Affine coordinates are block-local (x,y).
     * Cardinal palette colours stay zero; consumers must use the CROSS box to bound these maps. */
    static int[] packCross(List<BlockStateModelPart> parts, int tint) {
        int[] words = new int[ENTRY_WORDS];
        if (!FaceColorResolver.surface(parts).cross()) return words;
        int[][] maps = new int[4][];
        Object[] sprites = new Object[4];
        for (var part : parts) for (var quad : part.getQuads(null)) {
            int[] map = crossMapping(quad, tint);
            if (map == null) return new int[ENTRY_WORDS];
            var edge = new Vector3f(quad.position(1)).sub(quad.position(0));
            var normal = edge.cross(new Vector3f(quad.position(2)).sub(quad.position(0)));
            if (!normal.isFinite() || normal.y != 0f || normal.x == 0f || normal.z == 0f)
                return new int[ENTRY_WORDS];
            int slot = (normal.x > 0f ? 1 : 0) + (normal.z > 0f ? 2 : 0);
            if (maps[slot] != null && (sprites[slot] != quad.materialInfo().sprite()
                    || !compatible(maps[slot], map))) return new int[ENTRY_WORDS];
            maps[slot] = map;
            sprites[slot] = quad.materialInfo().sprite();
        }
        for (int slot = 0; slot < maps.length; slot++) if (maps[slot] != null)
            System.arraycopy(maps[slot], 0, words, slot * FACE_WORDS, FACE_WORDS);
        return words;
    }

    private static int[] crossMapping(BakedQuad quad, int tint) {
        if (quad.materialInfo().layer() != ChunkSectionLayer.CUTOUT
                || quad.materialInfo().tintIndex() > 0) return null;
        float x0 = Float.POSITIVE_INFINITY, y0 = x0;
        float x1 = Float.NEGATIVE_INFINITY, y1 = x1;
        for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
            var p = quad.position(vertex);
            if (!p.isFinite()) return null;
            x0 = Math.min(x0,p.x()); x1 = Math.max(x1,p.x());
            y0 = Math.min(y0,p.y()); y1 = Math.max(y1,p.y());
        }
        if (!(x1 > x0 && y1 > y0)) return null;
        float[][] uv = new float[4][];
        int[] order = new int[4];
        for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
            var p = quad.position(vertex);
            if ((p.x() != x0 && p.x() != x1) || (p.y() != y0 && p.y() != y1)) return null;
            int corner = (p.x() == x1 ? 1 : 0) + (p.y() == y1 ? 2 : 0);
            if (uv[corner] != null) return null;
            float u = UVPair.unpackU(quad.packedUV(vertex)), v = UVPair.unpackV(quad.packedUV(vertex));
            if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0f || u > 1f || v < 0f || v > 1f)
                return null;
            uv[corner] = new float[]{u,v}; order[vertex] = corner;
        }
        if ((order[0] ^ order[2]) != 3 || (order[1] ^ order[3]) != 3) return null;
        // The tracer reconstructs the palette's 1/16-grid rectangle. Preserve the real UV corner
        // values on those same rounded endpoints; fitting to unsent positions reads past sprites.
        x0 = VoxelShapeClassifier.to16ths(x0) / 16f;
        x1 = VoxelShapeClassifier.to16ths(x1) / 16f;
        y0 = VoxelShapeClassifier.to16ths(y0) / 16f;
        y1 = VoxelShapeClassifier.to16ths(y1) / 16f;
        if (!(x1 > x0 && y1 > y0)) return null;
        int[] words = new int[FACE_WORDS];
        for (int channel = 0; channel < 2; channel++) {
            float expected = uv[1][channel] + uv[2][channel] - uv[0][channel];
            // Four rounding ulps cover the three affine corner arithmetic operations.
            if (Math.abs(expected - uv[3][channel]) > 4 * Math.ulp(Math.max(Math.abs(expected), Math.abs(uv[3][channel]))))
                return null;
            float ds = (uv[1][channel] - uv[0][channel]) / (x1 - x0);
            float dt = (uv[2][channel] - uv[0][channel]) / (y1 - y0);
            float origin = uv[0][channel] - ds * x0 - dt * y0;
            if (!Float.isFinite(ds) || !Float.isFinite(dt) || !Float.isFinite(origin)) return null;
            words[1+channel] = Float.floatToRawIntBits(origin);
            words[3+channel] = Float.floatToRawIntBits(ds);
            words[5+channel] = Float.floatToRawIntBits(dt);
        }
        float determinant = Float.intBitsToFloat(words[3]) * Float.intBitsToFloat(words[6])
                - Float.intBitsToFloat(words[5]) * Float.intBitsToFloat(words[4]);
        if (!Float.isFinite(determinant) || determinant == 0f) return null;
        int rgb = quad.materialInfo().tintIndex() == 0 ? tint : -1;
        words[0] = 0x03000000 | (rgb & 0x00ffffff); // Usable affine map and alpha-tested coverage.
        if (quad.materialInfo().sprite() instanceof dev.icehunter.fornax.atlas.BlockAtlasGhostSprite ghost
                && ghost.hasOverflowCopy()) words[0] |= ghost.overflowPage() << ATLAS_PAGE_SHIFT;
        return words;
    }

    static int[] packPartial(List<BlockStateModelPart> parts, int tint) {
        int[] words = new int[ENTRY_WORDS];
        for (Direction face : Direction.values()) {
            int[] common = null;
            int[] sample = null;
            float sampleArea = 0f;
            Object sprite = null;
            boolean unsupported = false, translucent = false;
            for (var part : parts) {
                var candidates = new ArrayList<BakedQuad>(part.getQuads(face));
                for (var quad : part.getQuads(null)) if (quad.direction() == face) candidates.add(quad);
                for (var quad : candidates) {
                    translucent |= quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT;
                    int[] mapping = boundaryMapping(quad, face, tint);
                    if ((mapping[0] & BOUNDARY_MAPPING) == 0) { unsupported = true; continue; }
                    var rectangle = VoxelBoundaryGeometry.rectangle(quad);
                    // Exact 1/16-grid rectangle area; choose the first emitted quad on equal areas.
                    float area = (rectangle.s1() - rectangle.s0()) * (rectangle.t1() - rectangle.t0()) / 256f;
                    if (area > sampleArea) {
                        sampleArea = area;
                        sample = materialSample(quad, mapping[0], area);
                    }
                    if (common == null) { common = mapping; sprite = quad.materialInfo().sprite(); }
                    else if (sprite != quad.materialInfo().sprite() || !compatible(common, mapping)) unsupported = true;
                }
            }
            int base = face.get3DDataValue() * FACE_WORDS;
            if (common != null && !unsupported) System.arraycopy(common, 0, words, base, FACE_WORDS);
            else if (sample != null) System.arraycopy(sample, 0, words, base, FACE_WORDS);
            if (translucent) words[base] |= TRANSLUCENT_FACE;
        }
        return words;
    }

    private static int[] materialSample(BakedQuad quad, int header, float area) {
        int[] words = new int[FACE_WORDS];
        // A supported rectangle's affine texture center is its four-corner mean. Each actual UV
        // was checked against atlas bounds before fitting; extrapolated mapping origins are not used.
        float u = 0f, v = 0f;
        for (int vertex = 0; vertex < 4; vertex++) {
            u += UVPair.unpackU(quad.packedUV(vertex)) * 0.25f;
            v += UVPair.unpackV(quad.packedUV(vertex)) * 0.25f;
        }
        words[0] = (header & ~MATERIAL_SAMPLE_MASK) | MATERIAL_SAMPLE;
        words[1] = Float.floatToRawIntBits(u);
        words[2] = Float.floatToRawIntBits(v);
        words[3] = Float.floatToRawIntBits(area);
        return words;
    }

    static int[] withBoundaryFacts(int[] words, List<BlockStateModelPart> parts, VoxelShapeKind kind,
                                  List<VoxelShapeClassifier.PackedBox> boxes, boolean positionIndependent) {
        boolean closed = positionIndependent && VoxelBoundaryGeometry.certifies(parts, kind, boxes);
        for (Direction face : Direction.values()) {
            int base = face.get3DDataValue() * FACE_WORDS;
            words[base] &= ~CLOSED_BOX_BOUNDARY;
            if (closed) words[base] |= CLOSED_BOX_BOUNDARY;
            for (var part : parts) {
                var candidates = new ArrayList<BakedQuad>(part.getQuads(face));
                for (var quad : part.getQuads(null)) if (quad.direction() == face) candidates.add(quad);
                if (candidates.stream().anyMatch(q -> q.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT))
                    words[base] |= TRANSLUCENT_FACE;
            }
        }
        return words;
    }

    static int[] withoutBoundaryProof(int[] words) {
        if ((words[0] & CLOSED_BOX_BOUNDARY) == 0) return words;
        int[] result = words.clone();
        for (int face = 0; face < 6; face++) result[face * FACE_WORDS] &= ~CLOSED_BOX_BOUNDARY;
        return result;
    }

    /** Fit an affine UV map in block-local face coordinates, including a partial pane's edges.
     * A rejected map carries no usable bit; callers must not replace it with a unit-cube map. */
    static int[] boundaryMapping(BakedQuad quad, Direction face, int tint) {
        int[] words = new int[FACE_WORDS];
        var layer = quad.materialInfo().layer();
        if (quad.direction() != face || (layer != ChunkSectionLayer.CUTOUT && layer != ChunkSectionLayer.TRANSLUCENT)
                || quad.materialInfo().tintIndex() > 0) return words;
        var rectangle = VoxelBoundaryGeometry.rectangle(quad);
        if (rectangle == null) return words;
        int axis = VoxelBoundaryGeometry.axis(face);
        float[][] uv = new float[4][];
        for (int v = 0; v < 4; v++) {
            var p = quad.position(v);
            float ss = (axis == 0 ? p.y() : p.x()) * 16;
            float tt = (axis == 2 ? p.y() : p.z()) * 16;
            int corner = (ss == rectangle.s1() ? 1 : 0) | (tt == rectangle.t1() ? 2 : 0);
            float u = UVPair.unpackU(quad.packedUV(v)), w = UVPair.unpackV(quad.packedUV(v));
            if (!Float.isFinite(u) || !Float.isFinite(w) || u < 0f || u > 1f || w < 0f || w > 1f) return words;
            uv[corner] = new float[]{u,w};
        }
        for (int c = 0; c < 2; c++) {
            float expected = uv[1][c] + uv[2][c] - uv[0][c];
            // Three float additions/subtractions require at most four rounding ulps.
            if (Math.abs(expected - uv[3][c]) > 4 * Math.ulp(Math.max(Math.abs(expected), Math.abs(uv[3][c])))) return words;
            float ds = (uv[1][c] - uv[0][c]) * 16 / (rectangle.s1() - rectangle.s0());
            float dt = (uv[2][c] - uv[0][c]) * 16 / (rectangle.t1() - rectangle.t0());
            float origin = uv[0][c] - ds * (rectangle.s0() / 16f) - dt * (rectangle.t0() / 16f);
            words[1 + c] = Float.floatToRawIntBits(origin);
            words[3 + c] = Float.floatToRawIntBits(ds);
            words[5 + c] = Float.floatToRawIntBits(dt);
        }
        int rgb = quad.materialInfo().tintIndex() == 0 ? tint : -1;
        words[0] = BOUNDARY_MAPPING | (rgb & 0x00ffffff)
                | (layer == ChunkSectionLayer.TRANSLUCENT ? TRANSLUCENT_FACE : 1 << 25);
        if (quad.materialInfo().sprite() instanceof dev.icehunter.fornax.atlas.BlockAtlasGhostSprite ghost
                && ghost.hasOverflowCopy()) words[0] |= ghost.overflowPage() << ATLAS_PAGE_SHIFT;
        return words;
    }

    private static boolean compatible(int[] first, int[] next) {
        if (first[0] != next[0]) return false;
        for (int word = 1; word < FACE_WORDS; word++) {
            float a = Float.intBitsToFloat(first[word]), b = Float.intBitsToFloat(next[word]);
            // Affine fitting and extrapolation each use up to four rounded operations.
            if (Math.abs(a - b) > 8 * Math.ulp(Math.max(Math.abs(a), Math.abs(b)))) return false;
        }
        return true;
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
        // UVs are separate from how solid a face is: a see-through face needs its atlas alpha too.
        // The solid-cover flag is set elsewhere. Only CUTOUT gets the alpha-test bit.
        if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT
                && layer != ChunkSectionLayer.TRANSLUCENT) return words;
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
        int flags = layer == ChunkSectionLayer.TRANSLUCENT ? 0xa0 : 1 | (layer == ChunkSectionLayer.CUTOUT ? 2 : 0);
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
