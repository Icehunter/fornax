package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelFaceSourceTest {
    @Test
    void sourceInventoryLeavesTheExistingFaceTextureWordsBitIdentical() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            var quad = north(sprite, false);
            var parts = List.of(part(List.of(quad)));
            var index = index(sprite, 0);
            var faces = VoxelFaceTexture.packSources(parts, VoxelShapeKind.FULL, -1, index);
            assertArrayEquals(VoxelFaceTexture.pack(parts, -1), faces.textureWords());
            assertEquals(index.generation(), faces.atlasGeneration());
            assertEquals(6, faces.summaries().size());
            assertTrue(faces.summaries().get(2).supported());
            assertTrue(faces.summaries().get(2).authoredCandidate());
        }
    }

    @Test
    void croppedFaceRetainsRawPositiveEvidenceButCannotAdvertiseWholeSpriteCoverage() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            var faces = VoxelFaceTexture.packSources(List.of(part(List.of(north(sprite, true)))),
                    VoxelShapeKind.FULL, -1, index(sprite, 0));
            var summary = faces.summaries().get(2);
            assertTrue(summary.authoredCandidate());
            assertFalse(summary.supported());
            assertEquals(MaterialSourceIndex.CROPPED_UV, summary.flags());
            assertEquals(0x01112233, summary.maxEmissionArgb());
        }
    }

    @Test
    void stackedAndPartialFacesKeepTheirUnsupportedEvidence() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            var quad = north(sprite, false);
            for (var kind : List.of(VoxelShapeKind.FULL, VoxelShapeKind.PARTIAL)) {
                var faces = VoxelFaceTexture.packSources(List.of(part(List.of(quad, quad))),
                        kind, -1, index(sprite, 0));
                var summary = faces.summaries().get(2);
                assertEquals(0, faces.textureWords()[14]);
                assertTrue(summary.authoredCandidate());
                assertEquals(MaterialSourceIndex.UNSUPPORTED_GEOMETRY, summary.flags());
            }
        }
    }

    @Test
    void animatedSourceNeverBecomesStaticFirstFrameEvidence() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            var faces = VoxelFaceTexture.packSources(List.of(part(List.of(north(sprite, false)))),
                    VoxelShapeKind.FULL, -1, index(sprite, MaterialSourceIndex.ANIMATED));
            assertFalse(faces.summaries().get(2).supported());
            assertEquals(MaterialSourceIndex.ANIMATED, faces.summaries().get(2).flags());
            int[] words = faces.textureWords();
            words[14] = 0;
            assertEquals(0x01ffffff, faces.textureWords()[14]);
        }
    }

    private static MaterialSourceIndex index(TextureAtlasSprite sprite, int flags) {
        return new MaterialSourceIndex(Map.of(sprite,
                new MaterialSourceIndex.Summary(flags, 1, 0, 1, 0, 0x01112233)));
    }

    private static SpriteContents contents() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
        image.fillRect(0, 0, 2, 2, -1);
        return new SpriteContents(Identifier.fromNamespaceAndPath("test", "block/emitter"),
                new FrameSize(2, 2), image);
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents) {
            super(TextureAtlas.LOCATION_BLOCKS, contents, 2, 2, 0, 0, 0);
        }
    }

    private static BakedQuad north(TextureAtlasSprite sprite, boolean crop) {
        float u0 = crop ? (sprite.getU0() + sprite.getU1()) * .5f : sprite.getU0();
        return new BakedQuad(new Vector3f(0,0,0), new Vector3f(1,0,0),
                new Vector3f(1,1,0), new Vector3f(0,1,0),
                UVPair.pack(u0,sprite.getV0()), UVPair.pack(sprite.getU1(),sprite.getV0()),
                UVPair.pack(sprite.getU1(),sprite.getV1()), UVPair.pack(u0,sprite.getV1()), Direction.NORTH,
                new BakedQuad.MaterialInfo(sprite,ChunkSectionLayer.SOLID,null,-1,true,0));
    }

    private static BlockStateModelPart part(List<BakedQuad> quads) {
        return new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) { return face == Direction.NORTH ? quads : List.of(); }
            public boolean useAmbientOcclusion() { return true; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
    }
}
