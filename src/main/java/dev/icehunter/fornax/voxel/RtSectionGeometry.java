package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.BlockAtlasGhostSprite;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** RT-only geometry captured with the voxel result, never substituted into the lighting palette.
 * Primitive positions and UVs come only from certified baked models. A section without a complete
 * representation cannot certify a shadow ray, even if its approximate voxel boxes are ready. */
public record RtSectionGeometry(List<Cell> cells, boolean exact) {
    // Bit31 is unused by the voxel/face/palette primitive index. The remaining seven words carry
    // three UV pairs and a raster-backface-cull flag, making the supplemental Metal primitive stride 32 bytes.
    public static final int EXACT_UV_BIT = 1 << 31;
    public static final int PRIMITIVE_WORDS = 8;
    public static final RtSectionGeometry EMPTY = new RtSectionGeometry(List.of(), true);
    public static final RtSectionGeometry UNKNOWN = new RtSectionGeometry(List.of(), false);
    // One neighboring section suffices for readiness checks when vertices stay within this bound.
    public static final float MAX_SECTION_SPILL = 1f;
    // Allocation guard: a vanilla cross has four quads including reverse winding. Eight times that
    // budget allows modest model extensions; excess marks the section unknown, never truncates it.
    private static final int MAX_QUADS_PER_CELL = 32;

    public RtSectionGeometry { cells = List.copyOf(cells); }
    public record Cell(int voxelIndex, int paletteIndex, List<float[]> quads, Vec3 offset) {
        public Cell { quads = List.copyOf(quads); }
    }
    public record Mesh(float[] vertices, int[] primitiveWords) {
        public int triangleCount() { return vertices.length / 9; }
    }

    /** Old constructors can certify empty sections and ordinary full cubes, but not missing CROSS
     * metadata or the shape reconstruction history of PARTIAL boxes. */
    static RtSectionGeometry legacy(SectionPalette palette) {
        for (var entry : palette.entries()) {
            if (entry.shapeKind() == VoxelShapeKind.CROSS || entry.shapeKind() == VoxelShapeKind.PARTIAL)
                return UNKNOWN;
        }
        return EMPTY;
    }

    public Mesh mesh() {
        int quads = cells.stream().mapToInt(cell -> cell.quads().size()).sum();
        float[] vertices = new float[quads * 2 * 9];
        int[] words = new int[quads * 2 * PRIMITIVE_WORDS];
        int triangle = 0;
        // Triangle fan follows the baked vertex order. The native intersector interpolates these
        // exact UVs with its barycentric coordinates, so offsets never enter alpha-coordinate maths.
        int[][] corners = {{0,1,2},{0,2,3}};
        for (Cell cell : cells) for (float[] quad : cell.quads()) for (int[] face : corners) {
            int base = triangle * PRIMITIVE_WORDS;
            words[base] = EXACT_UV_BIT | cell.voxelIndex() | (6 << 12) | (cell.paletteIndex() << 16);
            // The terrain shadow pipeline enables culling. Each baked winding therefore carries
            // its own UVs; opposite-winding faces must never be alpha-unioned as two-sided planes.
            words[base+7] = 1;
            for (int v = 0; v < 3; v++) {
                int source = face[v] * 5, vertex = triangle * 9 + v * 3;
                vertices[vertex] = quad[source] + (cell.voxelIndex() & 15) + (float)cell.offset().x;
                vertices[vertex+1] = quad[source+1] + (cell.voxelIndex() >> 8) + (float)cell.offset().y;
                vertices[vertex+2] = quad[source+2] + ((cell.voxelIndex() >> 4) & 15) + (float)cell.offset().z;
                words[base+1+v*2] = Float.floatToRawIntBits(quad[source+3]);
                words[base+2+v*2] = Float.floatToRawIntBits(quad[source+4]);
            }
            triangle++;
        }
        return new Mesh(vertices, words);
    }

    public static final class Builder {
        private final List<Cell> cells = new ArrayList<>();
        private boolean exact = true;
        public void unknown() { exact = false; }

        void harvest(BlockState state, SectionPalette.Entry entry, int voxelIndex, int paletteIndex,
                     BlockPos absolutePosition) {
            if (entry.shapeKind() == VoxelShapeKind.EMPTY || entry.shapeKind() == VoxelShapeKind.FULL) return;
            if (entry.shapeKind() != VoxelShapeKind.CROSS) {
                // PARTIAL selection boxes and absent face UVs do not certify raster coverage.
                // Keep that limitation explicit until actual model geometry is available for them.
                unknown(); return;
            }
            try {
                var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
                addModel(voxelIndex, paletteIndex, model, state, absolutePosition);
            } catch (RuntimeException failure) {
                unknown(); // Optional RT metadata cannot abort the underlying voxel harvest.
            }
        }

