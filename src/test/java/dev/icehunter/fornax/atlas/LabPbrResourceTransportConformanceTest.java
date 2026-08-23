package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Source PNG -> resource lookup -> real blitter -> atlas level-zero conformance. */
class LabPbrResourceTransportConformanceTest {
    @TempDir
    Path temp;

    @Test
    void exactMaterialPngBytesSurviveTheResourceBackedTransport() throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath("pack", "textures/block/probe_s.png");
        Path png = temp.resolve("probe_s.png");
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            source.setPixel(0, 0, argb(0, 1, 64, 65));
            source.setPixel(1, 0, argb(254, 128, 229, 230));
            source.setPixel(0, 1, argb(255, 232, 233, 234));
            source.setPixel(1, 1, argb(127, 255, 238, 10));
            source.writeToFile(png);
        }

        try (NativeImage atlas = new NativeImage(NativeImage.Format.RGBA, 4, 4, false)) {
            atlas.fillRect(0, 0, 4, 4, 0xFF000000);
            assertTrue(MaterialMapAtlasReloadListener.blitSidecar(atlas,
                    new LabPbrSidecarSurvey.Entry(null, id, 2, 2, null), resources(id, png),
                    new MaterialMapAtlasReloadListener.SpriteRect(1, 1, 2, 2),
                    null, 0, 1, false));

            assertEquals(argb(0, 1, 64, 65), atlas.getPixel(1, 1));
            assertEquals(argb(254, 128, 229, 230), atlas.getPixel(2, 1));
            assertEquals(argb(255, 232, 233, 234), atlas.getPixel(1, 2));
            assertEquals(argb(127, 255, 238, 10), atlas.getPixel(2, 2));
        }
    }

    @Test
    void missingPartnerDoesNotBorrowOrOverwriteTheSemanticNeutral() {
        Identifier missing = Identifier.fromNamespaceAndPath("pack", "textures/block/probe_n.png");
        try (NativeImage atlas = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            atlas.setPixel(0, 0, LabPbrNeutralTextures.NORMAL_ARGB);
            assertFalse(NormalMapAtlasReloadListener.blitSidecar(atlas,
                    new LabPbrSidecarSurvey.Entry(null, null, 0, 0, null), resources(),
                    new NormalMapAtlasReloadListener.SpriteRect(0, 0, 1, 1),
                    0, 1, false));
            assertEquals(LabPbrNeutralTextures.NORMAL_ARGB, atlas.getPixel(0, 0),
                    "an absent exact owner lane must remain neutral");
            assertTrue(resources().getResource(missing).isEmpty());
        }
    }

    @Test
    void staticOwnerRejectsAFrameStripShapedSidecarInsteadOfInventingAnimation() throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath("pack", "textures/block/static_probe_n.png");
        Path png = temp.resolve("static_probe_n.png");
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 4, false)) {
            source.fillRect(0, 0, 2, 2, argb(255, 255, 0, 0));
            source.fillRect(0, 2, 2, 2, argb(255, 0, 255, 0));
            source.writeToFile(png);
        }

        try (NativeImage atlas = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            atlas.fillRect(0, 0, 2, 2, LabPbrNeutralTextures.NORMAL_ARGB);
            assertFalse(NormalMapAtlasReloadListener.blitSidecar(atlas,
                    new LabPbrSidecarSurvey.Entry(null, id, 2, 4, null), resources(id, png),
                    new NormalMapAtlasReloadListener.SpriteRect(0, 0, 2, 2),
                    0, 1, false));
            assertEquals(LabPbrNeutralTextures.NORMAL_ARGB, atlas.getPixel(0, 0));
        }
    }

    @Test
    void animatedOwnerRejectsAnUnavailableInitialFrameInsteadOfFallingBackToZero()
            throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath("pack", "textures/block/animated_probe_n.png");
        Path png = temp.resolve("animated_probe_n.png");
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 4, false)) {
            source.fillRect(0, 0, 2, 4, argb(255, 128, 128, 255));
            source.writeToFile(png);
        }

        try (NativeImage atlas = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            atlas.fillRect(0, 0, 2, 2, LabPbrNeutralTextures.NORMAL_ARGB);
            assertFalse(NormalMapAtlasReloadListener.blitSidecar(atlas,
                    new LabPbrSidecarSurvey.Entry(null, id, 2, 4, null), resources(id, png),
                    new NormalMapAtlasReloadListener.SpriteRect(0, 0, 2, 2),
                    2, 1, true));
            assertEquals(LabPbrNeutralTextures.NORMAL_ARGB, atlas.getPixel(0, 0));
        }
    }

    private static ResourceManager resources(Object... idAndPath) {
        Map<Identifier, Path> files;
        if (idAndPath.length == 0) {
            files = Map.of();
        } else {
            files = Map.of((Identifier) idAndPath[0], (Path) idAndPath[1]);
        }
        return new ResourceManager() {
            @Override
            public Optional<Resource> getResource(Identifier id) {
                Path path = files.get(id);
                return path == null ? Optional.empty() : Optional.of(new Resource(
                        (PackResources) null, () -> Files.newInputStream(path)));
            }

            @Override
            public Set<String> getNamespaces() {
                return files.keySet().stream().map(Identifier::getNamespace)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }

            @Override
            public List<Resource> getResourceStack(Identifier id) {
                return getResource(id).stream().toList();
            }

            @Override
            public Map<Identifier, Resource> listResources(String path,
                                                           Predicate<Identifier> predicate) {
                return Map.of();
            }

            @Override
            public Map<Identifier, List<Resource>> listResourceStacks(
                    String path, Predicate<Identifier> predicate) {
                return Map.of();
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.empty();
            }
        };
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
