package dev.icehunter.fornax.voxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

/** Optional exact UV mapping for a cell face made of one quad. Any other shape stays zero. This
 * extra buffer leaves the 16-word palette layout alone. */
public final class VoxelFaceTexture {
    public static final String TARGET = "voxelFaceTexture";
    public static final int FACE_WORDS = 8;
    public static final int ENTRY_WORDS = 6 * FACE_WORDS;
    public static final int WORDS_PER_SLOT = SectionHarvester.MAX_PALETTE_ENTRIES * ENTRY_WORDS;
    public static final int BYTES_PER_SLOT = WORDS_PER_SLOT * Integer.BYTES;
    private VoxelFaceTexture() { }

    static int[] resolve(BlockState state, VoxelShapeKind kind, int tint) {
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
        }
        return words;
    }

    /** words: flags(valid=1, alpha-tested=2), ARGB tint, then float u0,v0,du/ds,dv/ds,du/dt,dv/dt.
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
        words[0] = 1 | (quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT ? 2 : 0);
        words[1] = quad.materialInfo().tintIndex() == 0 ? tint : -1;
        words[2] = Float.floatToRawIntBits(uv[0][0]); words[3] = Float.floatToRawIntBits(uv[0][1]);
        words[4] = Float.floatToRawIntBits(uv[1][0]-uv[0][0]); words[5] = Float.floatToRawIntBits(uv[1][1]-uv[0][1]);
        words[6] = Float.floatToRawIntBits(uv[2][0]-uv[0][0]); words[7] = Float.floatToRawIntBits(uv[2][1]-uv[0][1]);
        return words;
    }
}
