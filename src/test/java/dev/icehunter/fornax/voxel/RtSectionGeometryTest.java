package dev.icehunter.fornax.voxel;

import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtSectionGeometryTest {
    @Test void customModelCannotEnterLiveEmissionOrPublishItsFallbackParts() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var model = new VoxelModelShapeEmissionTest.EmittedModel(List.of(quad(false,0,1)));
        var builder = new RtSectionGeometry.Builder();
        builder.addModel(0,0,model,net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState(),
                new net.minecraft.core.BlockPos(-31,70,43));
        assertFalse(builder.finish().exact());
        assertEquals(0,builder.finish().mesh().triangleCount());
        assertEquals(0,model.emissions);
        assertEquals(0,model.collections);
    }

    @Test void knownBakedVariantPreservesOffsetAndMirroredUvsWithoutLiveEmission() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var state = net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState();
        var at = new net.minecraft.core.BlockPos(-31,70,43);
        var quads = new net.minecraft.client.resources.model.geometry.QuadCollection.Builder()
                .addUnculledFace(quad(true,0,1)).build();
        var part = new net.minecraft.client.resources.model.SimpleModelWrapper(quads,true,null);
        var model = new net.minecraft.client.renderer.block.dispatch.SingleVariant(part);
        var builder = new RtSectionGeometry.Builder();
        builder.addModel(0,0,model,state,at);
        assertTrue(builder.finish().exact());
        var mesh = builder.finish().mesh();
        assertEquals(2,mesh.triangleCount()); // One baked quad is a two-triangle fan.
        assertEquals(1f,Float.intBitsToFloat(mesh.primitiveWords()[1]));
        assertEquals(0f,Float.intBitsToFloat(mesh.primitiveWords()[3]));
        assertEquals(state.getOffset(at).x,mesh.vertices()[0],1e-7);
        assertEquals(state.getOffset(at).y,mesh.vertices()[1],1e-7);
        assertEquals(state.getOffset(at).z,mesh.vertices()[2],1e-7);
    }

    @Test void customModelInsideWeightedVariantsCannotCertifyItsCollectedFallbackParts() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var custom = new VoxelModelShapeEmissionTest.EmittedModel(List.of(quad(false,0,1)));
        var weighted = new net.minecraft.client.renderer.block.dispatch.WeightedVariants(
                net.minecraft.util.random.WeightedList.<net.minecraft.client.renderer.block.dispatch.BlockStateModel>of(custom));
        var builder = new RtSectionGeometry.Builder();
        builder.addModel(0,0,weighted,net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState(),
                net.minecraft.core.BlockPos.ZERO);
        assertFalse(builder.finish().exact(), "A vanilla outer model does not certify a custom child");
        assertEquals(0,builder.finish().mesh().triangleCount());
        assertEquals(0,custom.emissions);
        assertEquals(0,custom.collections, "Do not inspect an unproven child on the harvest thread");
    }

    @Test void customPartInsideSingleVariantCannotCertifyItsCollectedFallbackQuads() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var model = new net.minecraft.client.renderer.block.dispatch.SingleVariant(
                VoxelModelShapeTest.part(List.of(quad(false,0,1))));
        var builder = new RtSectionGeometry.Builder();
        builder.addModel(0,0,model,net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState(),
                net.minecraft.core.BlockPos.ZERO);
        assertFalse(builder.finish().exact(), "Only known baked parts certify the final rendered geometry");
        assertEquals(0,builder.finish().mesh().triangleCount());
    }

    @Test void realStateOffsetUsesTheAbsolutePositionIncludingNegativeSections() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var state=net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState();
        var a=new net.minecraft.core.BlockPos(-31,70,43);
        var b=new net.minecraft.core.BlockPos(49,70,-53);
        assertNotEquals(state.getOffset(a),state.getOffset(b),"fixture must expose position dependence");
        for (var at:List.of(a,b)) {
            var builder=new RtSectionGeometry.Builder();
            builder.add(0,0,List.of(quad(false,0,1)),state,at);
            float[] vertices=builder.finish().mesh().vertices();
            var expected=state.getOffset(at);
            assertEquals(expected.x,vertices[0],1e-7);
            assertEquals(expected.y,vertices[1],1e-7);
            assertEquals(expected.z,vertices[2],1e-7);
        }
    }

    @Test void displacedQuadRetainsVerticesBeyondItsVoxelAndItsSection() {
        var builder = new RtSectionGeometry.Builder();
        builder.add(15, 3, List.of(quad(false, 0f, 1f)), new Vec3(0.25, -0.125, 0.25));
        var mesh = builder.finish().mesh();
        assertTrue(builder.finish().exact());
        assertArrayEquals(new float[]{15.25f,-0.125f,0.25f,16.25f,-0.125f,1.25f,
                16.25f,0.875f,1.25f}, java.util.Arrays.copyOf(mesh.vertices(),9));
    }

    @Test void mirroredAndCroppedAsymmetricAlphaKeepsActualBakedCoordinates() {
        var normal = new RtSectionGeometry.Builder();
        var mirrored = new RtSectionGeometry.Builder();
        normal.add(0,0,List.of(quad(false,0.2f,0.8f)),Vec3.ZERO);
        mirrored.add(0,0,List.of(quad(true,0.2f,0.8f)),Vec3.ZERO);
        int[] a = normal.finish().mesh().primitiveWords(), b = mirrored.finish().mesh().primitiveWords();
        assertNotEquals(a[1],b[1]);
        assertEquals(0.2f,Float.intBitsToFloat(a[1]),1e-4f);
        assertEquals(0.8f,Float.intBitsToFloat(b[1]),1e-4f);
        // At the first vertex, an asymmetric alpha image opaque for u<0.5 must differ.
        assertTrue(Float.intBitsToFloat(a[1]) < 0.5f);
        assertFalse(Float.intBitsToFloat(b[1]) < 0.5f);
        assertNotEquals(0,a[0] & RtSectionGeometry.EXACT_UV_BIT);
    }

    @Test void unsupportedSpillDoesNotPretendToBeExact() {
        var b = new RtSectionGeometry.Builder();
        b.add(0,0,List.of(quad(false,0,1)),new Vec3(-2,0,0));
        assertFalse(b.finish().exact());
        assertEquals(0,b.finish().mesh().triangleCount());
    }

    @Test void reverseWindingKeepsItsOwnAlphaAndRasterCulling() {
        var q=quad(false,0,1); var reversed=quad(true,0,1);
        reversed=new BakedQuad(q.position3(),q.position2(),q.position1(),q.position0(),
                reversed.packedUV3(),reversed.packedUV2(),reversed.packedUV1(),reversed.packedUV0(),
                q.direction(),q.materialInfo());
        var b=new RtSectionGeometry.Builder(); b.add(0,0,List.of(q,reversed),Vec3.ZERO);
        assertTrue(b.finish().exact());
        var mesh=b.finish().mesh();
        assertEquals(4,mesh.triangleCount());
        assertEquals(1,mesh.primitiveWords()[7]);
        assertEquals(1,mesh.primitiveWords()[23]);
        assertNotEquals(mesh.primitiveWords()[1],mesh.primitiveWords()[17]);
    }

    @Test void nonAffineUvMappingRequiresRasterFallback() {
        var q=quad(false,0,1);
        var warped=new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                q.packedUV0(),q.packedUV1(),UVPair.pack(0.6f,0.2f),q.packedUV3(),q.direction(),q.materialInfo());
        var b=new RtSectionGeometry.Builder(); b.add(0,0,List.of(warped),Vec3.ZERO);
        assertFalse(b.finish().exact(),"quad diagonal choices must not change alpha coverage");
    }

    static BakedQuad quad(boolean mirror,float low,float high) {
        float left=mirror?high:low,right=mirror?low:high;
        return new BakedQuad(new Vector3f(0,0,0),new Vector3f(1,0,1),new Vector3f(1,1,1),new Vector3f(0,1,0),
                UVPair.pack(left,1),UVPair.pack(right,1),UVPair.pack(right,0),UVPair.pack(left,0),Direction.NORTH,
                new BakedQuad.MaterialInfo(null,ChunkSectionLayer.CUTOUT,null,-1,true,0));
    }
}
