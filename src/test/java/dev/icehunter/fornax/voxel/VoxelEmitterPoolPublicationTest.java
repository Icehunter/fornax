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
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelEmitterPoolPublicationTest {
    @AfterEach void detach() { VoxelWindow.attachRegistry(null); }

    @SuppressWarnings("unchecked")
    @Test void queuedGeometryCannotEnterThePoolAndRetirementDropsCommittedReferences() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        var graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelEmitterPool]
                kind = "buffer"
                [targets.voxelSectionState]
                kind = "buffer"
                [targets.voxelSourceSummary]
                kind = "buffer"
                """), "graph.toml");
        var registry = TargetRegistry.create(graph, Map.of());
        VoxelWindow.attachRegistry(registry);
        VoxelWindow.synchronizeSourceGeneration(registry, 7);
        VoxelWindow.recenter(0, 0, 0, 1);
        var result = VoxelEmitterPoolTest.result(7, 0, 1);
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), result);
        assertEquals(0, VoxelWindow.emitterPoolStats().eligible(), "empty GPU registry has not committed geometry");
        int slot = VoxelWindow.slotFor(0, 0, 0);
        var states = VoxelWindow.class.getDeclaredField("sectionStates"); states.setAccessible(true);
        var latest = VoxelSectionState.class.getDeclaredField("latest"); latest.setAccessible(true);
        var token = ((Map<Integer, VoxelSectionState.Snapshot>) latest.get(states.get(null))).get(slot);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            // Explicit successful-fence model, not an assertion that this empty registry wrote GPU bytes.
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, result, true, token));
        }
        assertEquals(12, VoxelWindow.emitterPoolStats().eligible());
        assertEquals(1, VoxelWindow.emitterPoolStats().committedSlots());
        VoxelWindow.prepareEmitterPool(registry);
        assertEquals(0, VoxelWindow.emitterPoolStats().publications(), "unallocated destination cannot accept a publication");
        VoxelWindow.invalidateModelData();
        assertEquals(0, VoxelWindow.emitterPoolStats().committedSlots());
        assertEquals(0, VoxelWindow.emitterPoolStats().stored());
    }

    /** Runtime allocation, F10 and the Vulkan command stream require a client; pin real wiring. */
    @Test void graphPrepareAllocationInvalidationAndComputeBarrierAreConnected() throws Exception {
        String window = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("emitterPool.commit(item.slot(), item.result(), item.sectionState())"));
        assertTrue(window.contains("emitterPool.invalidate(exposed)"));
        assertTrue(window.contains("EngineBufferUploadQueue.discard(VoxelEmitterPool.TARGET)"));
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        assertTrue(graph.contains("VoxelWindow.prepareEmitterPool(registry)"));
        String upload = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        assertTrue(upload.contains("registry.ensureBufferSize(VoxelEmitterPool.TARGET, VoxelEmitterPool.BYTE_SIZE)"));
        String queue = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/EngineBufferUploadQueue.java"));
        int barrier = queue.indexOf("VkBufferMemoryBarrier.calloc");
        assertTrue(barrier >= 0 && barrier < queue.indexOf("VK13.vkCmdFillBuffer"));
        assertTrue(queue.contains(".srcAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT)"));
        assertTrue(queue.contains(".dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)"));
        assertTrue(queue.contains(".buffer(buffer.vkBuffer())"));
        String debug = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pass/voxel/VoxelDebugRaymarchPass.java"));
        for (String name : new String[]{"source_pool_stored", "source_pool_deferred", "source_pool_rebuilding", "source_pool_publications"})
            assertTrue(debug.contains(name));
    }
}
