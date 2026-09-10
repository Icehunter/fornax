package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelModelShapeTest {
    // Measured fence geometry: post 6..10, rails 7..9 wide at heights 6..9 and 12..15,
    // in the palette's existing sixteenth-block units. No model or texture asset is bundled.
    @Test void everyConnectionStateRetainsRailsAndGapsWithinTheExistingBudget() {
        for (int mask = 0; mask < 16; mask++) {
            var boxes = VoxelModelShape.reconstruct(List.of(part(fence(mask))));
            assertNotNull(boxes, "connection mask " + mask);
            assertTrue(boxes.size() <= 5);
            assertTrue(inside(boxes, 8, 4, 8));
            for (int side = 0; side < 4; side++) {
                var solid = turn(new Vector3f(8, 13, 3), side);
                var gap = turn(new Vector3f(8, 10, 3), side);
                assertEquals((mask & 1 << side) != 0, inside(boxes, solid.x(), solid.y(), solid.z()));
                assertFalse(inside(boxes, gap.x(), gap.y(), gap.z()));
            }
        }
    }
    @Test void realRailSurfacesAreOutsideTheirOwnPublishedSolidAfterAnOutwardBias() {
        var boxes = VoxelModelShape.reconstruct(List.of(part(fence(1))));
        assertNotNull(boxes);
        float bias = 16f / 4096f; // Same 1/4096-block ray-start bias measured in palette units.
        assertFalse(inside(boxes, 9 + bias, 13.5f, 3));
        assertFalse(inside(boxes, 8, 15 + bias, 3));
        assertFalse(inside(boxes, 10 + bias, 8, 8));
        assertTrue(inside(boxes, 8, 13.5f, 3), "the rail still casts a shadow");
    }
    @Test void missingEndcapsNeedAnAlreadyClosedVolumeStrictlyBeyondTheirPlane() {
        var rails = cuboid(7, 6, 0, 9, 9, 9, Direction.SOUTH);
        assertNull(VoxelModelShape.reconstruct(List.of(part(rails))), "an open rail alone has no closure proof");
        var touching = new ArrayList<>(cuboid(6, 0, 9, 10, 16, 13, null));
        touching.addAll(rails);
        assertNull(VoxelModelShape.reconstruct(List.of(part(touching))), "touching is not buried");
        var buried = new ArrayList<>(cuboid(6, 0, 6, 10, 16, 10, null));
        buried.addAll(rails);
        assertNotNull(VoxelModelShape.reconstruct(List.of(part(buried))));
    }
    @Test void unexplainedPlanesAndOpenBodiesCannotInventSolidVolumes() {
        var open = new ArrayList<>(cuboid(2, 2, 2, 14, 14, 14, Direction.UP));
        assertNull(VoxelModelShape.reconstruct(List.of(part(open))));
        open.addAll(cuboid(1, 1, 1, 3, 3, 3, null));
        assertNull(VoxelModelShape.reconstruct(List.of(part(open))));
        var extra = new ArrayList<>(cuboid(2, 2, 2, 14, 14, 14, null));
        extra.add(quad(Direction.UP, new Vector3f(0, 15, 0), new Vector3f(16, 15, 16)));
        assertNull(VoxelModelShape.reconstruct(List.of(part(extra))));
    }
    @Test void fractionalRotatedAndBowTieFacesAreUnsupported() {
        var base = cuboid(2, 2, 2, 14, 14, 14, null);
        for (int kind = 0; kind < 3; kind++) {
            var altered = new ArrayList<>(base); var q = altered.getFirst();
            var p0 = new Vector3f(q.position0()); var p1 = new Vector3f(q.position1());
            var p2 = new Vector3f(q.position2()); var p3 = new Vector3f(q.position3());
            if (kind == 0) { p0.x += 1f / 32; p1.x += 1f / 32; p2.x += 1f / 32; p3.x += 1f / 32; }
            if (kind == 1) p1.y += 1f / 16;
            if (kind == 2) { var swap = p1; p1 = p2; p2 = swap; }
            altered.set(0, new BakedQuad(p0,p1,p2,p3,q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),q.materialInfo()));
            assertNull(VoxelModelShape.reconstruct(List.of(part(altered))), "case " + kind);
        }
    }
    @Test void alphaUncertaintyAndTranslucencyCannotBecomeOpaqueCuboids() {
        for (var layer : List.of(ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT)) {
            var quads = new ArrayList<>(cuboid(2, 2, 2, 14, 14, 14, null)); var q = quads.getFirst();
            quads.set(0, new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                    q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),
                    new BakedQuad.MaterialInfo(null,layer,null,-1,true,0)));
            assertNull(VoxelModelShape.reconstruct(List.of(part(quads))));
        }
    }
    @Test void losslessMergingDoesNotFillTheNinthDisjointBoxOrItsGaps() {
        var quads = new ArrayList<BakedQuad>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
            quads.addAll(cuboid(x*4, 0, z*4, x*4+2, 2, z*4+2, null));
        assertNull(VoxelModelShape.reconstruct(List.of(part(quads))), "nine disconnected boxes cannot fit the ABI");
        quads = new ArrayList<>();
        for (int x = 0; x < 3; x++) quads.addAll(cuboid(x*4, 0, 0, x*4+4, 2, 2, null));
        var boxes = VoxelModelShape.reconstruct(List.of(part(quads)));
        assertNotNull(boxes); assertEquals(1, boxes.size());
        assertEquals(12, boxes.getFirst().maxX());
    }
    static List<BakedQuad> fence(int mask) {
        var result = new ArrayList<>(cuboid(6,0,6,10,16,10,null));
        for (int side=0;side<4;side++) if((mask & 1<<side)!=0) {
            for (int y : new int[]{6,12}) for(var q:cuboid(7,y,0,9,y+3,9,Direction.SOUTH)) result.add(turn(q,side));
        }
        return result;
    }
    static List<BakedQuad> cuboid(int x0,int y0,int z0,int x1,int y1,int z1,Direction missing) {
        var out = new ArrayList<BakedQuad>();
        for(var face:Direction.values()) if(face!=missing) {
            var lo=new Vector3f(x0,y0,z0);var hi=new Vector3f(x1,y1,z1);
            int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;
            float plane=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?hi.get(axis):lo.get(axis);
            lo.setComponent(axis,plane);hi.setComponent(axis,plane);out.add(quad(face,lo,hi));
        }
        return out;
    }
    static BakedQuad quad(Direction face,Vector3f lo,Vector3f hi) {
        int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;
        int s=(axis+1)%3,t=(axis+2)%3;var p=new Vector3f[4];int[] order={0,1,3,2};
        for(int i=0;i<4;i++) {p[i]=new Vector3f(lo);p[i].setComponent(s,(order[i]&1)!=0?hi.get(s):lo.get(s));p[i].setComponent(t,(order[i]&2)!=0?hi.get(t):lo.get(t));p[i].div(16);}
        if(face.getAxisDirection()==Direction.AxisDirection.NEGATIVE) {var swap=p[1];p[1]=p[3];p[3]=swap;}
        return new BakedQuad(p[0],p[1],p[2],p[3],UVPair.pack(0,0),UVPair.pack(1,0),UVPair.pack(1,1),UVPair.pack(0,1),face,
                new BakedQuad.MaterialInfo(null,ChunkSectionLayer.SOLID,null,-1,true,0));
    }
    static Vector3f turn(Vector3f point,int n) {var p=new Vector3f(point);for(int i=0;i<n;i++)p.set(16-p.z(),p.y(),p.x());return p;}
    static BakedQuad turn(BakedQuad q,int n) {
        var face=q.direction();for(int i=0;i<n;i++) if(face.getAxis()!=Direction.Axis.Y)face=face.getClockWise();
        return new BakedQuad(turn(new Vector3f(q.position0()).mul(16),n).div(16),turn(new Vector3f(q.position1()).mul(16),n).div(16),
                turn(new Vector3f(q.position2()).mul(16),n).div(16),turn(new Vector3f(q.position3()).mul(16),n).div(16),
                q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),face,q.materialInfo());
    }
    static BlockStateModelPart part(List<BakedQuad> quads) {
        return new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face){return face==null?quads:List.of();}
            public boolean useAmbientOcclusion(){return true;}
            public Material.Baked particleMaterial(){return null;}
            public int materialFlags(){return 0;}
        };
    }
    static boolean inside(List<VoxelShapeClassifier.PackedBox> boxes,float x,float y,float z) {
        return boxes.stream().anyMatch(b->x>b.minX()&&x<b.maxX()&&y>b.minY()&&y<b.maxY()&&z>b.minZ()&&z<b.maxZ());
    }
}
