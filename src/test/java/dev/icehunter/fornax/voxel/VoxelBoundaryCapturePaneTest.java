package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelBoundaryCapturePaneTest {
    private static final Direction[] CONNECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    // Numeric model data: a 2/16-wide central post and four flush arms. The touching faces
    // are absent, exactly as in the 26.2 pane multipart templates; no asset code is bundled.
    private static final List<VoxelShapeClassifier.PackedBox> PARTS = List.of(
            new VoxelShapeClassifier.PackedBox(7,0,7,9,16,9),
            new VoxelShapeClassifier.PackedBox(7,0,0,9,16,7),
            new VoxelShapeClassifier.PackedBox(9,0,7,16,16,9),
            new VoxelShapeClassifier.PackedBox(7,0,9,9,16,16),
            new VoxelShapeClassifier.PackedBox(0,0,7,7,16,9));

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest
    @ValueSource(ints={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})
    void actualMultipartExteriorClosesAgainstItsStateShapeWithoutInventedJointFaces(int connections) throws Exception {
        var state=Blocks.GLASS_PANE.defaultBlockState()
                .setValue(BlockStateProperties.NORTH,(connections&1)!=0)
                .setValue(BlockStateProperties.EAST,(connections&2)!=0)
                .setValue(BlockStateProperties.SOUTH,(connections&4)!=0)
                .setValue(BlockStateProperties.WEST,(connections&8)!=0);
        var candidate=VoxelShapeClassifier.classify(state);
        assertEquals(VoxelShapeKind.PARTIAL,candidate.kind());
        var quads=exterior(connections);
        assertTrue(VoxelBoundaryGeometry.certifies(parts(quads),candidate.kind(),candidate.boxes()),
                "The real state candidate must describe the actual exterior, independently of capture");
        try(var pixels=new Pixels()) {
            var capture=capture(new Sprite(pixels),candidate.boxes());
            emit(capture,quads);
            var result=capture.result();
            assertNotNull(result,"Flush-connected bodies form one closed exterior even without individual end caps");
            assertEquals(candidate.boxes(),result.boxes);
            assertEquals(VoxelShapeKind.PARTIAL,result.kind);
            for(int face=0;face<6;face++) assertTrue((result.words()[face*7]&VoxelFaceTexture.CLOSED_BOX_BOUNDARY)!=0);
        }
    }

    @Test void openExteriorAndOversizedSelectionShapeCannotCertify() throws Exception {
        try(var pixels=new Pixels()) {
            var quads=exterior(1);
            quads.removeIf(q->q.direction()==Direction.UP);
            var missing=capture(new Sprite(pixels),List.of(PARTS.get(0),PARTS.get(1)));
            emit(missing,quads);
            assertNull(missing.result());
            var enlarged=capture(new Sprite(pixels),List.of(new VoxelShapeClassifier.PackedBox(0,0,0,16,16,16)));
            emit(enlarged,exterior(1));
            assertNull(enlarged.result(),"Candidate geometry is never authoritative without emitted boundary proof");
        }
    }

    @Test void subdividedExteriorAndImmutableCandidateKeepTheSameClosedUnion() throws Exception {
        try(var pixels=new Pixels()) {
            var candidate=new ArrayList<>(List.of(PARTS.get(0),PARTS.get(1)));
            var capture=capture(new Sprite(pixels),candidate);
            candidate.clear();
            var quads=exterior(1);
            quads.removeIf(q->q.direction()==Direction.EAST && q.position0().z()<7f/16f);
            for(int[] z:new int[][]{{0,3},{3,7}}) {
                for(var q:VoxelModelShapeTest.cuboid(7,0,z[0],9,16,z[1],null))
                    if(q.direction()==Direction.EAST) quads.add(translucent(q));
            }
            emit(capture,quads);
            var result=capture.result();
            assertNotNull(result);
            assertEquals(List.of(PARTS.get(0),PARTS.get(1)),result.boxes);
        }
    }

    @Test void liveEmitterRoutesTheCandidateThroughTheSameCapturePath() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelBoundaryCapture.java"));
        assertTrue(source.contains("VoxelShapeClassifier.classify(state)"));
        assertTrue(source.contains("new Capture(VoxelAtlasLookup::sprite, tint, candidateBoxes)"));
        assertFalse(source.contains("Blocks.GLASS_PANE"),"Candidate use is generic geometry, never a block allowlist");
    }

    @Test void fourWayMixedSpritesKeepMaterialSamplesWithoutInventingAffineMaps() {
        try(var pixels=new Pixels()) {
            var pane=new Sprite(pixels);
            var edge=new Sprite(pixels);
            var quads=texturedExterior(pane,edge);
            int[] words=VoxelFaceTexture.packPartial(parts(quads),0xff123456);
            for(Direction face:Direction.values()) {
                int base=face.get3DDataValue()*7;
                int header=words[base];
                assertEquals(0x02000000,header&0x23000000,"Sample evidence has neither affine-valid bit");
                assertEquals(0,header&VoxelFaceTexture.CLOSED_BOX_BOUNDARY,"Material samples cannot certify geometry");
                assertEquals(0,header&0x01000000,"Legacy visibility and source sampling require this absent bit");
                assertFalse((header&0x07000000)==0x03000000,"Sample-only is not a thin alpha-tested face");
                boolean broad=face.getAxis()!=Direction.Axis.Y;
                assertEquals(broad?0.375f:0.0625f,Float.intBitsToFloat(words[base+1]));
                assertEquals(broad?0.625f:0.0625f,Float.intBitsToFloat(words[base+2]));
                assertEquals(broad?7f/16f:14f/256f,Float.intBitsToFloat(words[base+3]));
                assertEquals(0,words[base+4]);assertEquals(0,words[base+5]);assertEquals(0,words[base+6]);
                assertEquals(0x00ffffff,header&0x00ffffff);
                assertTrue((header&VoxelFaceTexture.TRANSLUCENT_FACE)!=0);
            }
            int[] certified=VoxelFaceTexture.withBoundaryFacts(words,parts(quads),VoxelShapeKind.PARTIAL,PARTS,true);
            for(int face=0;face<6;face++) {
                assertTrue((certified[face*7]&VoxelFaceTexture.CLOSED_BOX_BOUNDARY)!=0);
                assertEquals(0x02000000,certified[face*7]&0x23000000);
            }
        }
    }

    @Test void materialSampleRejectsNonfiniteAndOutOfAtlasUvs() {
        try(var pixels=new Pixels()) {
            var sprite=new Sprite(pixels);
            var quad=exterior(15).getFirst();
            for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-0.01f,1.01f}) {
                var bad=texture(quad,sprite,invalid,invalid,invalid,invalid);
                int[] words=VoxelFaceTexture.packPartial(parts(List.of(bad,bad)), -1);
                int base=quad.direction().get3DDataValue()*7;
                assertEquals(0,words[base]&0x23000000,"Invalid atlas coordinates provide no usable material fact");
                for(int word=1;word<7;word++) assertEquals(0,words[base+word]);
            }
        }
    }

    @Test void compatibleBoundaryMapRetainsAffineEncoding() {
        try(var pixels=new Pixels()) {
            var quad=texture(exterior(0).getFirst(),new Sprite(pixels),0.25f,0.5f,0.5f,0.75f);
            int[] words=VoxelFaceTexture.packPartial(parts(List.of(quad,quad)), -1);
            int header=words[quad.direction().get3DDataValue()*7];
            assertEquals(VoxelFaceTexture.BOUNDARY_MAPPING,header&0x23000000);
            assertEquals(0,header&VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
        }
    }

    private static List<BakedQuad> texturedExterior(TextureAtlasSprite pane,TextureAtlasSprite edge) {
        var result=new ArrayList<BakedQuad>();
        for(var quad:exterior(15)) {
            var rectangle=VoxelBoundaryGeometry.rectangle(quad);
            int area=(rectangle.s1()-rectangle.s0())*(rectangle.t1()-rectangle.t0());
            boolean broad=quad.direction().getAxis()!=Direction.Axis.Y && area>32;
            result.add(broad?texture(quad,pane,0.25f,0.5f,0.5f,0.75f):texture(quad,edge,0f,0f,0.125f,0.125f));
        }
        return result;
    }

    private static BakedQuad texture(BakedQuad quad,TextureAtlasSprite sprite,float u0,float v0,float u1,float v1) {
        return new BakedQuad(quad.position0(),quad.position1(),quad.position2(),quad.position3(),
                UVPair.pack(u0,v0),UVPair.pack(u0,v1),UVPair.pack(u1,v1),UVPair.pack(u1,v0),quad.direction(),
                new BakedQuad.MaterialInfo(sprite,ChunkSectionLayer.TRANSLUCENT,null,-1,true,0));
    }

    private static VoxelBoundaryCapture.Capture capture(TextureAtlasSprite sprite,List<VoxelShapeClassifier.PackedBox> boxes) throws Exception {
        Function<QuadView,TextureAtlasSprite> sprites=ignored->sprite;
        // Keep the behavior fixture runnable when the optional candidate API is absent.
        // The existing capture then reaches the same assertion with no candidate geometry.
        Constructor<?> constructor;
        try { constructor=VoxelBoundaryCapture.Capture.class.getDeclaredConstructor(Function.class,int.class,List.class); }
        catch(NoSuchMethodException absent) { return new VoxelBoundaryCapture.Capture(sprites,-1); }
        return (VoxelBoundaryCapture.Capture)constructor.newInstance(sprites,-1,boxes);
    }

    private static List<BakedQuad> exterior(int connections) {
        var result=new ArrayList<BakedQuad>();
        var post=PARTS.get(0);
        for(var quad:VoxelModelShapeTest.cuboid(post.minX(),post.minY(),post.minZ(),post.maxX(),post.maxY(),post.maxZ(),null)) {
            int bit=-1;
            for(int i=0;i<4;i++) if(quad.direction()==CONNECTIONS[i]) bit=i;
            if(bit<0 || (connections&(1<<bit))==0) result.add(translucent(quad));
        }
        for(int i=0;i<4;i++) if((connections&(1<<i))!=0) {
            var arm=PARTS.get(i+1);
            for(var quad:VoxelModelShapeTest.cuboid(arm.minX(),arm.minY(),arm.minZ(),arm.maxX(),arm.maxY(),arm.maxZ(),null))
                if(quad.direction()!=CONNECTIONS[i].getOpposite()) result.add(translucent(quad));
        }
        return result;
    }

    private static BakedQuad translucent(BakedQuad quad) {
        return new BakedQuad(quad.position0(),quad.position1(),quad.position2(),quad.position3(),
                quad.packedUV0(),quad.packedUV1(),quad.packedUV2(),quad.packedUV3(),quad.direction(),
                new BakedQuad.MaterialInfo(null,ChunkSectionLayer.TRANSLUCENT,null,-1,true,0));
    }

    private static List<BlockStateModelPart> parts(List<BakedQuad> quads) {
        return List.of(new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face==null ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return false; }
            public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        });
    }

    private static void emit(VoxelBoundaryCapture.Capture capture,List<BakedQuad> quads) {
        var emitter=IndigoRenderer.INSTANCE.quadEmitter(capture::accept);
        for(var quad:quads) {
            emitter.clear().atlas(QuadAtlas.BLOCK).chunkLayer(ChunkSectionLayer.TRANSLUCENT)
                    .nominalFace(quad.direction()).cullFace(quad.direction()).color(-1,-1,-1,-1).tintIndex(-1);
            for(int v=0;v<4;v++) {
                var p=quad.position(v);
                emitter.pos(v,p.x(),p.y(),p.z()).uv(v,UVPair.unpackU(quad.packedUV(v)),UVPair.unpackV(quad.packedUV(v)));
            }
            emitter.emit();
        }
    }

    private static final class Pixels extends SpriteContents implements SpriteContentsAccessor {
        final NativeImage image;
        Pixels() { this(image()); }
        private Pixels(NativeImage image) {
            super(Identifier.fromNamespaceAndPath("test","joined_boundary"),new FrameSize(2,2),image);this.image=image;
        }
        private static NativeImage image() { var image=new NativeImage(2,2,false);image.fillRect(0,0,2,2,0x370039a6);return image; }
        public NativeImage fornax$originalImage() { return image; }
        public NativeImage[] fornax$byMipLevel() { return new NativeImage[]{image}; }
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS,contents,2,2,0,0,0); }
    }
}
