package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual retained-sidecar schedule; only the GPU handles are inert test objects. */
class AtlasSourcePublicationTest {
    @AfterEach
    void clearPublicationAndDrainPendingWork() {
        for (int i = 0; i < 3; i++) AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
        LabPbrAtlasPair.clear();
    }

    @Test
    void successfulUnchangedBlockUploadRebindsWithoutRebuildingTheRetainedAtlas() {
        try (SpriteContents contents = contents()) {
            var oldSprite = new TestSprite(contents);
            var newSprite = new TestSprite(contents);
            var material = publish(oldSprite);
            var before = material.sourceIndex();
            var preparations = preparations(newSprite);
            schedule(preparations);
            assertEquals(before.generation(), material.sourceIndex().generation());

            AtlasGenerationSchedule.onAtlasUploaded(TextureAtlas.LOCATION_BLOCKS, preparations);
            var after = material.sourceIndex();
            assertNotEquals(before.generation(), after.generation());
            assertTrue(after.lookup(newSprite).authoredCandidate());
            assertEquals(MaterialSourceIndex.UNKNOWN_SPRITE, after.lookup(oldSprite).flags());
            assertSame(material, MaterialMapAtlas.getInstance());

            for (int i = 0; i < 3; i++) AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
            assertFalse(AtlasGenerationSchedule.hasPending(TextureAtlas.LOCATION_BLOCKS));
            assertSame(material, MaterialMapAtlas.getInstance());
            assertSame(after, material.sourceIndex());
        }
    }

    @Test
    void failedVanillaUploadNeverRebindsTheRetainedSourceIdentities() {
        try (SpriteContents contents = contents()) {
            var oldSprite = new TestSprite(contents);
            var attemptedSprite = new TestSprite(contents);
            var material = publish(oldSprite);
            var before = material.sourceIndex();
            schedule(preparations(attemptedSprite));
            // A throwing vanilla upload never reaches the RETURN callback.
            for (int i = 0; i < 3; i++) AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
            assertSame(before, material.sourceIndex());
            assertTrue(before.lookup(oldSprite).authoredCandidate());
            assertEquals(MaterialSourceIndex.UNKNOWN_SPRITE, before.lookup(attemptedSprite).flags());
        }
    }

    @Test
    void supersededUploadCannotBindItsSpritesIntoTheNewerPendingGeneration() {
        try (SpriteContents contents = contents()) {
            var oldSprite = new TestSprite(contents);
            var abandonedSprite = new TestSprite(contents);
            var finalSprite = new TestSprite(contents);
            var material = publish(oldSprite);
            var before = material.sourceIndex();
            var abandoned = preparations(abandonedSprite);
            var replacement = preparations(finalSprite);
            schedule(abandoned);
            schedule(replacement);
            AtlasGenerationSchedule.onAtlasUploaded(TextureAtlas.LOCATION_BLOCKS, abandoned);
            assertSame(before, material.sourceIndex());
            AtlasGenerationSchedule.onAtlasUploaded(TextureAtlas.LOCATION_BLOCKS, replacement);
            assertTrue(material.sourceIndex().lookup(finalSprite).authoredCandidate());
            assertEquals(MaterialSourceIndex.UNKNOWN_SPRITE,
                    material.sourceIndex().lookup(abandonedSprite).flags());
        }
    }

    private static void schedule(SpriteLoader.Preparations preparations) {
        AtlasGenerationSchedule.scheduleRelease(TextureAtlas.LOCATION_BLOCKS, preparations, null, null,
                AtlasGenerationSchedule.scopeFor(TextureAtlas.LOCATION_BLOCKS, true));
    }

    private static SpriteLoader.Preparations preparations(TextureAtlasSprite sprite) {
        return new SpriteLoader.Preparations(2, 2, 0, null,
                Map.of(sprite.contents().name(), sprite), CompletableFuture.completedFuture(null));
    }

    private static MaterialMapAtlas publish(TextureAtlasSprite sprite) {
        var normalTexture = new FakeTexture();
        var materialTexture = new FakeTexture();
        var material = new MaterialMapAtlas(materialTexture, new FakeView(materialTexture));
        material.setSourceIndex(new MaterialSourceIndex(Map.of(sprite,
                new MaterialSourceIndex.Summary(0, 1, 0, 1, 0, 0x01112233))));
        LabPbrAtlasPair.publish(TextureAtlas.LOCATION_BLOCKS,
                new LabPbrAtlasPair(new NormalMapAtlas(normalTexture, new FakeView(normalTexture)), material));
        return material;
    }

    private static SpriteContents contents() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
        image.fillRect(0, 0, 2, 2, -1);
        return new SpriteContents(Identifier.fromNamespaceAndPath("test", "block/retained_source"),
                new FrameSize(2, 2), image);
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 2, 2, 0, 0, 0); }
    }

    private static final class FakeTexture extends GpuTexture {
        private boolean closed;
        FakeTexture() { super(USAGE_TEXTURE_BINDING, "source-publication-test", GpuFormat.RGBA8_UNORM, 1, 1, 1, 1); }
        @Override public void close() { this.closed = true; }
        @Override public boolean isClosed() { return this.closed; }
    }

    private static final class FakeView extends GpuTextureView {
        private boolean closed;
        FakeView(GpuTexture texture) { super(texture, 0, 1); }
        @Override public void close() { this.closed = true; }
        @Override public boolean isClosed() { return this.closed; }
    }
}
