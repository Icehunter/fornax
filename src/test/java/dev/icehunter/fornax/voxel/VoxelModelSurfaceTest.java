package dev.icehunter.fornax.voxel;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VoxelModelSurfaceTest {
    private static BakedQuad quad(ChunkSectionLayer layer, boolean diagonal, boolean opposite) {
        return new BakedQuad(new Vector3f(0,0,opposite?1:0), new Vector3f(0,1,opposite?1:0),
                new Vector3f(1,1,diagonal?(opposite?0:1):0), new Vector3f(1,0,diagonal?(opposite?0:1):0),
                0,0,0,0,Direction.NORTH,new BakedQuad.MaterialInfo(null,layer,null,-1,true,0));
    }
    private static BlockStateModelPart part(List<BakedQuad> culled, List<BakedQuad> unculled) {
        return new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face==null?unculled:face==Direction.NORTH?culled:List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
    }
    @Test void untaggedCutoutModelIsDetectedFromItsMaterialLayer() {
        var surface=FaceColorResolver.surface(List.of(part(List.of(),List.of(quad(ChunkSectionLayer.CUTOUT,false,false)))));
        assertTrue(surface.cutout()); assertFalse(surface.cross());
    }
    @Test void opaqueAndTranslucentModelsAreNotMisclassifiedAsCutouts() {
        for(var layer:List.of(ChunkSectionLayer.SOLID,ChunkSectionLayer.TRANSLUCENT))
            assertFalse(FaceColorResolver.surface(List.of(part(List.of(quad(layer,false,false)),List.of()))).cutout());
    }
    @Test void twoVerticalDiagonalsAreCrossGeometryButOnePlaneIsNot() {
        var a=quad(ChunkSectionLayer.CUTOUT,true,false); var b=quad(ChunkSectionLayer.CUTOUT,true,true);
        assertTrue(FaceColorResolver.surface(List.of(part(List.of(),List.of(a,b)))).cross());
        assertFalse(FaceColorResolver.surface(List.of(part(List.of(),List.of(a)))).cross());
        assertFalse(FaceColorResolver.surface(List.of(part(List.of(a),List.of(a,b)))).cross());
    }
    @Test void unculledQuadsContributeOnlyToTheirNormalDirection() {
        var parts=List.of(part(List.of(),List.of(quad(ChunkSectionLayer.SOLID,false,false))));
        assertEquals(0xff246842,FaceColorResolver.resolve(parts,Direction.NORTH,q->0xff246842));
        assertEquals(0,FaceColorResolver.resolve(parts,Direction.SOUTH,q->0xff246842));
    }
    @Test void culledAndUnculledColorsAreBothRepresented() {
        var a=quad(ChunkSectionLayer.SOLID,false,false); var b=quad(ChunkSectionLayer.SOLID,false,false);
        assertEquals(0xff7f007f,FaceColorResolver.resolve(List.of(part(List.of(a),List.of(b))),Direction.NORTH,q->q==a?0xffff0000:0xff0000ff));
    }
    private static BakedQuad withTint(BakedQuad q, int index) {
        return new BakedQuad(q.position0(), q.position1(), q.position2(), q.position3(),
                q.packedUV0(), q.packedUV1(), q.packedUV2(), q.packedUV3(), q.direction(),
                new BakedQuad.MaterialInfo(null, q.materialInfo().layer(), null, index, true, 0));
    }

    @Test void biomeTintAppliesToEligibleQuadsBeforeAveraging() {
        var base = quad(ChunkSectionLayer.SOLID, false, false);
        var overlay = withTint(base, 0);
        // Equal opaque white base and green-tinted overlay average to light green, not solid green.
        assertEquals(0xff7fff7f, FaceColorResolver.resolve(
                List.of(part(List.of(base), List.of(overlay))), Direction.NORTH, 0xff00ff00,
                q -> 0xffffffff));
    }

    @Test void layerZeroTintDoesNotLeakIntoOtherTintLayers() {
        var other = withTint(quad(ChunkSectionLayer.SOLID, false, false), 1);
        assertEquals(0xffabcdef, FaceColorResolver.resolve(
                List.of(part(List.of(other), List.of())), Direction.NORTH, 0xff00ff00,
                q -> 0xffabcdef));
    }

}
