package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Final, neighbour-visible full-face materials, independent of optical volume certification.
 * A missing face is unavailable, never permission to read the model's unresolved fallback sprite.
 * No sprite or renderer callback survives reduction. Biome tint remains section-local at harvest. */
final class VoxelFaceAppearance {
    static final VoxelFaceAppearance UNAVAILABLE = new VoxelFaceAppearance(
            new int[VoxelFaceTexture.ENTRY_WORDS], new int[6], new int[6]);
    private final int[] words, colors, tintIndices;
    private final @Nullable Sources sources;

    /** Sprite identities are reduced while the atlas lease is held; no renderer object escapes. */
    private record Sources(long generation, List<MaterialSourceIndex.Summary> faces) {
        Sources { faces = List.copyOf(faces); }
    }

    private VoxelFaceAppearance(int[] words, int[] colors, int[] tintIndices) {
        this(words, colors, tintIndices, null);
    }

    private VoxelFaceAppearance(int[] words, int[] colors, int[] tintIndices, @Nullable Sources sources) {
        this.words = words.clone();
        this.colors = colors.clone();
        this.tintIndices = tintIndices.clone();
        this.sources = sources;
    }

    Resolved resolve(IntUnaryOperator tints) {
        int[] resolved = words.clone(), measured = colors.clone();
        for (int face = 0; face < 6; face++) {
            int base = face * VoxelFaceTexture.FACE_WORDS;
            if ((resolved[base] & ((1 << 24) | VoxelFaceTexture.BOUNDARY_MAPPING)) == 0) continue;
            int tint = tintIndices[face] < 0 ? -1 : tints.applyAsInt(tintIndices[face]);
            int multiplier = multiply(resolved[base], tint);
            resolved[base] = (resolved[base] & 0xff000000) | multiplier;
            measured[face] = (measured[face] & 0xff000000) | multiply(measured[face], multiplier);
        }
        return new Resolved(resolved, measured, sources);
    }

