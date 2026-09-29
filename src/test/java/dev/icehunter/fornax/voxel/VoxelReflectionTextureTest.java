package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VoxelReflectionTextureTest {
    private BlockStateModelPart part(List<BakedQuad> quads) {
        return new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face == null ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
    }
    private BakedQuad cross(boolean falling, boolean reverse, int tint, float uvOffset) {
        // Inset synthetic rectangle: x/z .125..875 and y .25..75, two diagonal planes.
        var p = new Vector3f[] {new Vector3f(.125f,.25f,falling?.875f:.125f),
                new Vector3f(.875f,.25f,falling?.125f:.875f),
                new Vector3f(.875f,.75f,falling?.125f:.875f),
                new Vector3f(.125f,.75f,falling?.875f:.125f)};
        long[] uv = {UVPair.pack(.25f+uvOffset,.75f),UVPair.pack(.75f,.75f),
                UVPair.pack(.75f,.25f),UVPair.pack(.25f+uvOffset,.25f)};
        int[] order = reverse ? new int[]{3,2,1,0} : new int[]{0,1,2,3};
        return new BakedQuad(p[order[0]],p[order[1]],p[order[2]],p[order[3]],
                uv[order[0]],uv[order[1]],uv[order[2]],uv[order[3]],Direction.NORTH,
                new BakedQuad.MaterialInfo(null,ChunkSectionLayer.CUTOUT,null,tint,true,0));
    }
    private List<BakedQuad> crosses() {
        return List.of(cross(false,false,0,0),cross(false,true,0,0),
                cross(true,false,0,0),cross(true,true,0,0));
    }
    private int[] crossWords(List<BakedQuad> quads) {
        return VoxelFaceTexture.packSources(List.of(part(quads)),VoxelShapeKind.CROSS,0xff408020,
                MaterialSourceIndex.EMPTY).textureWords();
    }
    @Test void crossedPlanesPublishAllFourSignedNormalsWithExactTintAndAffineUvs() throws Exception {
        int[] words = crossWords(crosses());
        for (int slot=0;slot<4;slot++) {
            assertEquals(0x03408020,words[slot*7]); // usable, alpha-tested, and the supplied layer-zero tint
            float u0=Float.intBitsToFloat(words[slot*7+1]),v0=Float.intBitsToFloat(words[slot*7+2]);
            float us=Float.intBitsToFloat(words[slot*7+3]),vs=Float.intBitsToFloat(words[slot*7+4]);
            float ut=Float.intBitsToFloat(words[slot*7+5]),vt=Float.intBitsToFloat(words[slot*7+6]);
            assertEquals(.25f,u0+us*.125f+ut*.25f,1e-6f);
            assertEquals(.75f,v0+vs*.125f+vt*.25f,1e-6f);
            assertEquals(.75f,u0+us*.875f+ut*.75f,1e-6f);
            assertEquals(.25f,v0+vs*.875f+vt*.75f,1e-6f);
        }
        assertArrayEquals(new int[14],Arrays.copyOfRange(words,28,42));
        var palette = new SectionPalette.Entry(VoxelShapeKind.CROSS,List.of(),new int[6],0,false,0,
                true,SectionPalette.NO_UV_RECT,0,0,words);
        String fixture=System.getenv("VOXEL_CROSS_FIXTURE");
        if(fixture!=null) Files.write(Path.of(fixture),Arrays.copyOf(
                BrickGridUpload.packFaceTextures(List.of(palette)),VoxelFaceTexture.bytesPerSlot()));
    }
    @Test void conflictingCrossMapsAndUnsupportedTintRemainUnavailable() {
        var conflict=new ArrayList<>(crosses()); conflict.add(cross(false,false,0,.125f));
        assertArrayEquals(new int[42],crossWords(conflict));
        var unsupported=new ArrayList<>(crosses());unsupported.set(0,cross(false,false,1,0));
        assertArrayEquals(new int[42],crossWords(unsupported));
    }
    @Test void planeAndWindingSelectTheirOwnSignedNormalRecord() {
        var quads=List.of(cross(false,false,0,0),cross(false,true,0,.02f),
                cross(true,false,0,.04f),cross(true,true,0,.06f));
        int[] words=crossWords(quads);
        // Cross products: rising/front (-,+), rising/back (+,-), falling/front (+,+), falling/back (-,-).
        float[] expected={.31f,.27f,.25f,.29f};
        for(int slot=0;slot<4;slot++) {
            float u=Float.intBitsToFloat(words[slot*7+1])
                    +Float.intBitsToFloat(words[slot*7+3])*.125f
                    +Float.intBitsToFloat(words[slot*7+5])*.25f;
            assertEquals(expected[slot],u,1e-6f);
        }
    }
    private BakedQuad replaceUv(BakedQuad q, long a, long b, long c, long d) {
        return new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                a,b,c,d,q.direction(),q.materialInfo());
    }
    @Test void nonfiniteNonaffineDegenerateAndOutOfAtlasCrossUvsRemainUnavailable() {
        var q=crosses().getFirst();
        for(var invalid:List.of(
                replaceUv(q,UVPair.pack(Float.NaN,.75f),q.packedUV1(),q.packedUV2(),q.packedUV3()),
                replaceUv(q,UVPair.pack(-.1f,.75f),q.packedUV1(),q.packedUV2(),q.packedUV3()),
                replaceUv(q,q.packedUV0(),q.packedUV1(),UVPair.pack(.6f,.25f),q.packedUV3()),
                replaceUv(q,UVPair.pack(.5f,.5f),UVPair.pack(.5f,.5f),UVPair.pack(.5f,.5f),UVPair.pack(.5f,.5f)))) {
            var quads=new ArrayList<>(crosses());quads.set(0,invalid);
            assertArrayEquals(new int[42],crossWords(quads));
        }
    }
    @Test void bowedOrNonDiagonalCrossGeometryCannotInventAPlaneMap() {
        var q=crosses().getFirst();
        var bowtie=new BakedQuad(q.position0(),q.position2(),q.position1(),q.position3(),
                q.packedUV0(),q.packedUV2(),q.packedUV1(),q.packedUV3(),q.direction(),q.materialInfo());
        var bent=new BakedQuad(new Vector3f(.25f,.25f,.125f),q.position1(),q.position2(),q.position3(),
                q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),q.materialInfo());
        for(var invalid:List.of(bowtie,bent)) {
            var quads=new ArrayList<>(crosses());quads.set(0,invalid);
            assertArrayEquals(new int[42],crossWords(quads));
        }
    }
    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents,int x,int y) { super(TextureAtlas.LOCATION_BLOCKS,contents,4,4,x,y,0); }
    }
    @Test void nonGridCrossBoundsKeepRotatedMirroredAndDistinctSpriteCornersOnCoarseGeometry() throws Exception {
        try(var contents=new SpriteContents(Identifier.fromNamespaceAndPath("test","cross"),
                new FrameSize(2,2),new NativeImage(NativeImage.Format.RGBA,2,2,false))) {
            var quads=new ArrayList<BakedQuad>();
            for(int i=0;i<crosses().size();i++) {
                var source=crosses().get(i);var sprite=new TestSprite(contents,(i&1)*2,(i>>1)*2);
                var points=new Vector3f[4];var uvs=new long[4];
                for(int vertex=0;vertex<4;vertex++) {
                    var p=source.position(vertex);float x=p.x()>.5f?1:0,y=p.y()>.5f?1:0;
                    // These non-grid bounds round outward to x=.125..875 and y=.25..75.
                    points[vertex]=new Vector3f(x==0?.14f:.86f,y==0?.27f:.73f,p.z()>.5f?.86f:.14f);
                    float u=switch(i){case 1->1-x;case 2->y;case 3->1-y;default->x;};
                    float v=i>=2?1-x:y;
                    uvs[vertex]=UVPair.pack(sprite.getU0()+(sprite.getU1()-sprite.getU0())*u,
                            sprite.getV0()+(sprite.getV1()-sprite.getV0())*v);
                }
                quads.add(new BakedQuad(points[0],points[1],points[2],points[3],
                        uvs[0],uvs[1],uvs[2],uvs[3],source.direction(),
                        new BakedQuad.MaterialInfo(sprite,ChunkSectionLayer.CUTOUT,null,0,true,0)));
            }
            int[] words=crossWords(quads);
            int[] slots={2,1,3,0}; // Signed normals of the four fixture windings.
            for(int i=0;i<quads.size();i++) {
                var quad=quads.get(i);int base=slots[i]*7;
                assertEquals(0x03408020,words[base]);
                for(int vertex=0;vertex<4;vertex++) {
                    var p=quad.position(vertex);
                    float x=VoxelShapeClassifier.to16ths(p.x())/16f,y=VoxelShapeClassifier.to16ths(p.y())/16f;
                    float u=Float.intBitsToFloat(words[base+1])+Float.intBitsToFloat(words[base+3])*x
                            +Float.intBitsToFloat(words[base+5])*y;
                    float v=Float.intBitsToFloat(words[base+2])+Float.intBitsToFloat(words[base+4])*x
                            +Float.intBitsToFloat(words[base+6])*y;
                    assertEquals(UVPair.unpackU(quad.packedUV(vertex)),u,1e-6f);
                    assertEquals(UVPair.unpackV(quad.packedUV(vertex)),v,1e-6f);
                }
            }
            String fixture=System.getenv("VOXEL_CROSS_NONGRID_FIXTURE");
            if(fixture!=null) {
                var palette=new SectionPalette.Entry(VoxelShapeKind.CROSS,List.of(),new int[6],0,false,0,
                        true,SectionPalette.NO_UV_RECT,0,0,words);
                Files.write(Path.of(fixture),Arrays.copyOf(BrickGridUpload.packFaceTextures(List.of(palette)),
                        VoxelFaceTexture.bytesPerSlot()));
            }
        }
    }
    @Test void crossRectangleCollapsedByPaletteQuantizationRemainsUnavailable() {
        var quads=new ArrayList<BakedQuad>();
        for(var q:crosses()) {
            var p=new Vector3f[4];
            for(int i=0;i<4;i++) p[i]=new Vector3f(q.position(i)).setComponent(1,q.position(i).y()>.5f?.5002f:.5001f);
            quads.add(new BakedQuad(p[0],p[1],p[2],p[3],q.packedUV0(),q.packedUV1(),
                    q.packedUV2(),q.packedUV3(),q.direction(),q.materialInfo()));
        }
        assertArrayEquals(new int[42],crossWords(quads));
    }
    @Test void matchingCrossDuplicatesPreserveTheSameMap() {
        var quads=new ArrayList<>(crosses());quads.add(quads.getFirst());
        assertArrayEquals(crossWords(crosses()),crossWords(quads));
    }
    @Test void crossSourceEvidenceRemainsUnknownDespiteUsableTextureMapping() {
        var source=VoxelFaceTexture.packSources(List.of(part(crosses())),VoxelShapeKind.CROSS,
                0xff408020,MaterialSourceIndex.EMPTY);
        assertEquals(0x03408020,source.textureWords()[0]);
        for(var summary:source.summaries()) assertFalse(summary.supported());
    }
    private BakedQuad top(float y, ChunkSectionLayer layer) {
        return new BakedQuad(new Vector3f(0,y,0),new Vector3f(0,y,1),new Vector3f(1,y,1),new Vector3f(1,y,0),
                UVPair.pack(.25f,.25f),UVPair.pack(.25f,.5f),UVPair.pack(.5f,.5f),UVPair.pack(.5f,.25f),
                Direction.UP,new BakedQuad.MaterialInfo(null,layer,null,-1,true,0));
    }
    @Test void uniqueOpaqueBackingRetainsItsOwnMappingBeneathStrictlyExteriorLayers() {
        var base=top(1,ChunkSectionLayer.SOLID);
        int[] expected=VoxelFaceTexture.pack(List.of(part(List.of(base))),-1);
        for(float y:new float[]{1.03125f,1.05f}) {
            int[] actual=VoxelFaceTexture.pack(List.of(part(List.of(top(y,ChunkSectionLayer.CUTOUT),base))),-1);
            // Closed-boundary certification can differ because the layer floats outside the cell.
            assertEquals(expected[7]&~VoxelFaceTexture.CLOSED_BOX_BOUNDARY,actual[7]&~VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
            assertArrayEquals(Arrays.copyOfRange(expected,8,14),Arrays.copyOfRange(actual,8,14));
        }
    }
    @Test void coplanarInsideMissingOrAmbiguousBackingsRemainUnavailable() {
        var base=top(1,ChunkSectionLayer.SOLID);
        for(var quads:List.of(List.of(base,top(1,ChunkSectionLayer.CUTOUT)),
                List.of(base,top(.99f,ChunkSectionLayer.CUTOUT)),List.of(base,base),List.of(base,top(1.05f,ChunkSectionLayer.SOLID)),
                List.of(top(1.05f,ChunkSectionLayer.CUTOUT),top(1.1f,ChunkSectionLayer.CUTOUT))))
            assertEquals(0,VoxelFaceTexture.pack(List.of(part(quads)),-1)[7]&0x01000000);
    }
    @Test void backingMappingDoesNotTurnStackedSourceEvidenceIntoWholeSpriteCoverage() {
        var source=VoxelFaceTexture.packSources(List.of(part(List.of(top(1,ChunkSectionLayer.SOLID),
                top(1.05f,ChunkSectionLayer.CUTOUT)))),VoxelShapeKind.FULL,-1,MaterialSourceIndex.EMPTY);
        assertEquals(0x01000000,source.textureWords()[7]&0x01000000);
        assertFalse(source.summaries().get(1).supported());
    }
}
