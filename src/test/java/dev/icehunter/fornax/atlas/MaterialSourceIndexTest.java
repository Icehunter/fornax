package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialSourceIndexTest {
    @Test
    void oneWeakCornerTexelRemainsAnAuthoredCandidateWithoutCenterSampling() {
        try (NativeImage image = image(8, 8, 0xff112233)) {
            image.setPixel(0, 0, 0x01123456);
            var summary = MaterialSourceIndex.summarize(image);
            assertEquals(64, summary.texelCount());
            assertEquals(63, summary.unprovidedTexels());
            assertEquals(1, summary.positiveTexels());
            assertEquals(0x01123456, summary.maxEmissionArgb());
            assertTrue(summary.authoredCandidate());
            assertTrue(summary.supported());
            assertEquals(0x01123456, image.getPixel(0, 0));
        }
    }

    @Test
    void authoredZeroAndUnprovidedEmissionRemainDifferentEvidence() {
        try (NativeImage image = image(2, 2, 0x00112233)) {
            image.setPixel(1, 1, 0xff445566);
            var summary = MaterialSourceIndex.summarize(image);
            assertEquals(3, summary.zeroTexels());
            assertEquals(1, summary.unprovidedTexels());
            assertEquals(0, summary.positiveTexels());
            assertFalse(summary.authoredCandidate());
        }
    }

    @Test
    void maximumEmissionRetainsRawMaterialBytesAndExcludesTheSentinel() {
        try (NativeImage image = image(2, 2, 0xffffffff)) {
            image.setPixel(0, 0, 0xfe1beffa);
            image.setPixel(1, 0, 0x10112233);
            var summary = MaterialSourceIndex.summarize(image);
            assertEquals(0xfe1beffa, summary.maxEmissionArgb());
            assertEquals(2, summary.positiveTexels());
            assertEquals(2, summary.unprovidedTexels());
        }
    }

    @Test
    void unsupportedFlagRetainsCandidateEvidenceWithoutCallingItSupported() {
        var summary = new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01112233)
                .withFlags(MaterialSourceIndex.CROPPED_UV);
        assertTrue(summary.authoredCandidate());
        assertFalse(summary.supported());
        assertEquals(0x01112233, summary.maxEmissionArgb());
    }

    @Test
    void rebindRejectsOldSpriteIdentitiesAndAdvancesOnlyTheCpuGeneration() {
        try (SpriteContents contents = new SpriteContents(id(), new FrameSize(2, 2), image(2, 2, -1),
                Optional.empty(), List.of(), Optional.empty())) {
            var oldSprite = new TestSprite(contents);
            var newSprite = new TestSprite(contents);
            var summary = new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01112233);
            var oldIndex = new MaterialSourceIndex(Map.of(oldSprite, summary));
            var newIndex = oldIndex.rebind(List.of(newSprite));
            assertNotEquals(oldIndex.generation(), newIndex.generation());
            assertEquals(summary, oldIndex.lookup(oldSprite));
            assertEquals(summary, newIndex.lookup(newSprite));
            assertEquals(MaterialSourceIndex.UNKNOWN_SPRITE, newIndex.lookup(oldSprite).flags());
            assertEquals(MaterialSourceIndex.UNKNOWN_SPRITE, oldIndex.lookup(newSprite).flags());
        }
    }

    @Test
    void absentAtlasCarriesNoValidGenerationOrMaterialEvidence() {
        assertEquals(0, MaterialSourceIndex.EMPTY.generation());
        assertEquals(MaterialSourceIndex.NO_ATLAS, MaterialSourceIndex.EMPTY.lookup(null).flags());
    }

    private static NativeImage image(int width, int height, int argb) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        image.fillRect(0, 0, width, height, argb);
        return image;
    }

    private static Identifier id() {
        return Identifier.fromNamespaceAndPath("test", "block/source");
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents) {
            super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0, 0);
        }
    }
}
