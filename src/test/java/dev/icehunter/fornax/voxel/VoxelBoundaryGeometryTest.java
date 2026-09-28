package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelBoundaryGeometryTest {
    @Test void fullCubeRequiresAllSixPhysicalFaces() {
        var quads = cube(new VoxelShapeClassifier.PackedBox(0,0,0,16,16,16), ChunkSectionLayer.TRANSLUCENT);
        assertTrue(VoxelBoundaryGeometry.certifies(parts(quads), VoxelShapeKind.FULL, List.of()));
        int[] words = VoxelFaceTexture.pack(parts(quads), 0xff123456);
        for (int face = 0; face < 6; face++) {
            // Mapping + closure + layer are independent of legacy UV validity and opaque coverage.
            assertEquals(0xe0123456, words[face * 7]);
        }
        quads.removeFirst();
        assertFalse(VoxelBoundaryGeometry.certifies(parts(quads), VoxelShapeKind.FULL, List.of()));
    }

    @Test void paneUsesItsActualThicknessAndBlockLocalAffineCoordinates() {
        // A two-model-unit pane is one eighth of a block thick.
        var box = new VoxelShapeClassifier.PackedBox(7,0,0,9,16,16);
        var model = parts(cube(box, ChunkSectionLayer.TRANSLUCENT));
        int[] words = VoxelFaceTexture.withBoundaryFacts(VoxelFaceTexture.packPartial(model, 0xff123456),
                model, VoxelShapeKind.PARTIAL, List.of(box), true);
        for (Direction face : Direction.values()) {
            int base = face.get3DDataValue() * 7;
            assertEquals(0xe0123456, words[base]);
            assertEquals(.25f, Float.intBitsToFloat(words[base + 1]));
            assertEquals(.5f, Float.intBitsToFloat(words[base + 2]));
            assertEquals(.125f, Float.intBitsToFloat(words[base + 3]));
            assertEquals(.125f, Float.intBitsToFloat(words[base + 6]));
        }
        assertFalse(VoxelBoundaryGeometry.certifies(model, VoxelShapeKind.FULL, List.of()));
    }

    @Test void joinedPaneArmsCertifyTheirUnionWithoutInventingACube() {
        var boxes = List.of(new VoxelShapeClassifier.PackedBox(7,0,7,9,16,9),
                new VoxelShapeClassifier.PackedBox(7,0,0,9,16,7),
                new VoxelShapeClassifier.PackedBox(7,0,9,9,16,16),
                new VoxelShapeClassifier.PackedBox(0,0,7,7,16,9),
                new VoxelShapeClassifier.PackedBox(9,0,7,16,16,9));
        List<BakedQuad> quads = new ArrayList<>();
        for (var box : boxes) quads.addAll(cube(box, ChunkSectionLayer.TRANSLUCENT));
        var model = parts(quads);
        assertTrue(VoxelBoundaryGeometry.certifies(model, VoxelShapeKind.PARTIAL, boxes));
        int[] words = VoxelFaceTexture.packPartial(model, -1);
        for (int face = 0; face < 6; face++) assertEquals(0xa0ffffff, words[face * 7]);
        assertFalse(VoxelBoundaryGeometry.certifies(model, VoxelShapeKind.FULL, List.of()));
    }

    @Test void cutoutPaneDoesNotTurnOnLegacyValidityOrTheBlendLayerFact() {
        var box = new VoxelShapeClassifier.PackedBox(7,0,0,9,16,16);
        var model = parts(cube(box, ChunkSectionLayer.CUTOUT));
        int[] words = VoxelFaceTexture.withBoundaryFacts(VoxelFaceTexture.packPartial(model, 0xff123456),
                model, VoxelShapeKind.PARTIAL, List.of(box), true);
        for (int face = 0; face < 6; face++) assertEquals(0x62123456, words[face * 7]);
    }

    @Test void conflictingMapsKeepLayerClosureAndMaterialSampleButNoUsableUv() {
        var box = new VoxelShapeClassifier.PackedBox(7,0,0,9,16,16);
        var quads = cube(box, ChunkSectionLayer.TRANSLUCENT);
        BakedQuad q = quads.get(Direction.NORTH.get3DDataValue());
        quads.add(new BakedQuad(q.position0(), q.position1(), q.position2(), q.position3(),
                UVPair.pack(.75f, .5f), UVPair.pack(.875f, .5f), UVPair.pack(.875f, .625f),
                UVPair.pack(.75f, .625f), q.direction(), q.materialInfo()));
        var model = parts(quads);
        int[] words = VoxelFaceTexture.withBoundaryFacts(VoxelFaceTexture.packPartial(model, -1),
                model, VoxelShapeKind.PARTIAL, List.of(box), true);
        int base = Direction.NORTH.get3DDataValue() * 7;
        int header = words[base];
        assertEquals(VoxelFaceTexture.CLOSED_BOX_BOUNDARY | VoxelFaceTexture.TRANSLUCENT_FACE,
                header & 0xc0000000);
        assertEquals(VoxelFaceTexture.MATERIAL_SAMPLE, header & VoxelFaceTexture.MATERIAL_SAMPLE_MASK);
        assertEquals(0, header & 0x21000000, "Neither affine mapping bit may be inferred from the sample");
        assertEquals(0x00ffffff, header & 0x00ffffff);
        // Equal-area overlays choose the first rectangle: its midpoint is (x,y)=(1/2,1/2),
        // its UV is (1/4+x/8,1/2+y/8), and its area is (2/16)*1 square blocks.
        assertEquals(.3125f, Float.intBitsToFloat(words[base + 1]));
        assertEquals(.5625f, Float.intBitsToFloat(words[base + 2]));
        assertEquals(.125f, Float.intBitsToFloat(words[base + 3]));
        for (int word = 4; word < 7; word++) assertEquals(0, words[base + word]);
    }

    @Test void offGridRotatedAndDisplacedGeometryCannotClaimClosedBoxProof() {
        var box = new VoxelShapeClassifier.PackedBox(7,0,0,9,16,16);
        var quads = cube(box, ChunkSectionLayer.TRANSLUCENT);
        BakedQuad q = quads.getFirst();
        quads.set(0, new BakedQuad(new Vector3f(q.position0()).add(1f / 32,0,0),
                q.position1(), q.position2(), q.position3(), q.packedUV0(), q.packedUV1(),
                q.packedUV2(), q.packedUV3(), q.direction(), q.materialInfo()));
        assertFalse(VoxelBoundaryGeometry.certifies(parts(quads), VoxelShapeKind.PARTIAL, List.of(box)));
        var valid = parts(cube(box, ChunkSectionLayer.TRANSLUCENT));
        int[] words = VoxelFaceTexture.withBoundaryFacts(VoxelFaceTexture.packPartial(valid, -1),
                valid, VoxelShapeKind.PARTIAL, List.of(box), false);
        assertEquals(0, words[0] & VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
    }

    @Test void geometryChangesDiscardProofWithoutMutatingExistingData() {
        int[] original = new int[VoxelFaceTexture.ENTRY_WORDS];
        for (int face = 0; face < 6; face++) original[face * 7] = 0xe0123456;
        int[] result = VoxelFaceTexture.withoutBoundaryProof(original);
        for (int face = 0; face < 6; face++) {
            assertEquals(0xa0123456, result[face * 7]);
            assertEquals(0xe0123456, original[face * 7]);
        }
    }

    private static List<BakedQuad> cube(VoxelShapeClassifier.PackedBox box, ChunkSectionLayer layer) {
        List<BakedQuad> result = new ArrayList<>();
        float[] lo = {box.minX()/16f, box.minY()/16f, box.minZ()/16f};
        float[] hi = {box.maxX()/16f, box.maxY()/16f, box.maxZ()/16f};
        for (Direction face : Direction.values()) {
            int axis = VoxelBoundaryGeometry.axis(face);
            float n = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? hi[axis] : lo[axis];
            int sa = axis == 0 ? 1 : 0, ta = axis == 2 ? 1 : 2;
            Vector3f[] positions = new Vector3f[4];
            long[] uv = new long[4];
            int[] corners = {0,1,3,2};
            for (int v = 0; v < 4; v++) {
                float s = (corners[v] & 1) != 0 ? hi[sa] : lo[sa];
                float t = (corners[v] & 2) != 0 ? hi[ta] : lo[ta];
                positions[v] = axis == 0 ? new Vector3f(n,s,t) : axis == 1 ? new Vector3f(s,n,t) : new Vector3f(s,t,n);
                uv[v] = UVPair.pack(.25f + .125f*s, .5f + .125f*t);
            }
            result.add(new BakedQuad(positions[0],positions[1],positions[2],positions[3],
                    uv[0],uv[1],uv[2],uv[3],face,
                    new BakedQuad.MaterialInfo(null,layer,null,0,true,0)));
        }
        return result;
    }

    private static List<BlockStateModelPart> parts(List<BakedQuad> quads) {
        return List.of(new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face == null ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        });
    }
}
