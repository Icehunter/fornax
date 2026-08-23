package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class LabPbrAtlasProvenanceTest {
    @AfterEach
    void clear() {
        LabPbrAtlasProvenance.clear();
    }

    @Test
    void sourceOwnershipFollowsTheExactDecodedContentsGeneration() {
        Object resourceA = new Object();
        Object resourceB = new Object();
        Object contentsA = new Object();
        Object contentsB = new Object();
        Identifier sourceA = id("pack", "textures/source/a.png");
        Identifier sourceB = id("pack", "textures/source/b.png");

        LabPbrAtlasProvenance.rememberSource(resourceA, sourceA);
        LabPbrAtlasProvenance.rememberSource(resourceB, sourceB);
        LabPbrAtlasProvenance.attachContents(contentsB, resourceB);
        LabPbrAtlasProvenance.attachContents(contentsA, resourceA);

        assertEquals(sourceA, LabPbrAtlasProvenance.resolveContents(contentsA).orElseThrow());
        assertEquals(sourceB, LabPbrAtlasProvenance.resolveContents(contentsB).orElseThrow());
    }

    @Test
    void generatedReplacementAndMissingSourceStayNeutral() {
        Object knownResource = new Object();
        Object replacedKnownContents = new Object();
        Object generatedWinnerContents = new Object();
        Identifier source = id("pack", "textures/source/known.png");

        LabPbrAtlasProvenance.rememberSource(knownResource, source);
        LabPbrAtlasProvenance.attachContents(replacedKnownContents, knownResource);
        LabPbrAtlasProvenance.attachContents(generatedWinnerContents, new Object());

        assertEquals(source,
                LabPbrAtlasProvenance.resolveContents(replacedKnownContents).orElseThrow());
        assertTrue(LabPbrAtlasProvenance.resolveContents(generatedWinnerContents).isEmpty());
        assertTrue(LabPbrAtlasProvenance.resolveContents(new Object()).isEmpty());
    }

    @Test
    void directorySourceReversesItsConfiguredPrefixToTheExactTextureFile() {
        assertEquals(id("pack", "textures/block/nested/stone.png"),
                LabPbrAtlasProvenance.directorySourceFile(
                        "block", "block/", id("pack", "block/nested/stone")).orElseThrow());
        assertTrue(LabPbrAtlasProvenance.directorySourceFile(
                "block", "block/", id("pack", "item/not_from_this_source")).isEmpty());
    }

    @Test
    void concreteSourceWrapperAttachesOwnershipWhenItsLoaderDecodesContents() {
        Identifier spriteId = id("pack", "block/example");
        Identifier sourceId = id("pack", "textures/block/example.png");
        Resource resource = new Resource(null, InputStream::nullInputStream);
        SpriteSource.DiscardableLoader[] captured = new SpriteSource.DiscardableLoader[1];
        SpriteSource.Output delegate = new SpriteSource.Output() {
            @Override
            public void add(Identifier id, SpriteSource.DiscardableLoader loader) {
                captured[0] = loader;
            }

            @Override
            public void removeAll(java.util.function.Predicate<Identifier> predicate) {
            }
        };

        LabPbrAtlasProvenance.rememberSource(resource, sourceId);
        LabPbrAtlasProvenance.trackingOutput(delegate).add(spriteId, resource);

        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
             SpriteContents contents = new SpriteContents(spriteId, new FrameSize(1, 1), image,
                     Optional.empty(), List.of(), Optional.empty())) {
            assertEquals(contents, captured[0].get((id, exactResource) -> contents));
            assertEquals(sourceId, LabPbrAtlasProvenance.resolve(contents).orElseThrow());
        }
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
