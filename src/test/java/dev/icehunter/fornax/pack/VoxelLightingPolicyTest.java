package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.material.MaterialScalars;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelLightingPolicyTest {
    private static BlocksSpec parse(String text) {
        return PackTomlLoader.loadBlocks(new StringReader(text), "blocks.toml");
    }

    @Test void rootDefaultAndCategoryOverridesAreAcceptedWithoutChangingMaterialSynthesis() {
        var blocks = parse("""
                [lighting]
                voxel = false
                [categories.lamp]
                blocks = ["test:lamp"]
                lighting.voxel = true
                [categories.other]
                blocks = ["test:other"]
                lighting.voxel = false
                [categories.inherited]
                blocks = ["test:inherited"]
                """);
        assertFalse(blocks.voxelLightingDefault());
        var scalars = MaterialScalars.build(List.copyOf(blocks.categories().values()), blocks.voxelLightingDefault());
        assertFalse(scalars.voxelLighting(0), "uncategorized inherits the root default");
        assertTrue(scalars.voxelLighting(1));
        assertFalse(scalars.voxelLighting(2));
        assertFalse(scalars.voxelLighting(3));
        assertFalse(scalars.voxelLighting(-1));
        assertFalse(scalars.voxelLighting(99));
        assertNull(blocks.categories().get("lamp").emissive(), "membership does not synthesize self emission");
        assertFalse(scalars.hasEmissive(1));
    }

    @Test void absentPolicyRetainsExistingPacksAndCategoriesCanOptOutIndividually() {
        var blocks = parse("""
                [categories.inherited]
                blocks = ["test:inherited"]
                [categories.excluded]
                blocks = ["test:excluded"]
                lighting.voxel = false
                """);
        assertTrue(blocks.voxelLightingDefault());
        assertNull(blocks.categories().get("inherited").voxelLighting());
        var scalars = MaterialScalars.build(List.copyOf(blocks.categories().values()), blocks.voxelLightingDefault());
        assertTrue(scalars.voxelLighting(0));
        assertTrue(scalars.voxelLighting(1));
        assertFalse(scalars.voxelLighting(2));
        assertTrue(BlocksSpec.empty().voxelLightingDefault());
        assertTrue(MaterialScalars.build(List.of()).voxelLighting(0));
    }

    @Test void wrongTableTypesUnknownKeysAndNonBooleanPoliciesFailAtLoadWithTheirFullPath() throws Exception {
        for (String filename : List.of("root-type", "root-key", "root-table", "category-type", "category-key", "category-table")) {
            String text = Files.readString(Path.of("src/test/resources/packs/bad_voxel_lighting", filename + ".toml"));
            var error = assertThrows(FornaxPackError.class, () -> parse(text));
            assertTrue(error.getMessage().contains("blocks.toml"));
            assertTrue(error.getMessage().contains(filename.startsWith("category") ? "categories.lamp.lighting" : "lighting"));
        }
    }

    /** Pack reload needs GPU/client state; pin the production parse-to-retirement-to-resolution seam. */
    @Test void packRebuildRetiresSourceSnapshotsBeforeResolvingTheReloadedManifest() throws Exception {
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        int rebuild = graph.indexOf("CompletableFuture<Void> rebuild(");
        int close = graph.indexOf("closeCurrent();", rebuild);
        int refresh = graph.indexOf("MaterialResolution.refresh();", close);
        assertTrue(rebuild >= 0 && close > rebuild && refresh > close);
        String closeBody = graph.substring(graph.indexOf("private static void closeCurrent()"));
        assertTrue(closeBody.indexOf("VoxelWindow.attachRegistry(null)") < closeBody.indexOf("registry.close()"));
        String resolution = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/material/MaterialResolution.java"));
        assertTrue(resolution.contains("MaterialScalars.build(pack.categories().ordered(), pack.blocks().voxelLightingDefault())"));
    }
    /** Tag reload keeps the registry, so publication itself must retire cached and in-flight policy. */
    @Test void materialRefreshUsesTheHarvestLifetimeBoundaryAndCapturesScalarsInsideItsLease() throws Exception {
        String resolution = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/material/MaterialResolution.java"));
        assertTrue(resolution.contains("VoxelHarvestLifecycle.publishMaterials("));
        String harvester = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java"));
        int active = harvester.indexOf("Result harvestCurrent(");
        int acquire = harvester.indexOf("VoxelHarvestLifecycle.tryAcquire()", active);
        int snapshot = harvester.indexOf("MaterialScalarsHolder.current()", active);
        assertTrue(active >= 0 && acquire > active && snapshot > acquire);
        for (String filename : List.of("voxel/DirectSectionReader.java", "mixin/sodium/ChunkBuilderMeshingTaskMixin.java")) {
            String caller = Files.readString(Path.of("src/main/java/dev/icehunter/fornax", filename));
            assertTrue(caller.contains("SectionHarvester.harvestCurrent("));
            assertFalse(caller.contains("MaterialScalarsHolder.current()"));
        }
    }

}
