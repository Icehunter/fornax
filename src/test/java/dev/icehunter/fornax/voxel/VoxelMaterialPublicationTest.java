package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.icehunter.fornax.voxel.VoxelSourceWindowTest.word;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelMaterialPublicationTest {
    @AfterEach void resumeAndDetach() {
        VoxelWindow.attachRegistry(null);
        VoxelHarvestLifecycle.onModelsPublished();
    }

    @Test void publicationRetiresBeforeDrainingReadersAndPublishesWithoutTheQueueLock() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        long generation = VoxelHarvestLifecycle.generation();
        var published = new AtomicBoolean();
        CompletableFuture<Void> update;
        try (var lease = VoxelHarvestLifecycle.tryAcquire(generation)) {
            assertNotNull(lease);
            update = CompletableFuture.runAsync(() -> VoxelHarvestLifecycle.publishMaterials(() -> {
                assertFalse(Thread.holdsLock(VulkanComputeBackend.SHARED_QUEUE_LOCK));
                assertFalse(VoxelHarvestLifecycle.isAvailable());
                assertNull(VoxelHarvestLifecycle.tryAcquire());
                published.set(true);
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (VoxelHarvestLifecycle.isAvailable() && !update.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(VoxelHarvestLifecycle.isCurrent(generation));
            assertFalse(published.get(), "material maps must not change under an old reader");
            assertFalse(update.isDone());
        }
        update.get(5, TimeUnit.SECONDS);
        assertTrue(published.get());
        assertTrue(VoxelHarvestLifecycle.isAvailable());
        assertNull(VoxelHarvestLifecycle.tryAcquire(generation));
    }

    @Test void materialPublicationNeverReopensAnAtlasThatIsStillRetired() {
        VoxelHarvestLifecycle.onBlockAtlasRetired();
        VoxelHarvestLifecycle.publishMaterials(() -> { });
        assertFalse(VoxelHarvestLifecycle.isAvailable());
        assertNull(VoxelHarvestLifecycle.tryAcquire());
    }

    @Test void queueLockOwnersCannotEnterTheBlockingPublicationBoundary() {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            assertThrows(IllegalStateException.class, () -> VoxelHarvestLifecycle.publishMaterials(() -> { }));
        }
    }

    @Test void aReaderCannotUpgradeItselfToAMaterialPublication() {
        VoxelHarvestLifecycle.onModelsPublished();
        try (var lease = VoxelHarvestLifecycle.tryAcquire()) {
            assertNotNull(lease);
            assertThrows(IllegalStateException.class, () -> VoxelHarvestLifecycle.publishMaterials(() -> { }));
            assertTrue(VoxelHarvestLifecycle.isCurrent(lease.generation()));
        }
    }

    @Test void unchangedRegistryReloadRevokesRowsAndOldHarvestsBeforeExcludedGeometryCommits() throws Exception {
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
        var original = VoxelEmitterPoolTest.result(7, 0);
        commit(original);
        var window = sourceWindow();
        // A row is one run, so its low bits hold that run's one face, not the entry's six.
        assertEquals(1 | (7 << 8) | (63 << 16), word(window.preparePublication().bytes(), VoxelSourceWindow.CELL_BASE + 4));
        VoxelHarvestLifecycle.publishMaterials(() -> {
            assertEquals(0, word(window.preparePublication().bytes(), 2));
        });
        assertSame(registry, VoxelWindow.attachedRegistry());
        assertEquals(0, VoxelWindow.currentState().radius(), "same-camera refill must run again");
        VoxelWindow.synchronizeSourceGeneration(registry, 7); // Next-frame source preparation keeps this registry.
        VoxelWindow.recenter(0, 0, 0, 1);
        assertFalse(VoxelWindow.hasCurrentSourceSummary(original));
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), original);
        assertEquals(0, word(window.preparePublication().bytes(), 2));
        var fresh = VoxelEmitterPoolTest.result(7, 0);
        var excluded = new SectionHarvester.Result(fresh.paletteIndices(), fresh.palette(), fresh.lightmap(),
                fresh.sourceSummary(), fresh.harvestGeneration(), fresh.sourceEvidence(), new VoxelSourcePolicy(0, 0, 2, true));
        assertTrue(VoxelWindow.hasCurrentSourceSummary(excluded));
        commit(excluded);
        var bytes = window.preparePublication().bytes();
        assertEquals(0, word(bytes, 2));
        assertTrue(VoxelWindow.hasValidData(0, 0, 0), "source exclusion preserves harvested geometry");
        assertEquals(7, excluded.sourceEvidence().intrinsicEmission(1));
    }

    private static VoxelSourceWindow sourceWindow() throws Exception {
        var field = VoxelWindow.class.getDeclaredField("sourceWindow"); field.setAccessible(true);
        return (VoxelSourceWindow)field.get(null);
    }
    @SuppressWarnings("unchecked")
    private static void commit(SectionHarvester.Result data) throws Exception {
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), data);
        int slot = VoxelWindow.slotFor(0, 0, 0);
        var states = VoxelWindow.class.getDeclaredField("sectionStates"); states.setAccessible(true);
        var latest = VoxelSectionState.class.getDeclaredField("latest"); latest.setAccessible(true);
        var token = ((Map<Integer, VoxelSectionState.Snapshot>)latest.get(states.get(null))).get(slot);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            // The headless registry cannot transfer; model the existing successful-transfer callback.
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, data, true, token));
        }
    }
}
