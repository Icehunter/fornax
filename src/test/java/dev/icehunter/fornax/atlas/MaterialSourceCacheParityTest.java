package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cache-hit source evidence must retain raw 512px facts even when the cached atlas is downscaled. */
class MaterialSourceCacheParityTest {
    @TempDir Path temp;

    @Test
    void tinyAuthoredSourceTexelsSurviveColdAndCachedAtlasBuildPathsEqually() throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath("test", "textures/block/source_s.png");
        Path png = temp.resolve("source_s.png");
        // LabPBR source classes: two authored texels (one off, one weakest positive); all others absent.
        try (NativeImage raw = new NativeImage(NativeImage.Format.RGBA, 512, 512, false)) {
            raw.fillRect(0, 0, 512, 512, 0xff112233);
            raw.setPixel(0, 0, 0x01123456);
            raw.setPixel(511, 511, 0x00123456);
            raw.writeToFile(png);
        }
        try (SpriteContents contents = new SpriteContents(
                     Identifier.fromNamespaceAndPath("test", "block/source"), new FrameSize(512, 512),
                     new NativeImage(NativeImage.Format.RGBA, 512, 512, true));
             NativeImage atlas = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            var sprite = new TestSprite(contents);
            var entry = new LabPbrSidecarSurvey.Entry(sprite, id, 512, 512, null);
            var resources = resources(id, png);
            var fresh = new MaterialSourceIndex.Summary[1];
            assertTrue(MaterialMapAtlasReloadListener.blitSidecar(atlas, entry, resources,
                    new MaterialMapAtlasReloadListener.SpriteRect(0, 0, 1, 1),
                    (source, frameHeight) -> fresh[0] = MaterialSourceIndex.summarize(source), 0, 1, false));
            var expected = new MaterialSourceIndex.Summary(0, 262144, 1, 1, 262142, 0x01123456);
            assertEquals(expected, fresh[0]);
            byte[] levelZero = new byte[4];
            atlas.getPixelBytes().duplicate().get(levelZero);
            Path cache = temp.resolve("material.bin");
            String fingerprint = "a".repeat(64);
            LabPbrAtlasDiskCache.writeFileSync(cache, fingerprint, 1, 1, levelZero, new byte[0][]);
            try (var loaded = LabPbrAtlasDiskCache.tryReadFile(cache, fingerprint, 1, 1, 0)) {
                assertNotNull(loaded);
                assertEquals(atlas.getPixel(0, 0), loaded.base().getPixel(0, 0));
                assertEquals(expected, MaterialSourceIndex.readStatic(entry, resources));
            }
        }
    }

    private static ResourceManager resources(Identifier expectedId, Path png) {
        return new ResourceManager() {
            @Override public Optional<Resource> getResource(Identifier id) {
                return id.equals(expectedId)
                        ? Optional.of(new Resource((PackResources) null, () -> Files.newInputStream(png)))
                        : Optional.empty();
            }
            @Override public Set<String> getNamespaces() { return Set.of("test"); }
            @Override public List<Resource> getResourceStack(Identifier id) { return getResource(id).stream().toList(); }
            @Override public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> predicate) { return Map.of(); }
            @Override public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> predicate) { return Map.of(); }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 512, 512, 0, 0, 0); }
    }
}