    private static int multiply(int a, int b) {
        int rgb = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            // Byte-normalized colour multiplication, rounded to the nearest byte.
            rgb |= (((((a >>> shift) & 255) * ((b >>> shift) & 255)) + 127) / 255) << shift;
        }
        return rgb;
    }

    @Override public boolean equals(Object other) {
        return other instanceof VoxelFaceAppearance a && Arrays.equals(words, a.words)
                && Arrays.equals(colors, a.colors) && Arrays.equals(tintIndices, a.tintIndices)
                && Objects.equals(sources, a.sources);
    }

    @Override public int hashCode() {
        return 31 * (31 * (31 * Arrays.hashCode(words) + Arrays.hashCode(colors))
                + Arrays.hashCode(tintIndices)) + Objects.hashCode(sources);
    }

    static final class Resolved {
        private final int[] words, colors;
        private final @Nullable Sources sources;

        Resolved(int[] words, int[] colors) { this(words, colors, null); }

        private Resolved(int[] words, int[] colors, @Nullable Sources sources) {
            this.words = words.clone();
            this.colors = colors.clone();
            this.sources = sources;
        }

        VoxelFaceTexture.@Nullable SourceFaces sourceFaces(long generation) {
            if (sources == null || sources.generation() <= 0 || sources.generation() != generation) return null;
            boolean knownNonpositive = sources.faces().stream().allMatch(VoxelFaceTexture::knownNonpositive);
            return new VoxelFaceTexture.SourceFaces(words, sources.faces(), generation, knownNonpositive);
        }

        int[] words() { return words.clone(); }
        int[] colors() { return colors.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Resolved a && Arrays.equals(words, a.words) && Arrays.equals(colors, a.colors)
                    && Objects.equals(sources, a.sources);
        }

        @Override public int hashCode() {
            return 31 * (31 * Arrays.hashCode(words) + Arrays.hashCode(colors)) + Objects.hashCode(sources);
        }
    }

    static final class Collector {
        // Same existing per-model observation budget as optical capture: two layers per box face.
        private static final int MAX_QUADS = 12 * VoxelShapeClassifier.MAX_BOXES;
        private record Sample(BakedQuad quad, int tintIndex, int vertexColor) { }
        private final Function<QuadView, @Nullable TextureAtlasSprite> sprites;
        private final MaterialSourceIndex sourceIndex;
        private final List<Sample> samples = new ArrayList<>();
        private final boolean[] invalid = new boolean[6], opaqueCoverage = new boolean[6];
        private int quadCount;
        private boolean overflow;

        Collector(Function<QuadView, @Nullable TextureAtlasSprite> sprites, MaterialSourceIndex sourceIndex) {
            this.sprites = sprites;
            this.sourceIndex = sourceIndex;
        }

        void accept(QuadView quad) {
            Direction face = quad.lightFace();
            int side = face.get3DDataValue();
            if (overflow) return;
            if (++quadCount > MAX_QUADS) { overflow = true; return; }
            try {
                var layer = quad.chunkLayer();
                if (quad.atlas() != QuadAtlas.BLOCK || (layer != ChunkSectionLayer.SOLID
                        && layer != ChunkSectionLayer.CUTOUT && layer != ChunkSectionLayer.TRANSLUCENT)) {
                    invalid[side] = true;
                    return;
                }
                int color = quad.color(0);
                boolean opaqueVertices = true;
                for (int v = 0; v < 4; v++) {
                    invalid[side] |= quad.color(v) != color;
                    opaqueVertices &= (quad.color(v) >>> 24) == 255;
                }
                invalid[side] |= !opaqueVertices;
                var sprite = sprites.apply(quad);
                if (sprite == null) { invalid[side] = true; return; }
                Vector3f[] p = new Vector3f[4];
                long[] uv = new long[4];
                for (int v = 0; v < 4; v++) {
                    p[v] = new Vector3f(quad.x(v), quad.y(v), quad.z(v));
                    uv[v] = UVPair.pack(quad.u(v), quad.v(v));
                }
                // The emitted tint index is retained separately; the mapping helper sees untinted
                // UV geometry, and harvest supplies that actual layer's section-centre tint.
                var baked = new BakedQuad(p[0], p[1], p[2], p[3], uv[0], uv[1], uv[2], uv[3], face,
                        new BakedQuad.MaterialInfo(sprite, layer, null, -1, true, 0));
                // Coverage is geometry/material evidence, not a UV-composition certificate. A
                // later unsupported overlay or vertex gradient cannot erase the opaque backing.
                opaqueCoverage[side] |= opaqueVertices && VoxelFaceOpacity.covers(baked, face);
                samples.add(new Sample(baked, quad.tintIndex(), color));
            } catch (RuntimeException unavailable) {
                invalid[side] = true;
            }
        }

        VoxelFaceAppearance result() {
            int[] words = new int[VoxelFaceTexture.ENTRY_WORDS], colors = new int[6], tints = new int[6];
            Arrays.fill(tints, -1);
            var sourceFaces = new ArrayList<MaterialSourceIndex.Summary>();
            for (Direction face : Direction.values()) {
                int side = face.get3DDataValue(), base = side * VoxelFaceTexture.FACE_WORDS;
                if (opaqueCoverage[side]) words[base] = VoxelFaceTexture.OPAQUE_COVERAGE;
                var candidates = new ArrayList<BakedQuad>();
                for (var sample : samples) if (sample.quad.direction() == face) candidates.add(sample.quad);
                if (invalid[side] || overflow) {
                    sourceFaces.add(MaterialSourceIndex.unavailable(MaterialSourceIndex.UNSUPPORTED_GEOMETRY));
                    continue;
                }
                copyMapping(candidates, face, words, colors, tints);
                sourceFaces.add(VoxelFaceTexture.sourceSummary(candidates, VoxelShapeKind.FULL, words[base], sourceIndex));
            }
            return new VoxelFaceAppearance(words, colors, tints, new Sources(sourceIndex.generation(), sourceFaces));
        }

        private void copyMapping(List<BakedQuad> candidates, Direction face, int[] words, int[] colors, int[] tints) {
            int side = face.get3DDataValue(), base = side * VoxelFaceTexture.FACE_WORDS;
            try {
                BakedQuad selected = VoxelFaceTexture.mappedFace(candidates, face);
                if (selected == null) return;
                int[] map = VoxelFaceTexture.mapping(selected, face, -1);
                if ((map[0] & ((1 << 24) | VoxelFaceTexture.BOUNDARY_MAPPING)) == 0) return;
                Sample source = null;
                for (var sample : samples) if (sample.quad == selected) { source = sample; break; }
                if (source == null) return;
                map[0] = (map[0] & 0xff000000) | (source.vertexColor & 0x00ffffff) | words[base];
                map[0] &= ~VoxelFaceTexture.CLOSED_BOX_BOUNDARY;
                colors[side] = FaceColorResolver.averageQuadColor(selected);
                tints[side] = source.tintIndex;
                System.arraycopy(map, 0, words, base, map.length);
            } catch (RuntimeException unavailable) {
                // Only this direction loses its material fact; independent coverage survives.
            }
        }

    }
}
