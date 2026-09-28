package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Test-only bridge to the actual palette, face mapping and boundary-proof encoders. */
public final class GlassOpticalGpuFixtures {
    private GlassOpticalGpuFixtures() { }

    public static byte[] cube(boolean translucent, boolean closed, int argb) {
        List<BakedQuad> quads = new ArrayList<>();
        var layer = translucent ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.CUTOUT;
        for (Direction face : Direction.values()) {
            int axis = VoxelBoundaryGeometry.axis(face);
            float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            Vector3f[] p = new Vector3f[4];
            long[] uv = new long[4];
            int[] corners = {0, 1, 3, 2};
            for (int vertex = 0; vertex < 4; vertex++) {
                float s = corners[vertex] & 1, t = (corners[vertex] >> 1) & 1;
                p[vertex] = axis == 0 ? new Vector3f(plane, s, t)
                        : axis == 1 ? new Vector3f(s, plane, t) : new Vector3f(s, t, plane);
                // Arbitrary nondegenerate atlas rectangle; it exercises real float-word mapping.
                uv[vertex] = UVPair.pack(.25f + .125f * s, .5f + .125f * t);
            }
            quads.add(new BakedQuad(p[0], p[1], p[2], p[3], uv[0], uv[1], uv[2], uv[3], face,
                    new BakedQuad.MaterialInfo(null, layer, null, 0, true, 0)));
        }
        List<BlockStateModelPart> parts = List.of(new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face == null ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        });
        int[] faces = VoxelFaceTexture.withBoundaryFacts(VoxelFaceTexture.pack(parts, -1), parts,
                VoxelShapeKind.FULL, List.of(), closed);
        int[] colours = new int[6];
        Arrays.fill(colours, argb);
        var entry = new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(), colours, 0, true, 0,
                !translucent, new float[]{.25f, .5f, .375f, .625f}, 0, 0, faces);
        byte[] palette = BrickGridUpload.packPaletteEntries(List.of(entry));
        ByteBuffer words = ByteBuffer.allocate(palette.length + faces.length * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        words.put(palette);
        for (int face : faces) words.putInt(face);
        return words.array();
    }
}
