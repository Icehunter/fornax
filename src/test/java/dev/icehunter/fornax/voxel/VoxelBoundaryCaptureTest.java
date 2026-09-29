package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelBoundaryCaptureTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static final List<BakedQuad> CUBE = VoxelModelShapeTest.cuboid(0,0,0,16,16,16,null);

    @Test void emittedBlockAtlasRoutesByAtlasIdAndRetainsMeasuredBlueAndGreen() {
        assertNotEquals(QuadAtlas.BLOCK.getId(), QuadAtlas.BLOCK.getTextureLocation(),
                "AtlasManager identifiers and texture locations are distinct public keys");
        // Measured uniform resource colors: alpha 55, blue (0,57,166), green (0,179,16).
        for (int argb : new int[]{0x370039a6, 0x3700b310}) {
            try (var pixels = new Pixels(argb)) {
                var sprite = new Sprite(pixels);
                var atlases = java.util.Map.of(QuadAtlas.BLOCK.getId(), sprite);
                var capture = new VoxelBoundaryCapture.Capture(quad -> VoxelAtlasLookup.atlas(quad.atlas(), id -> {
                    TextureAtlasSprite found = atlases.get(id);
                    if (found == null) throw new IllegalArgumentException("Unknown atlas ID: " + id);
                    return found;
                }), -1);
                var emitter = IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
                for (var quad : CUBE) fill(emitter,quad,ChunkSectionLayer.TRANSLUCENT,-1).emit();
                var result = capture.result();
                assertNotNull(result, "A stitched block atlas must resolve before geometry and color reduction");
                for (int face = 0; face < 6; face++) {
                    assertEquals(argb, result.faceColors()[face]);
                    assertTrue((result.words()[face * 7] & VoxelFaceTexture.CLOSED_BOX_BOUNDARY) != 0);
                }
            }
        }
    }

    @Test void rejectedCaptureRetainsTheOriginalLookupFailureForBoundedDiagnostics() {
        var failure = new IllegalArgumentException("Unknown atlas ID fixture");
        var capture = new VoxelBoundaryCapture.Capture(quad -> { throw failure; }, -1);
        var emitter = IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
        for (var quad : CUBE) fill(emitter,quad,ChunkSectionLayer.TRANSLUCENT,-1).emit();
        assertNull(capture.result());
        assertSame(failure, capture.failure(), "A failed capture must expose the concrete lookup exception");
    }

    @Test void oneEmissionCapturesSixFacesIncludingCulledNeighbourAndFinalTransformUv() {
        try (var pixels = new Pixels(0x372468ac)) {
            var sprite = new Sprite(pixels);
            var capture = new VoxelBoundaryCapture.Capture(q -> sprite, -1);
            var raster = new ArrayList<Direction>();
            QuadEmitter emitter = IndigoRenderer.INSTANCE.quadEmitter(q -> raster.add(q.cullFace()));
            var calls = new AtomicInteger();
            VoxelBoundaryCapture.observe(emitter, face -> face == Direction.EAST, earlyCull -> {
                calls.incrementAndGet();
                for (Direction face : Direction.values()) assertFalse(earlyCull.test(face));
                emitter.pushTransform(q -> {
                    if (q.cullFace() == Direction.NORTH) q.cullFace(Direction.EAST);
                    if (q.lightFace() == Direction.NORTH)
                        for (int v=0;v<4;v++) q.uv(v,q.u(v)+0.125f,q.v(v));
                    return true;
                });
                try { for (var q : CUBE) fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).emit(); }
                finally { emitter.popTransform(); }
            },capture);
            assertEquals(1,calls.get());
            assertEquals(4,raster.size(), "Both the original east face and transformed north cull face are omitted");
            var result = capture.result();
            assertNotNull(result);
            assertEquals(VoxelShapeKind.FULL,result.kind);
            for(int face=0;face<6;face++) assertEquals(0xe0000000,result.words()[face*7]&0xe0000000);
            assertEquals(0.125f,Float.intBitsToFloat(result.words()[Direction.NORTH.get3DDataValue()*7+1]));
            assertEquals(0x372468ac,result.faceColors()[0], "Colour comes from the emitted sprite");
            int[] changed=result.words();changed[0]=0;
            assertTrue(result.words()[0]!=0, "Published words are immutable");
        }
    }

    @Test void nullCullFacesArePreservedAndFailurePopsTheObserverWithoutPublishingPartialFacts() {
        try(var pixels=new Pixels(-1)) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var delivered=new AtomicInteger();
            QuadEmitter emitter=IndigoRenderer.INSTANCE.quadEmitter(q->delivered.incrementAndGet());
            assertThrows(IllegalStateException.class,()->VoxelBoundaryCapture.observe(emitter,face->{
                assertNotNull(face);return true;
            },early->{
                fill(emitter,CUBE.getFirst(),ChunkSectionLayer.TRANSLUCENT,-1).cullFace(null).emit();
                throw new IllegalStateException("fixture emission failed");
            },capture));
            assertEquals(1,delivered.get());
            assertNull(capture.result());
            // A leftover transform would reject this face and increase the failed capture.
            fill(emitter,CUBE.getFirst(),ChunkSectionLayer.TRANSLUCENT,-1).emit();
            assertEquals(2,delivered.get());
        }
    }

    @Test void partialPaneRetainsActualBoundsAndUnknownAtlasOrNonuniformColorCannotCertify() {
        try(var pixels=new Pixels(0x55123456)) {
            var sprite=new Sprite(pixels);
            var pane=VoxelModelShapeTest.cuboid(7,0,0,9,16,16,null);
            var good=capture(pane,sprite,ChunkSectionLayer.TRANSLUCENT,-1);
            assertNotNull(good);
            assertEquals(VoxelShapeKind.PARTIAL,good.kind);
            assertEquals(List.of(new VoxelShapeClassifier.PackedBox(7,0,0,9,16,16)),good.boxes);
            var missing=new VoxelBoundaryCapture.Capture(q->null,-1);
            QuadEmitter emitter=IndigoRenderer.INSTANCE.quadEmitter(missing::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).emit();
            assertNull(missing.result());
            var gradient=new VoxelBoundaryCapture.Capture(q->sprite,-1);
            emitter=IndigoRenderer.INSTANCE.quadEmitter(gradient::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).color(0,0xff000000).emit();
            assertNull(gradient.result());
            var unsupportedTint=new VoxelBoundaryCapture.Capture(q->sprite,-1);
            emitter=IndigoRenderer.INSTANCE.quadEmitter(unsupportedTint::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).tintIndex(1).emit();
            assertNull(unsupportedTint.result(), "Higher tint layers are not represented by the sidecar");
        }
    }

    @Test void fullSolidBackingRetainsOpaqueCoverageAndPartialSolidBackingIsUnsupported() {
        try(var pixels=new Pixels(-1)) {
            var sprite=new Sprite(pixels);
            for(boolean full:new boolean[]{true,false}) {
                var capture=new VoxelBoundaryCapture.Capture(q->sprite,-1);
                var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
                var model=full?CUBE:VoxelModelShapeTest.cuboid(7,0,0,9,16,16,null);
                for(var q:model) {
                    fill(emitter,q,ChunkSectionLayer.SOLID,-1).emit();
                    fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).emit();
                }
                var result=capture.result();
                if(full) {
                    assertNotNull(result);
                    for(int face=0;face<6;face++)assertTrue((result.words()[face*7]&VoxelFaceTexture.OPAQUE_COVERAGE)!=0);
                } else assertNull(result);
            }
        }
    }

    @Test void capturedCutoutCubeRetainsLegacyAlphaMappingAndFinalVertexTint() {
        try(var pixels=new Pixels(0x552468ac)) {
            var result=capture(CUBE,new Sprite(pixels),ChunkSectionLayer.CUTOUT,0xff80ffff);
            assertNotNull(result);
            assertEquals(0x4380ffff,result.words()[0]);
            assertEquals(0x551268ac,result.faceColors()[0]);
        }
    }

    @Test void subdividedOpaqueBackingCannotLoseItsCoverageBehindTransparentOverlays() {
        try(var pixels=new Pixels(-1)) {
            var capture=new VoxelBoundaryCapture.Capture(q->new Sprite(pixels),-1);
            var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
            for(var q:CUBE)fill(emitter,q,ChunkSectionLayer.TRANSLUCENT,-1).emit();
            for(var q:VoxelModelShapeTest.cuboid(0,0,0,8,16,16,null))
                if(q.direction()==Direction.NORTH)fill(emitter,q,ChunkSectionLayer.SOLID,-1).emit();
            assertNull(capture.result());
        }
    }

    @Test void closedSubdividedCubeHasGeometryProofWithoutInventingAnAffineMap() {
        try(var pixels=new Pixels(0x55123456)) {
            var quads=new ArrayList<>(CUBE);quads.removeIf(q->q.direction()==Direction.NORTH);
            for(var q:VoxelModelShapeTest.cuboid(0,0,0,8,16,16,null))if(q.direction()==Direction.NORTH)quads.add(q);
            for(var q:VoxelModelShapeTest.cuboid(8,0,0,16,16,16,null))if(q.direction()==Direction.NORTH)quads.add(q);
            var result=capture(quads,new Sprite(pixels),ChunkSectionLayer.TRANSLUCENT,-1);
            assertNotNull(result);
            int header=result.words()[Direction.NORTH.get3DDataValue()*7];
            assertTrue((header&VoxelFaceTexture.CLOSED_BOX_BOUNDARY)!=0);
            assertEquals(0,header&VoxelFaceTexture.BOUNDARY_MAPPING);
        }
    }

    @Test void generationWorldOwnerNewerBuildAndExplicitClearRejectOldSnapshots() {
        var store=new VoxelBoundaryCapture.Store();
        var owner=SectionPos.of(0,0,0);var world=new Object();
        var state=Blocks.GLASS.defaultBlockState();
        var old=store.begin(0,owner,world,10);old.add(BlockPos.ZERO,state,null);
        var latest=store.begin(0,owner,world,10);latest.add(BlockPos.ZERO,state,null);
        assertFalse(store.publish(old));assertTrue(store.publish(latest));
        assertNotNull(store.snapshot(0,owner,world,10).cell(0,state));
        assertNull(store.snapshot(0,owner,new Object(),10));
        assertNull(store.snapshot(0,owner,world,11));
        assertNull(store.snapshot(0,SectionPos.of(1,0,0),world,10));
        assertNull(store.snapshot(0,owner,world,10).cell(0,Blocks.STONE.defaultBlockState()));
        store.clear();assertFalse(store.publish(latest));assertNull(store.snapshot(0,owner,world,10));
    }

    @Test void contextualPaletteVariantsKeepDistinctMappingsColorsAndFailClosedAtCapacity() {
        var original=entry();
        var entries=new ArrayList<>(List.of(original));
        var copied=new ArrayList<Integer>();
        var variants=new VoxelPaletteShapes(entries,copied::add);
        int[] firstWords=original.faceTextureWords().clone();firstWords[1]=Float.floatToRawIntBits(.25f);
        var first=new VoxelBoundaryCapture.Boundary(VoxelShapeKind.FULL,List.of(),firstWords,new int[]{1,1,1,1,1,1},false,new float[4],0f);
        int[] nextWords=firstWords.clone();nextWords[1]=Float.floatToRawIntBits(.5f);
        var next=new VoxelBoundaryCapture.Boundary(VoxelShapeKind.FULL,List.of(),nextWords,new int[]{2,2,2,2,2,2},false,new float[4],0f);
        int a=variants.boundary(0,first),b=variants.boundary(0,next);
        assertTrue(a!=b);assertEquals(a,variants.boundary(0,first));
        assertArrayEquals(first.faceColors(),entries.get(a).faceColors());
        assertArrayEquals(next.words(),entries.get(b).faceTextureWords());
        assertEquals(List.of(0,0),copied);
        var full=new ArrayList<>(Collections.nCopies(SectionHarvester.maxPaletteEntries(),original));
        var capped=new VoxelPaletteShapes(full,unused->{});
        assertEquals(0,capped.boundary(0,next));assertTrue(capped.overflowed());
        assertEquals(0,full.getFirst().faceTextureWords()[0]&VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
        assertTrue((original.faceTextureWords()[0]&VoxelFaceTexture.CLOSED_BOX_BOUNDARY)!=0);
    }

    @Test void missingCaptureRequestsOneRemeshAndSectionCompletionDoesNotRequestAnother() {
        var store=new VoxelBoundaryCapture.Store();var owner=SectionPos.of(0,0,0);var world=new Object();
        store.request(0,owner,world,1);store.request(0,owner,world,1);
        assertNotNull(store.poll());assertNull(store.poll());
        var scope=store.begin(0,owner,world,1);assertTrue(store.publish(scope));
        store.request(0,owner,world,1);assertNull(store.poll());
        store.request(0,SectionPos.of(1,0,0),world,1);assertNotNull(store.poll());
        store.clear();store.request(0,owner,world,2);assertNotNull(store.poll());
    }

    @Test void changedTextureEvidencePreservesIntrinsicEmissionAndRemovesAuthoredClaims() {
        var evidence=new VoxelSourceEvidence.Builder();
        var faces=Collections.nCopies(6,dev.icehunter.fornax.atlas.MaterialSourceIndex.unavailable(
                dev.icehunter.fornax.atlas.MaterialSourceIndex.MISSING_MAP));
        evidence.add(true,12,faces);evidence.copyUnknown(0);
        evidence.addCell(true,1);
        var result=evidence.finish(false);
        assertEquals(12,result.intrinsicEmission(1));
        assertEquals(0,result.supportedMask(1));assertEquals(0,result.authoredMask(1));
        assertEquals(63,result.unknownMask(1));assertEquals(0,result.eligibleFaces());
        assertFalse(result.knownZeroSource(1));
    }

    @Test void leavingWindowRetiresOldGeometryAndItsInFlightCompletionBeforeReturning() {
        var store=new VoxelBoundaryCapture.Store();var owner=SectionPos.of(0,0,0);var world=new Object();
        var old=store.begin(0,owner,world,1);assertTrue(store.publish(old));
        store.retireSlots(List.of(0));
        assertNull(store.snapshot(0,owner,world,1));assertFalse(store.publish(old));
        // The displaced section may never mesh, while neighbours of the old owner change.
        store.request(0,SectionPos.of(9,0,0),world,1);assertNotNull(store.poll());
        store.retireSlots(List.of(0));
        store.request(0,owner,world,1);assertNotNull(store.poll());
        assertNull(store.snapshot(0,owner,world,1));
        var current=store.begin(0,owner,world,1);assertTrue(store.publish(current));
        assertNotNull(store.snapshot(0,owner,world,1));
    }

    @Test void transformedTranslucentSpriteCannotInheritFallbackCutoutRectOrExtinction() {
        try(var pixels=new Pixels(0x55336699)) {
            var actual=capture(CUBE,new Sprite(pixels),ChunkSectionLayer.TRANSLUCENT,-1);
            assertNotNull(actual);
            var fallback=new SectionPalette.Entry(VoxelShapeKind.FULL,List.of(),new int[6],0,true,0,true,
                    new float[]{.2f,.3f,.4f,.5f},1.75f,FaceSealResolver.ALL,new int[42]);
            var entries=new ArrayList<>(List.of(fallback));
            int index=new VoxelPaletteShapes(entries,unused->{}).boundary(0,actual);
            var result=entries.get(index);
            assertFalse(result.cutout());assertArrayEquals(new float[4],result.uvRect());assertEquals(0f,result.extinction());
            assertEquals(0x55336699,result.faceColors()[0]);
        }
    }

    @Test void capturedCutoutMaterialRecomputesItsLegacyRectAndDensityFromObservedQuads() {
        try(var pixels=new Pixels(0xc0336699)) {
            var actual=capture(CUBE,new Sprite(pixels),ChunkSectionLayer.CUTOUT,-1);
            assertNotNull(actual);
            var entries=new ArrayList<>(List.of(entry()));
            int index=new VoxelPaletteShapes(entries,unused->{}).boundary(0,actual);
            var result=entries.get(index);
            assertTrue(result.cutout());assertArrayEquals(new float[]{0f,0f,1f,1f},result.uvRect());
            // Six unit-area opaque cutout faces contribute S/4 = 6/4 per occupied block.
            assertEquals(1.5f,result.extinction());
        }
    }

    @Test void failedLatestBuildRetriesButSupersededOrRetiredFailuresDoNot() {
        var store=new VoxelBoundaryCapture.Store();var owner=SectionPos.of(0,0,0);var world=new Object();
        store.request(0,owner,world,1);assertNotNull(store.poll());
        var failed=store.begin(0,owner,world,1);
        store.retry(failed);assertNotNull(store.poll());assertNull(store.poll());
        var old=store.begin(0,owner,world,1);var current=store.begin(0,owner,world,1);
        store.retry(old);assertNull(store.poll());assertTrue(store.publish(current));
        store.retireSlots(List.of(0));store.retry(current);assertNull(store.poll());
    }

    private static VoxelBoundaryCapture.Boundary capture(List<BakedQuad> quads,TextureAtlasSprite sprite,
                                                         ChunkSectionLayer layer,int color) {
        var capture=new VoxelBoundaryCapture.Capture(q->sprite,-1);
        var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
        for(var q:quads)fill(emitter,q,layer,color).emit();
        return capture.result();
    }
    private static QuadEmitter fill(QuadEmitter emitter,BakedQuad quad,ChunkSectionLayer layer,int color) {
        emitter.clear().atlas(QuadAtlas.BLOCK).chunkLayer(layer).nominalFace(quad.direction()).cullFace(quad.direction())
                .color(color,color,color,color).tintIndex(-1);
        for(int v=0;v<4;v++) {
            var p=quad.position(v);
            emitter.pos(v,p.x(),p.y(),p.z()).uv(v,UVPair.unpackU(quad.packedUV(v)),UVPair.unpackV(quad.packedUV(v)));
        }
        return emitter;
    }
    private static SectionPalette.Entry entry() {
        int[] words=new int[42];for(int i=0;i<6;i++)words[i*7]=0xe0ffffff;
        return new SectionPalette.Entry(VoxelShapeKind.FULL,List.of(),new int[6],0,true,0,false,new float[4],0,
                FaceSealResolver.ALL,words);
    }
    private static final class Pixels extends SpriteContents implements SpriteContentsAccessor {
        final NativeImage image;
        Pixels(int argb) { this(image(argb)); }
        private Pixels(NativeImage image) {
            super(Identifier.fromNamespaceAndPath("test","captured_boundary"),new FrameSize(2,2),image);this.image=image;
        }
        private static NativeImage image(int argb) { var image=new NativeImage(2,2,false);image.fillRect(0,0,2,2,argb);return image; }
        public NativeImage fornax$originalImage() { return image; }
        public NativeImage[] fornax$byMipLevel() { return new NativeImage[]{image}; }
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS,contents,2,2,0,0,0); }
    }
}
