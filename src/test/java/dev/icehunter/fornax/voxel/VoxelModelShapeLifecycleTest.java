package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Quadrant;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelModelShapeLifecycleTest {
    @Test void actualFaceBakeryPreservesTheExactGridAndWindingForEveryCardinalRotation() {
        try (var pixels = new Pixels()) {
            var material = new BakedQuad.MaterialInfo(new Sprite(pixels),ChunkSectionLayer.SOLID,null,-1,true,0);
            ModelBaker.Interner interner = new ModelBaker.Interner() {
                public Vector3fc vector(Vector3fc value) { return value; }
                public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo value) { return value; }
            };
            for (var turn : Quadrant.values()) {
                var quads = new ArrayList<BakedQuad>();
                int[][] bounds = {{6,0,6,10,16,10},{7,6,0,9,9,9},{7,12,0,9,15,9}};
                for (int box = 0; box < bounds.length; box++) {
                    int[] b = bounds[box];
                    for (var face : Direction.values()) if (box == 0 || face != Direction.SOUTH) {
                        quads.add(FaceBakery.bakeQuad(interner,new Vector3f(b[0],b[1],b[2]),new Vector3f(b[3],b[4],b[5]),
                                new CuboidFace.UVs(0,0,16,16),Quadrant.R0,material,face,
                                BlockModelRotation.get(Quadrant.fromXYAngles(Quadrant.R0,turn)),null));
                    }
                }
                assertNotNull(VoxelModelShape.reconstruct(List.of(VoxelModelShapeTest.part(quads))), "runtime bake " + turn);
            }
        }
    }

    @Test void modelPublicationAndAtlasRetirementClearCachedOpacityProof() {
        try (var pixels = new Pixels()) {
            var sprite = new Sprite(pixels); var quads = new ArrayList<BakedQuad>();
            for (var q : VoxelModelShapeTest.fence(1)) quads.add(new BakedQuad(q.position0(),q.position1(),q.position2(),q.position3(),
                    q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),q.direction(),
                    new BakedQuad.MaterialInfo(sprite,ChunkSectionLayer.CUTOUT,null,-1,true,0)));
            var parts = List.of(VoxelModelShapeTest.part(quads));
            assertNotNull(VoxelModelShape.reconstruct(parts));
            VoxelHarvestLifecycle.onBlockAtlasRetired();
            pixels.image.setPixel(0,0,0);
            VoxelHarvestLifecycle.onModelsPublished();
            assertNull(VoxelModelShape.reconstruct(parts));
            pixels.image.setPixel(0,0,-1);
            VoxelHarvestLifecycle.onBlockAtlasRetired();
            VoxelHarvestLifecycle.onModelsPublished();
            assertNotNull(VoxelModelShape.reconstruct(parts));
        } finally { VoxelHarvestLifecycle.onModelsPublished(); }
    }

    private static final class Pixels extends SpriteContents implements SpriteContentsAccessor {
        final NativeImage image;
        Pixels() { this(new NativeImage(2,2,false)); }
        private Pixels(NativeImage image) {
            super(Identifier.fromNamespaceAndPath("test","model_shape"),new FrameSize(2,2),image);
            this.image=image; image.fillRect(0,0,2,2,-1);
        }
        public NativeImage fornax$originalImage(){return image;}
        public NativeImage[] fornax$byMipLevel(){return new NativeImage[]{image};}
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents){super(TextureAtlas.LOCATION_BLOCKS,contents,2,2,0,0,0);}
    }
}