        /** Harvest may read baked parts but must not enter live renderer callbacks. Only a direct
         * baked variant with known baked parts certifies final geometry here. Composite models can
         * hide custom children whose collected fallback differs from their rendered quads; they
         * remain unknown until final geometry can be captured within the renderer's own lifecycle. */
        void addModel(int voxelIndex, int paletteIndex, BlockStateModel model,
                      BlockState state, BlockPos absolutePosition) {
            try {
                if (model.getClass() != SingleVariant.class) {
                    unknown(); return;
                }
                List<BlockStateModelPart> parts = new ArrayList<>();
                // Use the same absolute-position seed as terrain, including negative world positions.
                model.collectParts(RandomSource.create(state.getSeed(absolutePosition)), parts);
                List<BakedQuad> quads = new ArrayList<>();
                for (var part : parts) {
                    if (part.getClass() != SimpleModelWrapper.class) {
                        unknown(); return; // custom parts may override their rendered geometry too
                    }
                    for (var direction : Direction.values()) if (!part.getQuads(direction).isEmpty()) {
                        unknown(); return; // directional face-culling requires neighboring model context
                    }
                    quads.addAll(part.getQuads(null));
                }
                add(voxelIndex, paletteIndex, quads, state, absolutePosition);
            } catch (RuntimeException failure) {
                unknown(); // Optional RT metadata cannot abort the underlying voxel harvest.
            }
        }

        public void add(int voxelIndex, int paletteIndex, List<BakedQuad> source,
                        BlockState state, BlockPos absolutePosition) {
            add(voxelIndex,paletteIndex,source,state.getOffset(absolutePosition));
        }

        public void add(int voxelIndex, int paletteIndex, List<BakedQuad> source, Vec3 offset) {
            if (source.isEmpty() || source.size() > MAX_QUADS_PER_CELL) { unknown(); return; }
            List<float[]> quads = new ArrayList<>();
            for (var quad : source) {
                var material = quad.materialInfo();
                if ((material.layer() != ChunkSectionLayer.SOLID && material.layer() != ChunkSectionLayer.CUTOUT)
                        || material.sprite() instanceof BlockAtlasGhostSprite) { unknown(); return; }
                float[] packed = new float[20];
                for (int v=0; v<4; v++) {
                    var position = quad.position(v);
                    packed[v*5]=position.x(); packed[v*5+1]=position.y(); packed[v*5+2]=position.z();
                    packed[v*5+3]=UVPair.unpackU(quad.packedUV(v));
                    packed[v*5+4]=UVPair.unpackV(quad.packedUV(v));
                }
                if (!validQuad(packed,voxelIndex,offset)) { unknown(); return; }
                quads.add(packed);
            }
            cells.add(new Cell(voxelIndex,paletteIndex,quads,offset));
        }

        private static boolean validQuad(float[] packed,int voxelIndex,Vec3 offset) {
            for (float value:packed) if (!Float.isFinite(value)) return false;
            for (int v=0;v<4;v++) {
                double[] section = {packed[v*5]+(voxelIndex&15)+offset.x,
                        packed[v*5+1]+(voxelIndex>>8)+offset.y,packed[v*5+2]+((voxelIndex>>4)&15)+offset.z};
                for (double coordinate : section) if (!Double.isFinite(coordinate)
                        || coordinate < -MAX_SECTION_SPILL || coordinate > 16+MAX_SECTION_SPILL) return false;
            }
            // Raster mesh optimization may select either diagonal. A planar parallelogram with
            // affine UVs is invariant to that choice. Eight ulps bound four rounded terms with a
            // twofold rounding margin; this is numerical tolerance, not geometry clipping.
            for (int c=0;c<5;c++) {
                float scale=Math.max(Math.max(Math.abs(packed[c]),Math.abs(packed[5+c])),
                        Math.max(Math.abs(packed[10+c]),Math.abs(packed[15+c])));
                if (Math.abs((packed[c]+packed[10+c])-(packed[5+c]+packed[15+c])) > 8*Math.ulp(scale)) return false;
            }
            return true;
        }
        public RtSectionGeometry finish() { return new RtSectionGeometry(cells,exact); }
    }
}
