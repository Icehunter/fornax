package dev.icehunter.fornax.atlas;

import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LabPbrAnimationMetadataTest {
    @Test
    void publicAlbedoMetadataDefinesFrameOrderDurationsColumnsAndInterpolation() {
        AnimationMetadataSection section = new AnimationMetadataSection(
                Optional.of(List.of(
                        new AnimationFrame(3, Optional.of(2)),
                        new AnimationFrame(1, Optional.empty()))),
                Optional.of(16), Optional.of(16), 5, true);

        LabPbrAnimationMetadata metadata = LabPbrAnimationMetadata.fromSection(
                section, 32, 32, 16, 16);

        assertNotNull(metadata);
        assertEquals(2, metadata.frameColumns());
        assertTrue(metadata.interpolate());
        assertEquals(List.of(new LabPbrAnimationState.Frame(3, 2),
                        new LabPbrAnimationState.Frame(1, 5)), metadata.frames());
    }

    @Test
    void omittedFrameListUsesEveryFrameInRowMajorOrder() {
        AnimationMetadataSection section = new AnimationMetadataSection(
                Optional.empty(), Optional.of(16), Optional.of(16), 3, false);
        LabPbrAnimationMetadata metadata = LabPbrAnimationMetadata.fromSection(
                section, 32, 32, 16, 16);

        assertNotNull(metadata);
        assertEquals(List.of(0, 1, 2, 3),
                metadata.frames().stream().map(LabPbrAnimationState.Frame::index).toList());
    }

    @Test
    void invalidFrameIndexOrSheetLayoutKeepsSidecarStatic() {
        AnimationMetadataSection invalidIndex = new AnimationMetadataSection(
                Optional.of(List.of(new AnimationFrame(4))), Optional.of(16), Optional.of(16),
                1, false);
        assertNull(LabPbrAnimationMetadata.fromSection(invalidIndex, 32, 32, 16, 16));
        assertNull(LabPbrAnimationMetadata.fromSection(invalidIndex, 31, 32, 16, 16));
    }

    @Test
    void metadataInspectionDistinguishesStaticValidAndInvalidOwners() {
        AnimationMetadataSection valid = new AnimationMetadataSection(
                Optional.empty(), Optional.of(16), Optional.of(16), 3, false);
        AnimationMetadataSection invalid = new AnimationMetadataSection(
                Optional.of(List.of(new AnimationFrame(4))), Optional.of(16), Optional.of(16),
                1, false);
        AnimationMetadataSection singleFrame = new AnimationMetadataSection(
                Optional.of(List.of(new AnimationFrame(0))), Optional.of(16), Optional.of(16),
                1, false);

        assertEquals(LabPbrAnimationMetadata.Status.STATIC,
                LabPbrAnimationMetadata.classify(Optional.empty(), 32, 32, 16, 16).status());
        assertEquals(LabPbrAnimationMetadata.Status.ANIMATED,
                LabPbrAnimationMetadata.classify(Optional.of(valid), 32, 32, 16, 16).status());
        assertEquals(LabPbrAnimationMetadata.Status.INVALID,
                LabPbrAnimationMetadata.classify(Optional.of(invalid), 32, 32, 16, 16).status());
        assertEquals(LabPbrAnimationMetadata.Status.STATIC,
                LabPbrAnimationMetadata.classify(
                        Optional.of(singleFrame), 16, 16, 16, 16).status());
    }

    @Test
    void noInexactPrivateAnimationAccessorCanReappear() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/SpriteContentsAccessor.java"));
        assertFalse(source.contains("@Accessor(\"animatedTexture\")"));
        assertFalse(source.contains("fornax$animatedTexture"));
    }

    @Test
    void frameClockMatchesDurationsAndInterpolationFractions() {
        LabPbrAnimationState state = new LabPbrAnimationState(List.of(
                new LabPbrAnimationState.Frame(0, 4),
                new LabPbrAnimationState.Frame(1, 2)), true);
        assertEquals(0, state.initialFrameIndex());
        assertEquals(0.25f, state.tick().blend(), 0.0f);
        assertEquals(0.50f, state.tick().blend(), 0.0f);
        assertEquals(0.75f, state.tick().blend(), 0.0f);
        LabPbrAnimationState.Sample changed = state.tick();
        assertEquals(1, changed.currentFrameIndex());
        assertEquals(0.0f, changed.blend(), 0.0f);
    }
}
