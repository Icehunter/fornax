package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

final class LabPbrSidecarRegistryTest {
    @Test
    void exactAlbedoOwnerReceivesOnlyItsExactSiblingSidecars() {
        LabPbrSidecarRegistry registry = new LabPbrSidecarRegistry();
        registry.refresh(resources(
                id("minecraft", "textures/block/oak_planks_n.png"),
                id("minecraft", "textures/block/oak_planks_s.png"),
                id("minecraft", "textures/block/oak_planks_weathered_n.png"),
                id("example", "textures/block/oak_planks_s.png")));

        LabPbrSidecarDescriptor oak = registry.descriptor(
                id("minecraft", "textures/block/oak_planks.png"));
        assertEquals(id("minecraft", "textures/block/oak_planks_n.png"), oak.normal().orElseThrow());
        assertEquals(id("minecraft", "textures/block/oak_planks_s.png"), oak.material().orElseThrow());

        LabPbrSidecarDescriptor weathered = registry.descriptor(
                id("minecraft", "textures/block/oak_planks_weathered.png"));
        assertEquals(id("minecraft", "textures/block/oak_planks_weathered_n.png"),
                weathered.normal().orElseThrow());
        assertTrue(weathered.material().isEmpty());

        LabPbrSidecarDescriptor otherNamespace = registry.descriptor(
                id("example", "textures/block/oak_planks.png"));
        assertTrue(otherNamespace.normal().isEmpty());
        assertEquals(id("example", "textures/block/oak_planks_s.png"),
                otherNamespace.material().orElseThrow());
    }

    @Test
    void normalAndMaterialPresenceAreIndependent() {
        LabPbrSidecarRegistry registry = new LabPbrSidecarRegistry();
        registry.refresh(resources(
                id("minecraft", "textures/item/normal_only_n.png"),
                id("minecraft", "textures/item/material_only_s.png"),
                id("minecraft", "textures/item/unrelated.png"),
                id("minecraft", "textures/item/not_a_sidecar_n.jpg")));

        LabPbrSidecarDescriptor normalOnly = registry.descriptor(
                id("minecraft", "textures/item/normal_only.png"));
        assertTrue(normalOnly.normal().isPresent());
        assertTrue(normalOnly.material().isEmpty());

        LabPbrSidecarDescriptor materialOnly = registry.descriptor(
                id("minecraft", "textures/item/material_only.png"));
        assertTrue(materialOnly.normal().isEmpty());
        assertTrue(materialOnly.material().isPresent());

        LabPbrSidecarDescriptor unrelated = registry.descriptor(
                id("minecraft", "textures/item/unrelated.png"));
        assertTrue(unrelated.normal().isEmpty());
        assertTrue(unrelated.material().isEmpty());
    }

    @Test
    void scansRepresentativeOwnersAcrossEveryResourceTextureDomain() {
        List<String> ownerPaths = List.of(
                "textures/block/stone.png",
                "textures/item/diamond_sword.png",
                "textures/entity/zombie/zombie.png",
                "textures/entity/chest/normal.png",
                "textures/painting/kebab.png",
                "textures/particle/spark.png");
        List<Identifier> sidecars = new ArrayList<>();
        for (String ownerPath : ownerPaths) {
            sidecars.add(id("pack", sidecar(ownerPath, "_n")));
            sidecars.add(id("pack", sidecar(ownerPath, "_s")));
        }

        LabPbrSidecarRegistry registry = new LabPbrSidecarRegistry();
        registry.refresh(resources(sidecars.toArray(Identifier[]::new)));

        for (String ownerPath : ownerPaths) {
            LabPbrSidecarDescriptor descriptor = registry.descriptor(id("pack", ownerPath));
            assertEquals(id("pack", sidecar(ownerPath, "_n")), descriptor.normal().orElseThrow());
            assertEquals(id("pack", sidecar(ownerPath, "_s")), descriptor.material().orElseThrow());
        }
    }

    @Test
    void preparedDescriptorFromPreviousGenerationResolvesNeutral() {
        Identifier owner = id("pack", "textures/block/iron_block.png");
        LabPbrSidecarRegistry registry = new LabPbrSidecarRegistry();
        long firstGeneration = registry.refresh(resources(
                id("pack", "textures/block/iron_block_n.png"),
                id("pack", "textures/block/iron_block_s.png")));
        LabPbrSidecarProvenance prepared = registry.prepare(owner);
        assertEquals(firstGeneration, prepared.generation());
        assertTrue(registry.resolve(prepared).normal().isPresent());
        assertTrue(registry.resolve(prepared).material().isPresent());

        long secondGeneration = registry.refresh(resources(
                id("pack", "textures/block/iron_block_n.png")));
        assertTrue(secondGeneration > firstGeneration);

        LabPbrSidecarDescriptor stale = registry.resolve(prepared);
        assertEquals(owner, stale.owner());
        assertTrue(stale.normal().isEmpty());
        assertTrue(stale.material().isEmpty());

        LabPbrSidecarDescriptor fresh = registry.resolve(registry.prepare(owner));
        assertTrue(fresh.normal().isPresent());
        assertTrue(fresh.material().isEmpty());
    }

    @Test
    void resourceManagerScanIsLimitedToTexturePngSidecars() {
        LabPbrSidecarRegistry registry = new LabPbrSidecarRegistry();
        registry.refresh(resources(
                id("pack", "textures/block/valid_n.png"),
                id("pack", "textures/block/valid_s.png"),
                id("pack", "models/block/not_a_texture_n.png"),
                id("pack", "textures/block/metadata_s.png.mcmeta"),
                id("pack", "textures/block/not_png_n.tga")));

        assertTrue(registry.descriptor(id("pack", "textures/block/valid.png")).hasAnySidecar());
        assertFalse(registry.descriptor(id("pack", "models/block/not_a_texture.png")).hasAnySidecar());
        assertFalse(registry.descriptor(id("pack", "textures/block/metadata.png.mcmeta")).hasAnySidecar());
        assertFalse(registry.descriptor(id("pack", "textures/block/not_png.tga")).hasAnySidecar());
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static String sidecar(String ownerPath, String suffix) {
        return ownerPath.substring(0, ownerPath.length() - ".png".length()) + suffix + ".png";
    }

    private static ResourceManager resources(Identifier... ids) {
        Map<Identifier, Resource> resources = new LinkedHashMap<>();
        for (Identifier id : ids) {
            resources.put(id, null);
        }
        return new ResourceManager() {
            @Override
            public Optional<Resource> getResource(Identifier id) {
                return Optional.ofNullable(resources.get(id));
            }

            @Override
            public Set<String> getNamespaces() {
                return Set.copyOf(ids.length == 0
                        ? List.of()
                        : java.util.Arrays.stream(ids).map(Identifier::getNamespace).toList());
            }

            @Override
            public List<Resource> getResourceStack(Identifier id) {
                return List.of();
            }

            @Override
            public Map<Identifier, Resource> listResources(
                    String path, Predicate<Identifier> predicate) {
                Map<Identifier, Resource> matches = new LinkedHashMap<>();
                resources.forEach((id, resource) -> {
                    if (id.getPath().startsWith(path + "/") && predicate.test(id)) {
                        matches.put(id, resource);
                    }
                });
                return matches;
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
}
