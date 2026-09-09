package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the real resync/arrival batches with a controlled world reader and executor.
 * The empty registry exercises CPU ownership, cancellation and scheduling, not Vulkan writes.
 * Fabric registration remains a source contract because firing it needs the client runtime. */
class VoxelChunkArrivalTest {
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private int pendingBefore;

    private static TargetRegistry registry() {
        return TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of());
    }

    @BeforeEach void setup() {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(registry());
        pendingBefore = VoxelWindow.pendingSlots();
    }

    @AfterEach void detach() {
        VoxelWindow.attachRegistry(null);
        while (!work.isEmpty()) work.remove().run();
        assertEquals(pendingBefore, VoxelWindow.pendingSlots(), "all submitted positions must drain");
    }

    private static SectionHarvester.Result data() {
        return DirectSectionReader.emptyResultWithLight(new byte[4096]);
    }

    private void drain() { while (!work.isEmpty()) work.remove().run(); }

    private void recenter(int x, BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        VoxelWindow.recenterAndResync(x, 4, 0, 1, null, 0, 0, 0, work::add, reader);
        drain();
    }

    private void arrival(int x, BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        VoxelWindow.onChunkLoaded(null, x, 0, work::add, reader);
    }

    @SuppressWarnings("unchecked")
    private static void acknowledgeCompletedUpload(int x, int y, int z) throws Exception {
        int slot = VoxelWindow.slotFor(x, y, z);
        var field = VoxelWindow.class.getDeclaredField("slotData");
        field.setAccessible(true);
        var result = ((Map<Integer, SectionHarvester.Result>) field.get(null)).get(slot);
        // This headless fixture models completion explicitly; its registry has no GPU buffers.
        VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, result, true, snapshotAt(slot)));
    }

    @Test void clearedReenteredSectionsAreHarvestedAgainWithoutDiagnosticBuffers() {
        recenter(0, (level, pos) -> data());
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
        // Radius one has diameter three: this teleport reuses every toroidal index.
        // Missing replacement chunks leave the old owners in place for light clearing.
        recenter(3, (level, pos) -> null);
        AtomicInteger reads = new AtomicInteger();
        recenter(0, (level, pos) -> { reads.incrementAndGet(); return data(); });
        assertEquals(27, reads.get(), "all 3 cubed cleared slots must be republished on reentry");
        assertEquals(27, VoxelWindow.populatedSlotCount());
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
    }

    @Test void clearingPreservesThePreviousOwnerNeededToClearRecycledLight() throws Exception {
        recenter(0, (level, pos) -> data());
        acknowledgeCompletedUpload(0, 4, 0);
        recenter(3, (level, pos) -> null);
        SectionPos replacement = SectionPos.of(3, 4, 0);
        var record = VoxelWindow.class.getDeclaredMethod("recordHarvest", int.class,
                SectionPos.class, SectionHarvester.Result.class);
        record.setAccessible(true);
        assertEquals(true, record.invoke(null, VoxelWindow.slotFor(3, 4, 0), replacement, data()),
                "forgetting the previous owner would preserve another section's propagated light");
    }

    @Test void chunkArrivalBackfillsAnInitialMissWithoutMovingOrMeshing() {
        recenter(0, (level, pos) -> null);
        assertFalse(VoxelWindow.hasValidData(0, 4, 0));
        var window = VoxelWindow.currentState();
        long clears = VoxelWindow.clearedTotal();
        arrival(0, (level, pos) -> data());
        drain();
        assertTrue(VoxelWindow.hasValidData(0, 4, 0), "arrival must retry the missing geometry");
        assertEquals(3, VoxelWindow.populatedSlotCount(), "one column intersects three section heights");
        assertEquals(window, VoxelWindow.currentState(), "backfill does not move the window");
        assertEquals(clears, VoxelWindow.clearedTotal(), "backfill does not clear existing geometry");
    }

    @Test void queuedDuplicateArrivalsReadEachInvalidSectionOnce() {
        VoxelWindow.recenter(0, 4, 0, 1);
        AtomicInteger reads = new AtomicInteger();
        BiFunction<Level, SectionPos, SectionHarvester.Result> reader = (level, pos) -> {
            reads.incrementAndGet(); return data();
        };
        for (int i = 0; i < 100; i++) arrival(0, reader);
        assertEquals(1, work.size(), "repeated notifications coalesce while queued");
        assertEquals(pendingBefore + 3, VoxelWindow.pendingSlots());
        drain();
        assertEquals(3, reads.get());
        arrival(0, reader);
        assertTrue(work.isEmpty(), "already populated sections need no second harvest");
    }

    @Test void arrivalDuringAnUnavailableReadSchedulesAnotherPass() {
        VoxelWindow.recenter(0, 4, 0, 1);
        AtomicInteger reads = new AtomicInteger();
        BiFunction<Level, SectionPos, SectionHarvester.Result> reader = (level, pos) -> {
            if (reads.incrementAndGet() == 1) arrival(0, (nextLevel, nextPos) -> data());
            return null;
        };
        arrival(0, reader);
        assertEquals(1, work.size());
        work.remove().run();
        assertEquals(1, work.size(), "an arrival during execution must not be lost to deduplication");
        drain();
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
    }

    @Test void aMovedWindowDiscardsQueuedArrivalBeforeReadingTheWorld() {
        VoxelWindow.recenter(0, 4, 0, 1);
        arrival(0, (level, pos) -> { throw new AssertionError("obsolete world read"); });
        VoxelWindow.recenter(3, 4, 0, 1);
        drain();
        assertEquals(0, VoxelWindow.populatedSlotCount());
        assertEquals(pendingBefore, VoxelWindow.pendingSlots());
    }

    @Test void retiredArrivalCannotUnregisterANewGenerationsQueuedColumn() {
        VoxelWindow.recenter(0, 4, 0, 1);
        arrival(0, (level, pos) -> { throw new AssertionError("retired world read"); });
        VoxelWindow.attachRegistry(registry());
        VoxelWindow.recenter(0, 4, 0, 1);
        arrival(0, (level, pos) -> data());
        assertEquals(2, work.size());
        work.remove().run();
        arrival(0, (level, pos) -> data());
        assertEquals(1, work.size(), "old completion must preserve the new generation's dedup key");
        drain();
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
    }

    @Test void arrivalBeforeTheFirstWindowIsCoveredByTheInitialResync() {
        arrival(0, (level, pos) -> { throw new AssertionError("uncentered world read"); });
        assertTrue(work.isEmpty());
        recenter(0, (level, pos) -> data());
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
    }

    @Test void failedReadsDrainPendingAndAllowAnotherArrival() {
        VoxelWindow.recenter(0, 4, 0, 1);
        arrival(0, (level, pos) -> { throw new IllegalStateException("read failed"); });
        assertEquals(1, work.size());
        assertThrows(IllegalStateException.class, work.remove()::run);
        assertEquals(pendingBefore, VoxelWindow.pendingSlots());
        arrival(0, (level, pos) -> data());
        drain();
        assertTrue(VoxelWindow.hasValidData(0, 4, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedSecondReadDoesNotCertifyTheUnsubmittedFirstSection(boolean metadata) throws Exception {
        if (metadata) {
            var graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(new java.io.StringReader(
                    "[targets.voxelSectionState]\nkind = \"buffer\"\n"), "graph.toml");
            VoxelWindow.attachRegistry(TargetRegistry.create(graph, Map.of()));
        }
        VoxelWindow.recenter(0, 4, 0, 1);
        AtomicInteger reads = new AtomicInteger();
        arrival(0, (level, pos) -> {
            if (reads.incrementAndGet() == 1) return data();
            throw new IllegalStateException("second read failed");
        });
        assertThrows(IllegalStateException.class, work.remove()::run);
        assertFalse(VoxelWindow.hasValidData(0, 3, 0), "the first section never reached a batch upload");
        assertEquals(0, VoxelWindow.populatedSlotCount());
        if (metadata) {
            var field = VoxelWindow.class.getDeclaredField("sectionStates");
            field.setAccessible(true);
            var states = (VoxelSectionState) field.get(null);
            assertFalse(states.hasOwner(VoxelWindow.slotFor(0, 3, 0), SectionPos.of(0, 3, 0)),
                    "unsubmitted metadata must lose its authoritative CPU token too");
        }
        assertEquals(pendingBefore, VoxelWindow.pendingSlots());
        reads.set(0);
        arrival(0, (level, pos) -> { reads.incrementAndGet(); return data(); });
        drain();
        assertEquals(3, reads.get(), "retry must include the first, previously unsubmitted section");
        assertTrue(VoxelWindow.hasValidData(0, 3, 0));
    }

    @Test void partialFailurePreservesANewerOwnerOfTheSameSlot() {
        VoxelWindow.recenter(0, 4, 0, 1);
        AtomicInteger reads = new AtomicInteger();
        arrival(0, (level, pos) -> {
            if (reads.incrementAndGet() == 1) return data();
            // A three-section move recycles the first position's toroidal slot for a new owner.
            VoxelWindow.recenter(3, 4, 0, 1);
            VoxelWindow.onSectionHarvested(SectionPos.of(3, 3, 0), data());
            throw new IllegalStateException("read failed after newer publication");
        });
        assertThrows(IllegalStateException.class, work.remove()::run);
        assertTrue(VoxelWindow.hasValidData(3, 3, 0), "recovery must not revoke the newer owner");
        assertEquals(1, VoxelWindow.populatedSlotCount());
    }

    @Test void partialFailureCannotRevokeReplacementStoragesSamePosition() {
        VoxelWindow.recenter(0, 4, 0, 1);
        AtomicInteger reads = new AtomicInteger();
        arrival(0, (level, pos) -> {
            if (reads.incrementAndGet() == 1) return data();
            VoxelWindow.attachRegistry(registry());
            VoxelWindow.recenter(0, 4, 0, 1);
            VoxelWindow.onSectionHarvested(SectionPos.of(0, 3, 0), data());
            throw new IllegalStateException("read failed after storage replacement");
        });
        assertThrows(IllegalStateException.class, work.remove()::run);
        assertTrue(VoxelWindow.hasValidData(0, 3, 0), "old work must not revoke new storage");
        assertEquals(1, VoxelWindow.populatedSlotCount());
    }

    @Test void partialFailureRetainsTheLightClearOwedToThePreviousOwner() throws Exception {
        recenter(0, (level, pos) -> data());
        acknowledgeCompletedUpload(0, 3, 0);
        recenter(3, (level, pos) -> null);
        AtomicInteger reads = new AtomicInteger();
        arrival(3, (level, pos) -> {
            if (reads.incrementAndGet() == 1) return data();
            throw new IllegalStateException("second read failed");
        });
        assertThrows(IllegalStateException.class, work.remove()::run);
        var record = VoxelWindow.class.getDeclaredMethod("recordHarvest", int.class,
                SectionPos.class, SectionHarvester.Result.class);
        record.setAccessible(true);
        assertEquals(true, record.invoke(null, VoxelWindow.slotFor(3, 3, 0), SectionPos.of(3, 3, 0), data()),
                "an unsubmitted replacement must not erase the previous owner's light-clear requirement");
    }

    /** The real uploadSlots entry checks isEmpty before resolving GPU buffers. Observe the
     * actual list passed across that boundary; the empty registry still performs no GPU transfer. */
    private static final class UploadProbe extends ArrayList<BrickGridUpload.SlotUpload> {
        private List<BrickGridUpload.SlotUpload> atUpload;
        @Override public boolean isEmpty() {
            atUpload = new ArrayList<>(this);
            return super.isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static VoxelSectionState.Snapshot snapshotAt(int slot) {
        try {
            var field = VoxelWindow.class.getDeclaredField("sectionStates");
            field.setAccessible(true);
            var latest = VoxelSectionState.class.getDeclaredField("latest");
            latest.setAccessible(true);
            return ((Map<Integer, VoxelSectionState.Snapshot>) latest.get(field.get(null))).get(slot);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,replacement", "true,replacement", "false,reentry", "true,reentry",
            "false,newer_data", "true,newer_data", "false,unchanged", "true,unchanged"})
    void aQueuedHarvestLosesTransferAuthorityWhenItsSlotChanges(boolean metadata, String change) throws Exception {
        if (metadata) {
            var graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(new java.io.StringReader(
                    "[targets.voxelSectionState]\nkind = \"buffer\"\n"), "graph.toml");
            VoxelWindow.attachRegistry(TargetRegistry.create(graph, Map.of()));
        }
        VoxelWindow.recenter(0, 4, 0, 1);
        var generation = VoxelWindow.class.getDeclaredField("storageGeneration");
        generation.setAccessible(true);
        long queuedGeneration = generation.getLong(null);
        SectionHarvester.Result first = data();
        UploadProbe queued = new UploadProbe();
        AtomicInteger reads = new AtomicInteger();
        arrival(0, (level, pos) -> {
            int read = reads.incrementAndGet();
            if (read == 1) return first;
            if (read == 2) {
                // The first read has published its CPU record, but its batch has not flushed.
                int slot = VoxelWindow.slotFor(0, 3, 0);
                queued.add(new BrickGridUpload.SlotUpload(slot, first, false,
                        metadata ? snapshotAt(slot) : null));
                if (change.equals("unchanged")) return null;
                if (change.equals("newer_data")) {
                    VoxelWindow.onSectionHarvested(SectionPos.of(0, 3, 0), data());
                } else {
                    VoxelWindow.recenterAndResync(3, 4, 0, 1, null, 0, 0, 0, work::add,
                            (otherLevel, otherPos) -> null);
                    if (change.equals("replacement")) {
                        VoxelWindow.onSectionHarvested(SectionPos.of(3, 3, 0), data());
                    } else {
                        VoxelWindow.recenterAndResync(0, 4, 0, 1, null, 0, 0, 0, work::add,
                                (otherLevel, otherPos) -> null);
                    }
                }
            }
            return null;
        });
        drain();
        assertEquals(queuedGeneration, generation.getLong(null), "all changes retain the storage generation");
        var flush = VoxelWindow.class.getDeclaredMethod("flushBatch", TargetRegistry.class, List.class,
                VoxelWindow.BatchSizeController.class, long.class);
        flush.setAccessible(true);
        flush.invoke(null, VoxelWindow.attachedRegistry(), queued, new VoxelWindow.BatchSizeController(), queuedGeneration);
        assertTrue(queued.atUpload != null, "the probe must observe the real uploadSlots boundary");
        assertEquals(change.equals("unchanged") ? 1 : 0, queued.atUpload.size(),
                "only geometry that still owns its current slot may reach the uploader");
        if (change.equals("replacement")) assertTrue(VoxelWindow.hasValidData(3, 3, 0));
        else if (change.equals("newer_data") || change.equals("unchanged")) assertTrue(VoxelWindow.hasValidData(0, 3, 0));
        else assertFalse(VoxelWindow.hasValidData(0, 3, 0), "cleared reentry is still awaiting a fresh harvest");
    }

    @Test void aPartialResyncFailureDrainsEveryRemainingQueuedPosition() {
        AtomicInteger reads = new AtomicInteger();
        VoxelWindow.recenterAndResync(0, 4, 0, 1, null, 0, 0, 0, work::add, (level, pos) -> {
            int read = reads.incrementAndGet();
            // The existing synchronous slice consumes 24 positions; radius one has 27 total.
            if (read <= 24) return null;
            if (read == 25) return data();
            throw new IllegalStateException("second async read failed");
        });
        assertEquals(pendingBefore + 3, VoxelWindow.pendingSlots());
        assertThrows(IllegalStateException.class, work.remove()::run);
        assertEquals(pendingBefore, VoxelWindow.pendingSlots(), "failed resync must drain its unread tail too");
        assertEquals(0, VoxelWindow.populatedSlotCount(), "the unsubmitted prefix must lose authority");
    }

    @Test void theClientInitializerRegistersVoxelChunkArrival() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/FornaxMod.java"));
        assertTrue(source.contains("ClientChunkEvents.CHUNK_LOAD.register"),
                "the backfill callback must run when a client chunk becomes available");
        assertTrue(source.contains("VoxelWindow.onChunkLoaded"));
    }
}
