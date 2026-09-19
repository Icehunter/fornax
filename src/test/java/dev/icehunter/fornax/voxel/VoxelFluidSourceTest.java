package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.atlas.BlockAtlasGhostSprite;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fluid has no model quad, so its six faces come from its own sprite or it casts nothing. */
class VoxelFluidSourceTest {
    @Test
    void everyFaceMapsTheWholeSpriteOnce() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            int[] words = VoxelFluidFace.words(new VoxelFluidFace.Fluid(sprite, false), -1);
            assertEquals(VoxelFaceTexture.ENTRY_WORDS, words.length);
            for (Direction face : Direction.values()) {
                int base = face.get3DDataValue() * VoxelFaceTexture.FACE_WORDS;
                assertEquals(1, words[base] >>> 24 & 1, "the face carries a usable map");
                assertEquals(0, words[base] >>> 25 & 1, "a solid layer does not alpha-test");
                assertEquals(0xffffff, words[base] & 0xffffff);
                assertEquals(sprite.getU0(), Float.intBitsToFloat(words[base + 1]));
                assertEquals(sprite.getV0(), Float.intBitsToFloat(words[base + 2]));
                assertEquals(sprite.getU1() - sprite.getU0(), Float.intBitsToFloat(words[base + 3]));
                assertEquals(0f, Float.intBitsToFloat(words[base + 4]));
                assertEquals(0f, Float.intBitsToFloat(words[base + 5]));
                assertEquals(sprite.getV1() - sprite.getV0(), Float.intBitsToFloat(words[base + 6]));
            }
        }
    }

    @Test
    void anAnimatedFluidCastsThroughItsOwnLightLevel() {
        try (SpriteContents contents = contents()) {
            var sprite = new TestSprite(contents);
            var faces = VoxelFluidFace.packSources(new VoxelFluidFace.Fluid(sprite, false), -1,
                    index(sprite, MaterialSourceIndex.ANIMATED));
            assertEquals(6, faces.summaries().size());
            assertFalse(faces.materialsKnownNonpositive());
            var builder = new VoxelSourceEvidence.Builder();
            builder.add(true, 15, faces.summaries());
            builder.addCell(true, 0);
            var evidence = builder.finish(false);
            assertEquals(63, evidence.intrinsicOnlyMask(0), "six faces, each proven apart from its frames");
            assertEquals(63, evidence.unknownMask(0));
            assertFalse(evidence.knownZeroSource(0));
        }
    }

    @Test
    void aCutoutLayerKeepsItsAlphaTest() {
        try (SpriteContents contents = contents()) {
            int[] words = VoxelFluidFace.words(
                    new VoxelFluidFace.Fluid(new TestSprite(contents), true), -1);
            assertEquals(3, words[0] >>> 24 & 3, "valid, and alpha-tested");
        }
    }

    @Test
    void anAnimatedGhostWithNoOverflowCopyCannotClaimItsSprite() {
        try (SpriteContents contents = contents()) {
            var ghost = BlockAtlasGhostSprite.animated(TextureAtlas.LOCATION_BLOCKS, contents, 256, 0, 0, 0);
            var faces = VoxelFluidFace.packSources(new VoxelFluidFace.Fluid(ghost, false), -1,
                    index(ghost, MaterialSourceIndex.ANIMATED));
            assertTrue(faces.summaries().get(0).unknown());
            assertEquals(MaterialSourceIndex.ANIMATED | MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE,
                    faces.summaries().get(0).flags());
            var builder = new VoxelSourceEvidence.Builder();
            builder.add(true, 15, faces.summaries());
            builder.addCell(true, 0);
            assertEquals(0, builder.finish(false).intrinsicOnlyMask(0));
        }
    }

    private static MaterialSourceIndex index(TextureAtlasSprite sprite, int flags) {
        return new MaterialSourceIndex(Map.of(sprite, MaterialSourceIndex.unavailable(flags)));
    }

    private static SpriteContents contents() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
        image.fillRect(0, 0, 2, 2, -1);
        return new SpriteContents(Identifier.fromNamespaceAndPath("test", "block/fluid"),
                new FrameSize(2, 2), image);
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents) {
            super(TextureAtlas.LOCATION_BLOCKS, contents, 2, 2, 0, 0, 0);
        }
    }
}
