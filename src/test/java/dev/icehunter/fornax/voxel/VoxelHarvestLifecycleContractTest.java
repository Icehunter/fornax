package dev.icehunter.fornax.voxel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mixin target registration and callback ordering need the real client; keep the seam pinned. */
class VoxelHarvestLifecycleContractTest {
    @Test
    void cpuRetirementAndSuccessfulModelPublicationHooksAreRegistered() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("vanilla.TextureAtlasVoxelLifetimeMixin"));
        assertTrue(mixins.contains("vanilla.ModelManagerVoxelLifetimeMixin"));
    }

    @Test
    void exactCpuLifetimeHooksDoNotReopenAtAtlasUploadOrClose() throws Exception {
        String atlas = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasVoxelLifetimeMixin.java"));
        assertTrue(atlas.contains("method = \"clearTextureData()V\", at = @At(\"HEAD\")"));
        assertTrue(atlas.contains("TextureAtlas.LOCATION_BLOCKS.equals(this.location)"));
        assertTrue(!atlas.contains("onModelsPublished"));
        String models = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/ModelManagerVoxelLifetimeMixin.java"));
        assertTrue(models.contains("apply(Lnet/minecraft/client/resources/model/ModelManager$ReloadState;)V"));
        assertTrue(models.contains("at = @At(\"RETURN\")"));
    }

    @Test
    void bothUploadPathsRejectRetiredHarvestsWithoutRequiringOptionalMetadata() throws Exception {
        String upload = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        int single = upload.indexOf("public static void uploadSlot(");
        int singleLock = upload.indexOf("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)", single);
        int singleGuard = upload.indexOf("if (!VoxelWindow.hasCurrentSourceSummary(result))", singleLock);
        int singleBuffer = upload.indexOf("BufferInstance occupancy = registry.getBuffer", singleLock);
        assertTrue(singleGuard > singleLock && singleGuard < singleBuffer);
        assertTrue(upload.contains("if (!VoxelWindow.hasCurrentSourceSummary(item.result())\n"
                + "                            || (item.sectionState() != null"));
        String window = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("previous.sourceSummary(), previous.harvestGeneration()"),
                "light-only refresh must not recertify an older geometry palette");
        String sodium = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/ChunkBuilderMeshingTaskMixin.java"));
        assertTrue(sodium.contains("if (result != null)"), "expected cancellation is not a harvest failure");
    }

    @Test
    void queuedReadsCaptureALifetimeAndPausedHarvestsDoNotConsumeTheWindow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(source.contains("VoxelHarvestLifecycle.tryAcquire(harvestGeneration)"),
                "the old queued world reference must be guarded before DirectSectionReader.read");
        assertTrue(source.contains("if (!VoxelHarvestLifecycle.isAvailable()) return"),
                "a paused frame must not consume the stationary camera's unharvested shell");
    }
}
