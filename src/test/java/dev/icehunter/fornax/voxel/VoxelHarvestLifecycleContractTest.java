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
        int singleEnd = upload.indexOf("\n    }", single);
        assertTrue(single >= 0 && singleEnd > single);
        assertTrue(upload.substring(single, singleEnd).contains(
                "uploadSlots(registry, List.of(new SlotUpload(slot, result, false)))"),
                "single uploads must use the same guarded queue as batches");
        int batch = upload.indexOf("public static void uploadSlots(");
        int batchLock = upload.indexOf("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)", batch);
        int batchGuard = upload.indexOf("!VoxelWindow.hasCurrentSourceSummary(item.result())", batchLock);
        int enqueue = upload.indexOf("queue(registry).publish(", batchLock);
        assertTrue(batch >= 0 && batchLock > batch && batchGuard > batchLock && enqueue > batchGuard,
                "retired data must be rejected before it enters the frame queue");
        int record = upload.indexOf("private static void uploadBatchLocked(");
        int recordGuard = upload.indexOf("!VoxelWindow.hasCurrentSourceSummary(item.result())", record);
        int optionalStateGuard = upload.indexOf("|| (item.sectionState() != null", recordGuard);
        int transfer = upload.indexOf("VK13.vkCmdUpdateBuffer(", record);
        assertTrue(record >= 0 && recordGuard > record && optionalStateGuard > recordGuard && transfer > optionalStateGuard,
                "the drain must recheck lifetime independently of optional metadata before recording writes");
        String window = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("previous.withLightmap(sample.getValue())"));
        String harvester = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java"));
        assertTrue(harvester.contains("updated, sourceSummary, harvestGeneration, sourceEvidence"),
                "light-only refresh must retain geometry evidence and its captured generations");
        // ChunkBuilderMeshingTaskMixin queues the harvest onto VoxelWindow.queueMeshTriggeredHarvest
        // instead of calling harvestCurrent inline; the null-check lives in that method's callback.
        String voxelWindow = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(voxelWindow.contains("if (result != null) {"), "expected cancellation is not a harvest failure");
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
