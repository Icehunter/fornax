package dev.icehunter.fornax.voxel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class VoxelFaceTextureTest {
    private BakedQuad north(int tint, boolean rotate) {
        return new BakedQuad(new Vector3f(0,0,0),new Vector3f(1,0,0),new Vector3f(1,1,0),new Vector3f(0,1,0),
            UVPair.pack(.25f,.5f), UVPair.pack(rotate?.25f:.5f,rotate?.75f:.5f),
            UVPair.pack(.5f,.75f), UVPair.pack(rotate?.5f:.25f,rotate?.5f:.75f),Direction.NORTH,
            new BakedQuad.MaterialInfo(null,ChunkSectionLayer.SOLID,null,tint,true,0));
    }
    @Test void originalAndRotatedUvAreEvaluatedAtRealLocalCoordinates() {
        for(boolean rotate:new boolean[]{false,true}) {
            int[] words=VoxelFaceTexture.mapping(north(-1,rotate),Direction.NORTH,0xff00ff00);
            assertEquals(0x01ffffff,words[0]);
            assertEquals(.25f,Float.intBitsToFloat(words[1]));
            assertEquals(rotate?0:.25f,Float.intBitsToFloat(words[3]));
            assertEquals(rotate?.25f:0,Float.intBitsToFloat(words[5]));
        }
    }
    @Test void eligibleTintIsRetainedAndUnknownTintLayerIsUnsupported() {
        assertEquals(0x0100ff00,VoxelFaceTexture.mapping(north(0,false),Direction.NORTH,0xff00ff00)[0]);
        assertEquals(0,VoxelFaceTexture.mapping(north(1,false),Direction.NORTH,0xff00ff00)[0]);
    }
    @Test void mappingRejectsWrongFacePlane() {
        assertEquals(0,VoxelFaceTexture.mapping(north(-1,false),Direction.SOUTH,-1)[0]);
    }
    @Test void sidecarLeavesExistingPaletteStrideUnchanged() {
        assertEquals(16,BrickGridUpload.PALETTE_ENTRY_WORDS);
        // Seven words per face, six faces, 96 palette entries in one section slot.
        assertEquals(7,VoxelFaceTexture.FACE_WORDS);
        assertEquals(42,VoxelFaceTexture.ENTRY_WORDS);
        assertEquals(4032,VoxelFaceTexture.WORDS_PER_SLOT);
        assertEquals(16128,VoxelFaceTexture.BYTES_PER_SLOT);
        assertEquals(0,VoxelFaceTexture.BYTES_PER_SLOT % Integer.BYTES);
    }
    @Test void multipleQuadsRemainExplicitlyUnsupported() {
        var q = north(-1,false);
        var part = new net.minecraft.client.renderer.block.dispatch.BlockStateModelPart() {
            public java.util.List<BakedQuad> getQuads(Direction face) {
                return face == null ? java.util.List.of(q,q) : java.util.List.of();
            }
            public boolean useAmbientOcclusion() { return true; }
            public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
        assertArrayEquals(new int[42],VoxelFaceTexture.pack(java.util.List.of(part),-1));
    }
    @Test void absentMappingPacksZerosAndExistingPaletteStillHasSixteenWords() {
        var entry = new SectionPalette.Entry(VoxelShapeKind.FULL,java.util.List.of(),new int[6],0,false,0);
        assertArrayEquals(new byte[168],BrickGridUpload.packFaceTextures(java.util.List.of(entry)));
        assertEquals(64,BrickGridUpload.packPaletteEntries(java.util.List.of(entry)).length);
    }
    @Test void optionalTargetHonoursDeclarationAndCompileGate() {
        var targets = new java.util.LinkedHashMap<String,dev.icehunter.fornax.pack.TargetSpec>();
        var graph = new dev.icehunter.fornax.pack.GraphSpec(targets,java.util.List.of());
        assertFalse(dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph,java.util.Map.of()).isEnabledBufferTarget(VoxelFaceTexture.TARGET));
        targets.put(VoxelFaceTexture.TARGET,new dev.icehunter.fornax.pack.TargetSpec(VoxelFaceTexture.TARGET,null,0,false,"DETAIL != 0",dev.icehunter.fornax.pack.graph.TargetBasis.RENDER,dev.icehunter.fornax.pack.graph.TargetKind.BUFFER));
        graph = new dev.icehunter.fornax.pack.GraphSpec(targets,java.util.List.of());
        assertFalse(dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph,java.util.Map.of("DETAIL",0)).isEnabledBufferTarget(VoxelFaceTexture.TARGET));
        assertTrue(dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph,java.util.Map.of("DETAIL",1)).isEnabledBufferTarget(VoxelFaceTexture.TARGET));
    }

    @Test void allCardinalFacesUseDocumentedLocalAxes() {
        for (Direction face : Direction.values()) {
            Vector3f[] positions = new Vector3f[4];
            for (int corner = 0; corner < 4; corner++) {
                float s = corner & 1, t = (corner >> 1) & 1;
                float n = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                positions[corner] = switch (face.getAxis()) {
                    case X -> new Vector3f(n,s,t); case Y -> new Vector3f(s,n,t); case Z -> new Vector3f(s,t,n);
                };
            }
            var quad = new BakedQuad(positions[0],positions[1],positions[3],positions[2],
                    UVPair.pack(0,0),UVPair.pack(1,0),UVPair.pack(1,1),UVPair.pack(0,1),face,
                    new BakedQuad.MaterialInfo(null,ChunkSectionLayer.CUTOUT,null,-1,true,0));
            int[] words = VoxelFaceTexture.mapping(quad,face,-1);
            assertEquals(0x03ffffff,words[0]);
            assertEquals(1f,Float.intBitsToFloat(words[3]));
            assertEquals(1f,Float.intBitsToFloat(words[6]));
        }
    }
    @Test void nonAffineUvCannotBeAdvertisedAsExact() {
        var q = north(-1,false);
        var bent = new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                q.packedUV0(),q.packedUV1(),UVPair.pack(.8f,.75f),q.packedUV3(),q.direction(),q.materialInfo());
        assertEquals(0,VoxelFaceTexture.mapping(bent,Direction.NORTH,-1)[0]);
    }

    @Test void translucentSurfaceRequiresTransmissionInsteadOfAnOpaqueMapping() {
        var q = north(-1,false);
        var glass = new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),
                new BakedQuad.MaterialInfo(null,ChunkSectionLayer.TRANSLUCENT,null,-1,true,0));
        assertEquals(0,VoxelFaceTexture.mapping(glass,Direction.NORTH,-1)[0]);
    }

    @Test void everyOldAlphaByteIsDiscardedWithoutChangingRgbOrFlags() {
        for (int alpha = 0; alpha <= 255; alpha++) {
            int[] words = VoxelFaceTexture.mapping(north(0,false),Direction.NORTH,(alpha << 24) | 0x1234ab);
            assertEquals(0x011234ab, words[0]);
        }
    }

    @Test void allRgbByteValuesSurviveTheHeaderWithoutChangingFlags() {
        for (int channel = 0; channel <= 255; channel++) {
            for (int shift : new int[]{0,8,16}) {
                int rgb = channel << shift;
                int[] words = VoxelFaceTexture.mapping(north(0,false),Direction.NORTH,0xff000000 | rgb);
                assertEquals(0x01000000 | rgb, words[0]);
            }
        }
    }

    @Test void uvFloatBitsStayRawThroughMappingAndLittleEndianUpload() throws Exception {
        var q = north(0,false);
        var quad = new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                UVPair.pack(-0.0f,.25f),UVPair.pack(.5f,.25f),
                UVPair.pack(.5f,.75f),UVPair.pack(-0.0f,.75f),q.direction(),q.materialInfo());
        int[] words = VoxelFaceTexture.mapping(quad,Direction.NORTH,0xee1234ab);
        // Float bits for -0, 1/4, 1/2, +0, +0, 1/2. Header: usable bit plus RGB unchanged.
        int[] expected = {0x011234ab,0x80000000,0x3e800000,0x3f000000,0,0,0x3f000000};
        assertArrayEquals(expected, words);
        int[] faces = new int[42];
        System.arraycopy(words,0,faces,2*7,7); // NORTH is face two in the six-face order.
        var entry = new SectionPalette.Entry(VoxelShapeKind.FULL,List.of(),new int[6],0,false,0,
                false,SectionPalette.NO_UV_RECT,0,0,faces);
        byte[] packed = BrickGridUpload.packFaceTextures(List.of(entry,entry));
        assertEquals(336,packed.length); // Two entries times six faces times seven words times four bytes.
        ByteBuffer bytes = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        for (int entryIndex = 0; entryIndex < 2; entryIndex++) {
            for (int word = 0; word < expected.length; word++) {
                assertEquals(expected[word],bytes.getInt((entryIndex*42+2*7+word)*4));
            }
        }
        // If set, save these bytes for the software Vulkan checker; they come from the real code.
        String fixture = System.getenv("VOXEL_FACE_FIXTURE");
        if (fixture != null) Files.write(Path.of(fixture),Arrays.copyOf(packed,VoxelFaceTexture.BYTES_PER_SLOT));
    }

    @Test void uvMantissaBitsThatDoNotFitHalfFloatsArePreserved() {
        var q = north(0,false);
        var quad = new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                UVPair.pack(.1f,.2f),UVPair.pack(.4f,.2f),
                UVPair.pack(.4f,.8f),UVPair.pack(.1f,.8f),q.direction(),q.materialInfo());
        // Float bits for .1, .2 and the .4-.1 / .8-.2 differences.
        assertArrayEquals(new int[]{0x011234ab,0x3dcccccd,0x3e4ccccd,0x3e99999a,0,0,0x3f19999a},
                VoxelFaceTexture.mapping(quad,Direction.NORTH,0x801234ab));
    }

    @Test void legacyEightWordFaceEntriesAreRejectedByTheirActualSize() {
        var error = assertThrows(IllegalArgumentException.class, () -> new SectionPalette.Entry(
                VoxelShapeKind.FULL,List.of(),new int[6],0,false,0,false,SectionPalette.NO_UV_RECT,0,0,
                new int[48])); // Six faces times eight words.
        assertTrue(error.getMessage().contains("42"));
        assertTrue(error.getMessage().contains("48"));
    }

}
