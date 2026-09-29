package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelFaceAppearanceTest {
    private static final List<BakedQuad> CUBE=VoxelModelShapeTest.cuboid(0,0,0,16,16,16,null);
    @BeforeAll static void boot(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void opaqueEmittedUvSurvivesWithoutAnOpticalVolumeCertificate() throws Exception {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.SOLID,-1).emit();
            assertNull(capture.result(),"An opaque-only model does not request an optical boundary certificate");
            int[] words=appearance(capture,index->-1);
            assertNotNull(words,"Opaque rendered faces must publish final appearance independently of optical proof");
            assertEquals(0x05ffffff,words[7]);
            assertEquals(0,words[7]&VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
        }
    }

    @Test void aHigherTintSideLayerCannotDiscardTheResolvedTop() throws Exception {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.SOLID,q.direction()==Direction.NORTH?1:0).emit();
            int[] words=appearance(capture,index->index==1?0xffff80ff:0xff80ffff);
            assertNotNull(words,"Each emitted face resolves its own tint layer");
            assertEquals(0x0580ffff,words[7]);
            assertEquals(0x05ff80ff,words[14]);
        }
    }

    @Test void culledFacesDoNotMultiplyAppearanceOrBorrowUnresolvedFallbackUvs() throws Exception {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var output=IndigoRenderer.INSTANCE.quadEmitter(q->{});
            VoxelBoundaryCapture.observe(output,face->face!=Direction.UP,early->{
                for(var q:CUBE)fill(output,q,ChunkSectionLayer.SOLID,-1).emit();
            },capture);
            int[] words=appearance(capture,index->-1);
            assertNotNull(words);
            assertEquals(0x05ffffff,words[7]);
            for(int face:new int[]{0,2,3,4,5})assertEquals(0,words[face*7],"Hidden material is explicitly unavailable");
        }
    }

    @Test void incompatibleCoplanarOverlayRetainsOpaqueCoverageWithoutInventingUvs() {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            var top=CUBE.stream().filter(q->q.direction()==Direction.UP).findFirst().orElseThrow();
            fill(emitter,top,ChunkSectionLayer.SOLID,-1).emit();
            fill(emitter,top,ChunkSectionLayer.CUTOUT,1).emit();
            int[] words=appearance(capture,index->-1);
            assertEquals(VoxelFaceTexture.OPAQUE_COVERAGE,words[7],"Opaque geometry remains proved when one map cannot compose materials");
        }
    }

    @Test void nonuniformFaceColorDoesNotEraseOpaqueGeometryOrOtherFaceMaterials() {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            for(var q:CUBE) {
                fill(emitter,q,ChunkSectionLayer.SOLID,-1);
                if(q.direction()==Direction.NORTH)emitter.color(0,0xff000000);
                emitter.emit();
            }
            int[] words=appearance(capture,index->-1);
            assertEquals(0x05ffffff,words[7]);
            assertEquals(VoxelFaceTexture.OPAQUE_COVERAGE,words[14]);
        }
    }

    @Test void twoContextualSpritesReachPublishedPaletteBytesWithTheirActualTintLayers() throws Exception {
        try (var pixels = new Pixels()) {
            var state = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            var owner = net.minecraft.core.SectionPos.of(0, 0, 0);
            var world = new Object();
            var store = new VoxelBoundaryCapture.Store();
            var scope = store.begin(0, owner, world, 1);
            for (int cell = 0; cell < 2; cell++) {
                var sprite = new Sprite(pixels, cell * 2);
                var capture = new VoxelBoundaryCapture.Capture(q -> sprite, -1);
                var emitter = IndigoRenderer.INSTANCE.quadEmitter(q -> {});
                final float start = cell * .5f;
                VoxelBoundaryCapture.observe(emitter, face -> face != Direction.UP && face != Direction.NORTH, early -> {
                    for (var q : CUBE) {
                        fill(emitter, q, ChunkSectionLayer.SOLID, q.direction() == Direction.NORTH ? 1 : 0);
                        for (int v = 0; v < 4; v++) emitter.uv(v, emitter.u(v) * .5f + start, emitter.v(v));
                        emitter.emit();
                    }
                }, capture);
                scope.add(new net.minecraft.core.BlockPos(cell, 0, 0), state, null, capture.appearance());
            }
            assertTrue(store.publish(scope));
            var snapshot = store.snapshot(0, owner, world, 1);
            var entries = new java.util.ArrayList<>(List.of(entry()));
            var variants = new VoxelPaletteShapes(entries, unused -> {});
            for (int cell = 0; cell < 2; cell++) {
                var observed = snapshot.cell(cell, state);
                var material = observed.appearance().resolve(layer -> layer == 1 ? 0xffff80ff : 0xff80ffff);
                assertEquals(cell + 1, variants.observed(0, observed.boundary(), material));
            }
            assertEquals(3, entries.size());
            assertEquals(0f, Float.intBitsToFloat(entries.get(1).faceTextureWords()[8]));
            assertEquals(.5f, Float.intBitsToFloat(entries.get(2).faceTextureWords()[8]));
            assertEquals(0x0580ffff, entries.get(1).faceTextureWords()[7]);
            assertEquals(0x05ff80ff, entries.get(2).faceTextureWords()[14]);
            String fixture = System.getenv("FORNAX_CTM_FIXTURE");
            if (fixture != null) {
                byte[] bytes = BrickGridUpload.packFaceTextures(entries);
                java.nio.file.Files.write(java.nio.file.Path.of(fixture),
                        java.util.Arrays.copyOf(bytes, VoxelFaceTexture.bytesPerSlot()));
                java.nio.file.Files.writeString(java.nio.file.Path.of(fixture + ".json"),
                        "{\"entryIndices\":[1,2],\"faceWords\":7,\"entryWords\":42,\"records\":["
                                + java.util.Arrays.toString(entries.get(1).faceTextureWords()) + ","
                                + java.util.Arrays.toString(entries.get(2).faceTextureWords()) + "]}");
            }
        }
    }

    private static int[] appearance(VoxelBoundaryCapture.Capture capture,IntUnaryOperator tints) {
        return capture.appearance().resolve(tints).words();
    }

    @Test void oneCombinedVariantKeepsAppearanceAndAuthoritativeBoundaryProof() throws Exception {
        try(var pixels=new Pixels()) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.SOLID,-1).emit();
            var material=capture.appearance().resolve(index->-1);
            var base=entry();var entries=new java.util.ArrayList<>(List.of(base));
            var variants=new VoxelPaletteShapes(entries,unused->{});
            int[] certified=new int[42];for(int side=0;side<6;side++)certified[side*7]=0xe0ffffff;
            var boundary=new VoxelBoundaryCapture.Boundary(VoxelShapeKind.FULL,List.of(),certified,new int[6],false,new float[4],0);
            int first=observed(variants,0,boundary,material);
            assertEquals(2,entries.size(),"Geometry and material consume one combined variant");
            assertEquals(0x45ffffff,entries.get(first).faceTextureWords()[7]);
            assertEquals(0xff204060,entries.get(first).faceColors()[1]);
            assertEquals(first,observed(variants,0,boundary,material));
            assertEquals(2,entries.size());
        }
    }

    @Test void exhaustedPaletteCannotResurrectTheUnresolvedBaseTexture() throws Exception {
        var entries=new java.util.ArrayList<>(java.util.Collections.nCopies(SectionHarvester.maxPaletteEntries(),entry()));
        var variants=new VoxelPaletteShapes(entries,unused->{});
        int[] words=new int[42];words[7]=0x05ffffff;words[8]=Float.floatToRawIntBits(.5f);
        int result=observed(variants,0,null,new VoxelFaceAppearance.Resolved(words,new int[6]));
        assertEquals(0,result);assertTrue(variants.overflowed());
        for(int side=0;side<6;side++)assertEquals(0,entries.getFirst().faceTextureWords()[side*7]&0x23000000,
                "Capacity failure is material-unavailable, never the static placeholder mapping");
        assertEquals(VoxelShapeKind.FULL,entries.getFirst().shapeKind());
    }

    @Test void visibleRepeatTilesReuseVariantsAcrossTheWholeSurface() throws Exception {
        var entries=new java.util.ArrayList<>(List.of(entry()));
        var variants=new VoxelPaletteShapes(entries,unused->{});
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int[] words=new int[42];words[7]=0x05ffffff;
            words[8]=Float.floatToRawIntBits((x%4)/4f);words[9]=Float.floatToRawIntBits((z%4)/4f);
            observed(variants,0,null,new VoxelFaceAppearance.Resolved(words,new int[6]));
        }
        assertEquals(17,entries.size(),"The 4 by 4 repeat contributes sixteen maps, not 256 cells");
        assertFalse(variants.overflowed());
    }

    private static int observed(VoxelPaletteShapes variants, int base, VoxelBoundaryCapture.Boundary boundary,
                                VoxelFaceAppearance.Resolved appearance) {
        return variants.observed(base, boundary, appearance);
    }
    private static SectionPalette.Entry entry() {
        int[] words=new int[42];for(int side=0;side<6;side++)words[side*7]=0x01ffffff;
        return new SectionPalette.Entry(VoxelShapeKind.FULL,List.of(),new int[6],0,false,0,false,new float[4],0,
                FaceSealResolver.ALL,words);
    }

    static QuadEmitter fill(QuadEmitter emitter,BakedQuad quad,ChunkSectionLayer layer,int tint) {
        emitter.clear().atlas(QuadAtlas.BLOCK).chunkLayer(layer).nominalFace(quad.direction()).cullFace(quad.direction())
                .color(-1,-1,-1,-1).tintIndex(tint);
        for(int v=0;v<4;v++) {
            var p=quad.position(v);
            emitter.pos(v,p.x(),p.y(),p.z()).uv(v,UVPair.unpackU(quad.packedUV(v)),UVPair.unpackV(quad.packedUV(v)));
        }
        return emitter;
    }
    static final class Pixels extends SpriteContents implements SpriteContentsAccessor {
        final NativeImage image;
        Pixels(){this(image());}
        private Pixels(NativeImage image){super(Identifier.fromNamespaceAndPath("test","emitted_appearance"),new FrameSize(2,2),image);this.image=image;}
        private static NativeImage image(){var image=new NativeImage(2,2,false);image.fillRect(0,0,2,2,0xff204060);return image;}
        public NativeImage fornax$originalImage(){return image;}
        public NativeImage[] fornax$byMipLevel(){return new NativeImage[]{image};}
    }
    static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents){super(TextureAtlas.LOCATION_BLOCKS,contents,2,2,0,0,0);}
        Sprite(SpriteContents contents, int x){super(TextureAtlas.LOCATION_BLOCKS,contents,4,2,x,0,0);}
    }
}
