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
                assertEquals(kind == VoxelShapeKind.FULL ? VoxelFaceTexture.OPAQUE_COVERAGE : 0, faces.textureWords()[14]);
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
            assertEquals(0x01ffffff | VoxelFaceTexture.OPAQUE_COVERAGE, faces.textureWords()[14]);
        }
    }

    @Test
    void staticOverflowPagesRetainFullSourceEligibilityAndEncodeTheirExactPage() {
        try (SpriteContents contents = contents()) {
            for (int page = 0; page <= 3; page++) {
                TextureAtlasSprite sprite = page == 0 ? new TestSprite(contents)
                        : dev.icehunter.fornax.atlas.BlockAtlasGhostSprite.spilled(
                                TextureAtlas.LOCATION_BLOCKS, contents, 256, page, 32, 64, 0);
                var quads = new java.util.ArrayList<BakedQuad>();
                for (Direction face : Direction.values()) quads.add(fullFace(sprite, face));
                List<BlockStateModelPart> parts = List.of(new BlockStateModelPart() {
                    public List<BakedQuad> getQuads(Direction face) {
                        return quads.stream().filter(q -> q.direction() == face).toList();
                    }
                    public boolean useAmbientOcclusion() { return true; }
                    public Material.Baked particleMaterial() { return null; }
                    public int materialFlags() { return 0; }
                });
                var faces = VoxelFaceTexture.packSources(parts, VoxelShapeKind.FULL, -1, index(sprite, 0));
                assertEquals(42, faces.textureWords().length, "the six-face ABI stride stays unchanged");
                for (Direction face : Direction.values()) {
                    int base = face.get3DDataValue() * 7;
                    assertEquals(page, faces.textureWords()[base] >>> 27 & 3);
                    assertTrue(faces.summaries().get(face.get3DDataValue()).supported());
                    // Sprite UVs stay in base/ghost space; page-aware consumers perform the remap.
                    assertEquals(sprite.getU0(), Float.intBitsToFloat(faces.textureWords()[base+1]));
                    assertEquals(sprite.getV0(), Float.intBitsToFloat(faces.textureWords()[base+2]));
                }
                var builder = new VoxelSourceEvidence.Builder();
                builder.add(true, 15, faces.summaries());
                builder.addCell(true, 0);
                var evidence = builder.finish(false);
                assertEquals(6, evidence.eligibleFaces());
                assertEquals(0, evidence.unknownMask(0));
            }
        }
    }

    @Test
    void animatedGhostWithoutAnOverflowCopyStillCannotClaimStaticSourceCoverage() {
        try (SpriteContents contents = contents()) {
            var ghost = dev.icehunter.fornax.atlas.BlockAtlasGhostSprite.animated(
                    TextureAtlas.LOCATION_BLOCKS, contents, 256, 0, 0, 0);
            var faces = VoxelFaceTexture.packSources(List.of(part(List.of(north(ghost, false)))),
                    VoxelShapeKind.FULL, -1, index(ghost, 0));
            assertEquals(0, faces.textureWords()[14] >>> 27 & 3);
            assertEquals(MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE, faces.summaries().get(2).flags());
            assertTrue(faces.summaries().get(2).unknown());
        }
    }

    private static BakedQuad fullFace(TextureAtlasSprite sprite, Direction face) {
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        Vector3f[] vertices = new Vector3f[4];
        int[][] st = {{0,0},{1,0},{1,1},{0,1}};
        for (int i=0; i<4; i++) {
            float u=st[i][0], v=st[i][1];
            vertices[i] = switch (face.getAxis()) {
                case X -> new Vector3f(plane,u,v);
                case Y -> new Vector3f(u,plane,v);
                case Z -> new Vector3f(u,v,plane);
            };
        }
        return new BakedQuad(vertices[0],vertices[1],vertices[2],vertices[3],
                UVPair.pack(sprite.getU0(),sprite.getV0()), UVPair.pack(sprite.getU1(),sprite.getV0()),
                UVPair.pack(sprite.getU1(),sprite.getV1()), UVPair.pack(sprite.getU0(),sprite.getV1()), face,
                new BakedQuad.MaterialInfo(sprite,ChunkSectionLayer.SOLID,null,-1,true,0));
    }

    @Test void zeroIntrinsicFoliageCanBeCertifiedBeforeGeometryFlagsButEveryQuadMustBeKnownNonpositive() {
        try (var contents = contents()) {
            var sprite = new TestSprite(contents);
            var dark = new MaterialSourceIndex.Summary(0, 1, 1, 0, 0, 0);
            var parts = List.of(part(List.of(north(sprite, true), north(sprite, false))));
            var source = VoxelFaceTexture.packSources(parts, VoxelShapeKind.PARTIAL, -1,
                    new MaterialSourceIndex(Map.of(sprite, dark)));
            assertTrue(source.materialsKnownNonpositive());
            var evidence = new VoxelSourceEvidence.Builder();
            evidence.add(true, 0, source.summaries(), source.materialsKnownNonpositive());
            var result = evidence.finish(false);
            assertTrue(result.knownZeroSource(0));
            assertEquals(4, result.paletteBytes());
            assertTrue(result.unknownMask(0) != 0, "diagnostic unsupported facts remain unchanged");
            var positive = VoxelFaceTexture.packSources(parts, VoxelShapeKind.PARTIAL, -1, index(sprite, 0));
            assertFalse(positive.materialsKnownNonpositive());
            for (int flag : new int[]{MaterialSourceIndex.ANIMATED, MaterialSourceIndex.UNREADABLE,
                    MaterialSourceIndex.UNKNOWN_SPRITE, MaterialSourceIndex.NO_ATLAS}) {
                var unknown = VoxelFaceTexture.packSources(parts, VoxelShapeKind.PARTIAL, -1,
                        new MaterialSourceIndex(Map.of(sprite, MaterialSourceIndex.unavailable(flag))));
                assertFalse(unknown.materialsKnownNonpositive());
            }
            var missing = VoxelFaceTexture.packSources(parts, VoxelShapeKind.PARTIAL, -1,
                    new MaterialSourceIndex(Map.of(sprite, MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP))));
            assertTrue(missing.materialsKnownNonpositive());
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
