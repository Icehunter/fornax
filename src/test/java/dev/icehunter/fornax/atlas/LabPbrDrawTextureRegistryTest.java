package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TickableTexture;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LabPbrDrawTextureRegistryTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearDrawOwners() {
        LabPbrDrawTextureRegistry.clear();
        LabPbrAtlasPair.clear();
    }

    @Test
    void exactSampler0OwnerIsRememberedByPreparedDrawIdentity() {
        PreparedRenderType first = prepared();
        PreparedRenderType recordEqualButDistinct = prepared();
        Identifier owner = id("pack_a", "textures/entity/banner/base.png");

        assertEquals(first, recordEqualButDistinct, "precondition: record values are equal");
        LabPbrDrawTextureRegistry.remember(first, owner);

        assertEquals(Optional.of(owner), LabPbrDrawTextureRegistry.ownerOf(first));
        assertTrue(LabPbrDrawTextureRegistry.ownerOf(recordEqualButDistinct).isEmpty(),
                "GPU-equal or record-equal prepared draws must not borrow owner provenance");
    }

    @Test
    void exactNamespacesNeverAlias() {
        PreparedRenderType minecraft = prepared();
        PreparedRenderType pack = prepared();
        Identifier minecraftOwner = id("minecraft", "textures/entity/chest/normal.png");
        Identifier packOwner = id("pack", "textures/entity/chest/normal.png");

        LabPbrDrawTextureRegistry.remember(minecraft, minecraftOwner);
        LabPbrDrawTextureRegistry.remember(pack, packOwner);

        assertEquals(Optional.of(minecraftOwner), LabPbrDrawTextureRegistry.ownerOf(minecraft));
        assertEquals(Optional.of(packOwner), LabPbrDrawTextureRegistry.ownerOf(pack));
    }

    @Test
    void preparedOwnerFromPreviousResourceGenerationIsRejected() {
        PreparedRenderType prepared = prepared();
        Identifier owner = id("pack", "textures/entity/chest/normal.png");
        ResourceManager empty = resources(Map.of());

        LabPbrSidecarRegistry.refreshActive(empty);
        LabPbrDrawTextureRegistry.remember(prepared, owner);
        assertTrue(LabPbrDrawTextureRegistry.isCurrent(prepared));

        LabPbrSidecarRegistry.refreshActive(empty);
        assertTrue(LabPbrDrawTextureRegistry.ownerOf(prepared).isPresent());
        assertTrue(!LabPbrDrawTextureRegistry.isCurrent(prepared),
                "a prepared draw must not bind sidecars from a later pack generation");
    }

    @Test
    void directLaneLoadPreservesSourceDimensionsAndCategoricalBytes() throws IOException {
        Identifier sidecar = id("pack", "textures/entity/probe_s.png");
        Path png = temp.resolve("probe_s.png");
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 3, false)) {
            source.setPixel(0, 0, argb(0, 1, 64, 65));
            source.setPixel(1, 0, argb(254, 128, 229, 230));
            source.setPixel(0, 1, argb(255, 232, 233, 234));
            source.setPixel(1, 1, argb(127, 255, 238, 10));
            source.setPixel(0, 2, argb(5, 6, 7, 8));
            source.setPixel(1, 2, argb(9, 10, 11, 12));
            source.writeToFile(png);
        }

        try (NativeImage loaded = LabPbrDirectTexturePair.loadSource(resources(Map.of(sidecar, png)), sidecar)) {
            assertEquals(2, loaded.getWidth());
            assertEquals(3, loaded.getHeight());
            assertEquals(argb(0, 1, 64, 65), loaded.getPixel(0, 0));
            assertEquals(argb(254, 128, 229, 230), loaded.getPixel(1, 0));
            assertEquals(argb(255, 232, 233, 234), loaded.getPixel(0, 1));
            assertEquals(argb(127, 255, 238, 10), loaded.getPixel(1, 1));
            assertEquals(argb(5, 6, 7, 8), loaded.getPixel(0, 2));
            assertEquals(argb(9, 10, 11, 12), loaded.getPixel(1, 2));
        }
    }

    @Test
    void atlasInstancesAreIndexedByExactAtlasOwnerAndBlockAccessorStaysCompatible() {
        Identifier otherAtlas = id("pack", "textures/atlas/entity.png");
        FakeTexture normalTexture = new FakeTexture();
        FakeTexture materialTexture = new FakeTexture();
        NormalMapAtlas normal = new NormalMapAtlas(normalTexture, new FakeTextureView(normalTexture));
        MaterialMapAtlas material = new MaterialMapAtlas(
                materialTexture, new FakeTextureView(materialTexture));

        LabPbrAtlasPair.publish(otherAtlas, new LabPbrAtlasPair(normal, material));

        assertSame(normal, NormalMapAtlas.getInstance(otherAtlas));
        assertSame(material, MaterialMapAtlas.getInstance(otherAtlas));
        assertTrue(NormalMapAtlas.getInstance() == NormalMapAtlas.getInstance(TextureAtlas.LOCATION_BLOCKS));
        assertTrue(MaterialMapAtlas.getInstance() == MaterialMapAtlas.getInstance(TextureAtlas.LOCATION_BLOCKS));

    }

    @Test
    void atlasPairReplacementPublishesBothLanesBeforeRetiringEitherOldLane() {
        Identifier owner = id("pack", "textures/atlas/entity.png");
        LabPbrAtlasPair[] replacement = new LabPbrAtlasPair[1];
        FakeTexture oldNormalTexture = new FakeTexture(
                () -> assertSame(replacement[0], LabPbrAtlasPair.get(owner)));
        FakeTexture oldMaterialTexture = new FakeTexture(
                () -> assertSame(replacement[0], LabPbrAtlasPair.get(owner)));
        LabPbrAtlasPair old = new LabPbrAtlasPair(
                new NormalMapAtlas(oldNormalTexture, new FakeTextureView(oldNormalTexture)),
                new MaterialMapAtlas(oldMaterialTexture, new FakeTextureView(oldMaterialTexture)));
        LabPbrAtlasPair.publish(owner, old);

        FakeTexture newNormalTexture = new FakeTexture();
        FakeTexture newMaterialTexture = new FakeTexture();
        replacement[0] = new LabPbrAtlasPair(
                new NormalMapAtlas(newNormalTexture, new FakeTextureView(newNormalTexture)),
                new MaterialMapAtlas(newMaterialTexture, new FakeTextureView(newMaterialTexture)));
        LabPbrAtlasPair.publish(owner, replacement[0]);

        assertSame(replacement[0], LabPbrAtlasPair.get(owner));
        assertTrue(oldNormalTexture.isClosed());
        assertTrue(oldMaterialTexture.isClosed());
        assertFalse(newNormalTexture.isClosed());
        assertFalse(newMaterialTexture.isClosed());
    }

    @Test
    void failedNormalBuildRemovesAndRetiresThePreviousAtlasGeneration() {
        Identifier owner = id("pack", "textures/atlas/entity.png");
        FakeTexture oldNormal = new FakeTexture();
        FakeTexture oldMaterial = new FakeTexture();
        LabPbrAtlasPair.publish(owner, pair(oldNormal, oldMaterial));

        LabPbrAtlasPair.rebuild(owner, () -> null,
                () -> { throw new AssertionError("material must not build after normal failure"); });

        assertTrue(LabPbrAtlasPair.get(owner) == null);
        assertTrue(oldNormal.isClosed());
        assertTrue(oldMaterial.isClosed());
    }

    @Test
    void failedMaterialBuildRemovesOldGenerationAndClosesPartialNormal() {
        Identifier owner = id("pack", "textures/atlas/entity.png");
        FakeTexture oldNormal = new FakeTexture();
        FakeTexture oldMaterial = new FakeTexture();
        FakeTexture partialNormal = new FakeTexture();
        LabPbrAtlasPair.publish(owner, pair(oldNormal, oldMaterial));

        LabPbrAtlasPair.rebuild(owner,
                () -> new NormalMapAtlas(partialNormal, new FakeTextureView(partialNormal)),
                () -> null);

        assertTrue(LabPbrAtlasPair.get(owner) == null);
        assertTrue(oldNormal.isClosed());
        assertTrue(oldMaterial.isClosed());
        assertTrue(partialNormal.isClosed());
    }

    @Test
    void thrownMaterialBuildRemovesOldGenerationAndClosesPartialNormal() {
        Identifier owner = id("pack", "textures/atlas/entity.png");
        FakeTexture oldNormal = new FakeTexture();
        FakeTexture oldMaterial = new FakeTexture();
        FakeTexture partialNormal = new FakeTexture();
        LabPbrAtlasPair.publish(owner, pair(oldNormal, oldMaterial));

        assertThrows(IllegalStateException.class, () -> LabPbrAtlasPair.rebuild(owner,
                () -> new NormalMapAtlas(partialNormal, new FakeTextureView(partialNormal)),
                () -> { throw new IllegalStateException("material build failed"); }));

        assertTrue(LabPbrAtlasPair.get(owner) == null);
        assertTrue(oldNormal.isClosed());
        assertTrue(oldMaterial.isClosed());
        assertTrue(partialNormal.isClosed());
    }

    @Test
    void onlyConsumedAtlasOwnersCanAllocateMirroredPairs() {
        assertTrue(LabPbrGeometryBindings.isMirroredAtlasOwner(TextureAtlas.LOCATION_BLOCKS));
        assertTrue(LabPbrGeometryBindings.isMirroredAtlasOwner(
                LabPbrGeometryBindings.BANNER_ATLAS));
        assertFalse(LabPbrGeometryBindings.isMirroredAtlasOwner(
                id("minecraft", "textures/atlas/paintings.png")));
        assertFalse(LabPbrGeometryBindings.isMirroredAtlasOwner(
                id("minecraft", "textures/atlas/items.png")));
        assertFalse(LabPbrGeometryBindings.isMirroredAtlasOwner(
                id("minecraft", "textures/atlas/particles.png")));
    }

    @Test
    void generationCacheRetiresWithoutAnotherResolveAndOnlyOnce() {
        LabPbrGeometryBindings.GenerationOwnedCache<Identifier, CountingCloseable> cache =
                new LabPbrGeometryBindings.GenerationOwnedCache<>();
        CountingCloseable old = new CountingCloseable();
        cache.transitionTo(7L);
        cache.put(id("pack", "textures/entity/chest.png"), old);

        cache.transitionTo(8L);
        cache.transitionTo(8L);

        assertEquals(1, old.closeCount);
        assertEquals(0, cache.size());
        assertEquals(8L, cache.generation());
    }

    @Test
    void activeRegistryRefreshImmediatelyTransitionsTheDirectCacheGeneration() {
        long generation = LabPbrSidecarRegistry.refreshActive(resources(Map.of()));

        assertEquals(generation, LabPbrGeometryBindings.directGeneration());
    }

    @Test
    void mc262SimpleTextureIsAStaticFullImageOwnerEvenWithAnimationMetadata() throws IOException {
        assertFalse(TickableTexture.class.isAssignableFrom(SimpleTexture.class),
                "TextureManager only clocks TickableTexture instances");
        assertTrue(Stream.of(SimpleTexture.class.getMethods())
                .noneMatch(method -> method.getName().equals("tick")));

        Identifier owner = id("pack", "textures/entity/probe.png");
        Path png = temp.resolve("probe.png");
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 4, false)) {
            image.setPixel(0, 0, argb(255, 1, 2, 3));
            image.setPixel(1, 3, argb(255, 4, 5, 6));
            image.writeToFile(png);
        }
        AnimationMetadataSection animation = new AnimationMetadataSection(
                Optional.empty(), Optional.of(2), Optional.of(2), 3, true);
        TextureMetadataSection texture = new TextureMetadataSection(
                true, true, MipmapStrategy.AUTO, 0.0f);
        ResourceMetadata metadata = ResourceMetadata.of(
                AnimationMetadataSection.TYPE, animation,
                TextureMetadataSection.TYPE, texture);
        ResourceManager manager = resources(Map.of(owner, png), Map.of(owner, metadata));
        assertTrue(manager.getResource(owner).orElseThrow().metadata()
                .getSection(AnimationMetadataSection.TYPE).isPresent(),
                "precondition: the authored animation section is visible to resource metadata");

        try (TextureContents contents = new SimpleTexture(owner).loadContents(manager)) {
            assertEquals(2, contents.image().getWidth());
            assertEquals(4, contents.image().getHeight(),
                    "SimpleTexture uploads the complete sheet, not animation frames");
            assertSame(texture, contents.metadata(),
                    "TextureContents consumes only the texture metadata section");
            assertTrue(contents.blur());
            assertTrue(contents.clamp());
        }
    }

    private static PreparedRenderType prepared() {
        return new PreparedRenderType(null, null, null, null, List.of());
    }

    private static LabPbrAtlasPair pair(FakeTexture normal, FakeTexture material) {
        return new LabPbrAtlasPair(
                new NormalMapAtlas(normal, new FakeTextureView(normal)),
                new MaterialMapAtlas(material, new FakeTextureView(material)));
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static ResourceManager resources(Map<Identifier, Path> files) {
        return resources(files, Map.of());
    }

    private static ResourceManager resources(Map<Identifier, Path> files,
                                             Map<Identifier, ResourceMetadata> metadata) {
        Map<Identifier, Path> copy = new LinkedHashMap<>(files);
        Map<Identifier, ResourceMetadata> metadataCopy = new LinkedHashMap<>(metadata);
        return new ResourceManager() {
            @Override
            public Optional<Resource> getResource(Identifier id) {
                Path path = copy.get(id);
                return path == null ? Optional.empty() : Optional.of(new Resource(
                        (PackResources) null, () -> Files.newInputStream(path),
                        () -> metadataCopy.getOrDefault(id, ResourceMetadata.EMPTY)));
            }

            @Override
            public Set<String> getNamespaces() {
                return copy.keySet().stream().map(Identifier::getNamespace)
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

    private static final class FakeTexture extends GpuTexture {
        private boolean closed;
        private final Runnable onClose;

        private FakeTexture() {
            this(() -> { });
        }

        private FakeTexture(Runnable onClose) {
            super(GpuTexture.USAGE_TEXTURE_BINDING, "test", GpuFormat.RGBA8_UNORM,
                    1, 1, 1, 1);
            this.onClose = onClose;
        }

        @Override
        public void close() {
            this.closed = true;
            this.onClose.run();
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }
    }

    private static final class FakeTextureView extends GpuTextureView {
        private boolean closed;

        private FakeTextureView(GpuTexture texture) {
            super(texture, 0, 1);
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }
    }

    private static final class CountingCloseable implements AutoCloseable {
        private int closeCount;

        @Override
        public void close() {
            this.closeCount++;
        }
    }
}
