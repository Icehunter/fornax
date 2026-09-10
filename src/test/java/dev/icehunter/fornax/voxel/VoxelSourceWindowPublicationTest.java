package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelSourceWindowPublicationTest {
    @AfterEach void detach() { VoxelWindow.attachRegistry(null); }
    @SuppressWarnings("unchecked")
    @Test void summaryOffStillBuildsCommittedSourceWindowAndModelRetirementInvalidatesIt() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        var graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelSourceWindow]
                kind = "buffer"
                [targets.voxelSectionState]
                kind = "buffer"
                """), "graph.toml");
        var registry = TargetRegistry.create(graph, Map.of());
        VoxelWindow.attachRegistry(registry);
        VoxelWindow.synchronizeSourceGeneration(registry, 7);
        VoxelWindow.recenter(0, 0, 0, 1);
        var data = VoxelEmitterPoolTest.result(7, 0);
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), data);
        var field = VoxelWindow.class.getDeclaredField("sourceWindow"); field.setAccessible(true);
        var sourceWindow = (VoxelSourceWindow)field.get(null);
        assertEquals(0, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(), 2));
        int slot = VoxelWindow.slotFor(0,0,0);
        var stateField = VoxelWindow.class.getDeclaredField("sectionStates"); stateField.setAccessible(true);
        var latest = VoxelSectionState.class.getDeclaredField("latest"); latest.setAccessible(true);
        var token = ((Map<Integer,VoxelSectionState.Snapshot>)latest.get(stateField.get(null))).get(slot);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            // Explicit successful-transfer model; the empty registry performs no GPU upload.
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot,data,true,token));
            assertEquals(1, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(),2));
            assertEquals(slot*96+1, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(),VoxelSourceWindow.CELL_BASE + 3));
        }
        var replacement = VoxelEmitterPoolTest.result(7, 15);
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), replacement);
        assertEquals(0, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(), 2),
                "queued replacement geometry cannot certify the old source");
        var replacementToken = ((Map<Integer,VoxelSectionState.Snapshot>)latest.get(stateField.get(null))).get(slot);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot,data,true,token));
            assertEquals(0, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(), 2),
                    "an old successful-transfer callback cannot restore stale membership");
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot,replacement,true,replacementToken));
        }
        assertEquals(1, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(), 2));
        assertEquals(15, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(), VoxelSourceWindow.CELL_BASE));
        VoxelWindow.invalidateModelData();
        assertEquals(0, VoxelSourceWindowTest.word(sourceWindow.preparePublication().bytes(),2));
    }
    /** The GPU registry cannot allocate outside a client, so pin the production allocation/queue seams. */
    @Test void allocationPublicationAndDiagnosticOffHarvestActivationAreWired() throws Exception {
        String upload = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        assertTrue(upload.contains("registry.ensureBufferSize(VoxelSourceWindow.TARGET, VoxelSourceWindow.BYTE_SIZE)"));
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        assertFalse(graph.contains("captureSourceWindowCamera"));
        assertTrue(graph.contains("|| registry.isEnabledBufferTarget(VoxelSourceWindow.TARGET)"));
        String window = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("EngineBufferUploadQueue.discard(VoxelSourceWindow.TARGET)"));
        assertTrue(window.contains("EngineBufferUploadQueue.publish(VoxelSourceWindow.TARGET"));
        assertTrue(window.contains("sourceWindow.commit(item.slot(), item.result(), item.sectionState())"));
        assertTrue(window.contains("sourceWindow.pending(slot)"));
        assertTrue(window.contains("sourceWindow.invalidate(exposed)"));
    }
    /** A worker upload may land between graph preparation and either source consumer. Refresh
     * under that consumer's existing lock before recording its pending buffer update. */
    @Test void everySourceWindowComputeConsumerRefreshesWithinItsDispatchCriticalSection() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int run = source.indexOf("public long run(");
        int lock = source.indexOf("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)", run);
        int refresh = source.indexOf("VoxelWindow.refreshSourceWindow(registry)", lock);
        int record = source.indexOf("EngineBufferUploadQueue.recordForBindings", lock);
        assertTrue(refresh > lock && refresh < record);
        assertTrue(source.substring(lock, record).contains("bindingOrder.contains(VoxelSourceWindow.TARGET)"));
    }

}
