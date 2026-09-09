package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks CPU ownership across a GPU storage swap. It does not stand in for GPU allocation. */
class VoxelWindowStorageTest {
    private static final BiFunction<Level, SectionPos, SectionHarvester.Result> READER = DirectSectionReader::read;
    private static TargetRegistry registry() {
        return TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of());
    }

    @AfterEach void detach() { VoxelWindow.attachRegistry(null); }

    private static void seedOwner() throws Exception { seedOwner(32, 3, -55); }

    @SuppressWarnings("unchecked")
    private static void seedOwner(int x, int y, int z) throws Exception {
        VoxelWindow.recenter(x, y, z, 4);
        int slot = VoxelWindow.slotFor(x, y, z);
        var owner = VoxelWindow.class.getDeclaredField("slotOwner");
        owner.setAccessible(true);
        ((Map<Integer, SectionPos>) owner.get(null)).put(slot, SectionPos.of(x, y, z));
        var populated = VoxelWindow.class.getDeclaredField("populatedSlots");
        populated.setAccessible(true);
        ((Set<Integer>) populated.get(null)).add(slot);
    }

    @Test void replacementRegistryForcesFullScanAtTheSameCamera() throws Exception {
        VoxelWindow.attachRegistry(registry());
        seedOwner();
        assertTrue(VoxelWindow.hasValidData(32, 3, -55));
        VoxelWindow.attachRegistry(registry());
        assertEquals(0, VoxelWindow.currentState().radius(), "replacement storage needs first-enable scan");
        VoxelWindow.recenter(32, 3, -55, 4);
        assertFalse(VoxelWindow.hasValidData(32, 3, -55), "old GPU ownership cannot survive replacement");
        assertEquals(0.0, VoxelWindow.populationFraction());
    }

    @Test void detachInvalidatesOwnershipBeforeASecondEnable() throws Exception {
        VoxelWindow.attachRegistry(registry());
        seedOwner();
        VoxelWindow.attachRegistry(null);
        assertEquals(0, VoxelWindow.currentState().radius());
        VoxelWindow.recenter(32, 3, -55, 4);
        assertFalse(VoxelWindow.hasValidData(32, 3, -55));
    }
    @Test void resizeInvalidatesCoincidentToroidalOwnerIndices() throws Exception {
        TargetRegistry r = registry();
        VoxelWindow.attachRegistry(r);
        // Section zero maps to slot zero at BOTH diameters: changed addressing cannot hide stale owners.
        seedOwner(0, 0, 0);
        assertTrue(VoxelWindow.hasValidData(0, 0, 0));
        assertEquals(0, VoxelWindow.slotFor(0, 0, 0));
        VoxelWindow.initializeStorage(r, 17);
        assertEquals(0, VoxelWindow.currentState().radius());
        VoxelWindow.recenter(0, 0, 0, 8);
        assertEquals(0, VoxelWindow.slotFor(0, 0, 0));
        assertFalse(VoxelWindow.hasValidData(0, 0, 0));
        assertEquals(0.0, VoxelWindow.populationFraction());
    }

    @Test void queuedOldGenerationCannotHarvestIntoReplacementStorage() throws Exception {
        VoxelWindow.attachRegistry(registry());
        var epoch = VoxelWindow.class.getDeclaredField("storageGeneration");
        epoch.setAccessible(true);
        long queuedGeneration = epoch.getLong(null);
        VoxelWindow.attachRegistry(registry());
        VoxelWindow.recenter(32, 3, -55, 4);
        var harvest = VoxelWindow.class.getDeclaredMethod("harvestAndUploadBatch",
                net.minecraft.world.level.Level.class, List.class,
                java.util.function.LongConsumer.class, Runnable.class, long.class, long.class, BiFunction.class, long.class);
        harvest.setAccessible(true);
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        // A stale queued task must drain its counts without touching the old world, null here.
        harvest.invoke(null, null, List.of(SectionPos.of(32, 3, -55)),
                (java.util.function.LongConsumer) count -> { throw new AssertionError("stale harvest"); },
                (Runnable) processed::incrementAndGet, queuedGeneration, VoxelHarvestLifecycle.generation(), READER, System.nanoTime());
        assertEquals(1, processed.get());
        assertFalse(VoxelWindow.hasValidData(32, 3, -55));
    }

    @Test void queuedSectionsOutsideTheMovedWindowDrainWithoutReadingTheWorld() throws Exception {
        VoxelWindow.attachRegistry(registry());
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.recenter(0, 4, 0, 2);
        // These positions were eligible when queued; the teleport leaves all of them behind.
        List<SectionPos> queued = List.of(SectionPos.of(0, 4, 0), SectionPos.of(2, 4, 0),
                SectionPos.of(-2, 4, 0));
        var epoch = VoxelWindow.class.getDeclaredField("storageGeneration");
        epoch.setAccessible(true);
        long queuedGeneration = epoch.getLong(null);
        long queuedHarvestGeneration = VoxelHarvestLifecycle.generation();
        VoxelWindow.recenter(50, 4, 50, 2);
        assertEquals(queuedGeneration, epoch.getLong(null), "movement keeps the storage generation");
        assertTrue(VoxelHarvestLifecycle.isCurrent(queuedHarvestGeneration));
        for (SectionPos pos : queued) {
            assertEquals(-1, VoxelWindow.slotFor(pos.x(), pos.y(), pos.z()));
            assertFalse(VoxelWindow.hasValidData(pos.x(), pos.y(), pos.z()));
        }
        var harvest = VoxelWindow.class.getDeclaredMethod("harvestAndUploadBatch",
                net.minecraft.world.level.Level.class, List.class,
                java.util.function.LongConsumer.class, Runnable.class, long.class, long.class, BiFunction.class, long.class);
        harvest.setAccessible(true);
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        // Null is a forbidden-world-read sentinel, as in the old-generation tests above.
        harvest.invoke(null, null, queued,
                (java.util.function.LongConsumer) count -> { throw new AssertionError("out-of-window harvest"); },
                (Runnable) processed::incrementAndGet, queuedGeneration, queuedHarvestGeneration, READER, System.nanoTime());
        assertEquals(queued.size(), processed.get(), "discarded work must still drain pending counts");
        assertEquals(0.0, VoxelWindow.populationFraction());
    }

    @Test void queuedOldModelsCannotTouchTheCapturedWorldAfterPublication() throws Exception {
        long queuedHarvestGeneration = VoxelHarvestLifecycle.generation();
        VoxelHarvestLifecycle.onBlockAtlasRetired();
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(registry());
        VoxelWindow.recenter(32, 3, -55, 4);
        var epoch = VoxelWindow.class.getDeclaredField("storageGeneration");
        epoch.setAccessible(true);
        var harvest = VoxelWindow.class.getDeclaredMethod("harvestAndUploadBatch",
                net.minecraft.world.level.Level.class, List.class,
                java.util.function.LongConsumer.class, Runnable.class, long.class, long.class, BiFunction.class, long.class);
        harvest.setAccessible(true);
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        // Use CURRENT storage to isolate the lifetime guard. Reading this old null world would
        // throw; the queued generation must cancel before DirectSectionReader touches it.
        harvest.invoke(null, null, List.of(SectionPos.of(32, 3, -55)),
                (java.util.function.LongConsumer) count -> { throw new AssertionError("stale model harvest"); },
                (Runnable) processed::incrementAndGet, epoch.getLong(null), queuedHarvestGeneration, READER, System.nanoTime());
        assertEquals(1, processed.get());
        assertFalse(VoxelWindow.hasValidData(32, 3, -55));
    }

    @Test void reattachingTheSameStorageRetainsPublishedOwnership() throws Exception {
        TargetRegistry r = registry();
        VoxelWindow.attachRegistry(r);
        seedOwner();
        VoxelWindow.attachRegistry(r);
        assertEquals(4, VoxelWindow.currentState().radius());
        assertTrue(VoxelWindow.hasValidData(32, 3, -55));
    }

    @Test void workerCannotClaimTheUncenteredSentinelSlot() {
        VoxelWindow.attachRegistry(registry());
        // Null would throw in recordHarvest: a callback before the first recenter must not publish.
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), null);
        assertFalse(VoxelWindow.hasValidData(0, 0, 0));
    }

    @Test void atlasGenerationChangeInvalidatesQueuedTokensAtAnUnchangedCamera() throws Exception {
        var graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.voxelSourceSummary]
                kind = "buffer"
                """), "graph.toml");
        TargetRegistry r = TargetRegistry.create(graph, Map.of());
        VoxelWindow.attachRegistry(r);
        assertTrue(VoxelWindow.synchronizeSourceGeneration(r, 8));
        VoxelWindow.recenter(0, 0, 0, 4);
        var field = VoxelWindow.class.getDeclaredField("sectionStates");
        field.setAccessible(true);
        var states = (VoxelSectionState) field.get(null);
        var queued = states.geometry(0, SectionPos.of(0, 0, 0));
        assertFalse(VoxelWindow.synchronizeSourceGeneration(r, 8), "same generation keeps the populated window");
        assertTrue(VoxelWindow.isCurrentSectionState(0, queued));
        assertTrue(VoxelWindow.synchronizeSourceGeneration(r, 9));
        assertEquals(0, VoxelWindow.currentState().radius(), "force resync with an unchanged camera");
        VoxelWindow.recenter(0, 0, 0, 4);
        assertFalse(VoxelWindow.isCurrentSectionState(0, queued));
        var staleResult = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()),
                new byte[4096], new VoxelSourceSummary(8, 0, 0, 0, 0, 0, 0));
        assertFalse(VoxelWindow.hasCurrentSourceSummary(staleResult));
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), staleResult);
        assertFalse(VoxelWindow.hasValidData(0, 0, 0), "a late old-atlas result must not claim the new storage");
        assertEquals(0, VoxelWindow.sourceInventoryStats().committedSlots());
        assertEquals(1, VoxelWindow.sourceInventoryStats().staleUploads());
    }

    @Test void disabledSourceInventoryDoesNotResetTheOrdinaryVoxelWindow() throws Exception {
        TargetRegistry r = registry();
        VoxelWindow.attachRegistry(r);
        seedOwner();
        assertFalse(VoxelWindow.synchronizeSourceGeneration(r, 81));
        assertTrue(VoxelWindow.hasValidData(32, 3, -55));
    }

}
