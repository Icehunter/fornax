package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelMeshUpdatesTest {
    private static final SectionPos SECTION = SectionPos.of(3, 4, 5);

    @AfterEach
    void detachWindow() {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(null);
    }

    private static TargetRegistry registry() {
        return TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of());
    }

    private static VoxelMeshUpdates liveUpdates(ControlledExecutor executor,
                                                BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        return new VoxelMeshUpdates(executor,
                (storage, harvest, level, section, requestedAt) ->
                        VoxelWindow.harvestMeshRequest(storage, harvest, level, section, reader, requestedAt),
                (section, error) -> { throw new AssertionError("unexpected mesh reader failure", error); });
    }

    private static void readyWindow() {
        VoxelWindow.attachRegistry(registry());
        VoxelWindow.recenter(SECTION.x(), SECTION.y(), SECTION.z(), 1);
    }

    @Test
    void duplicateBurstBeforeTheReadRunsOnlyOnce() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> reads.add(section),
                (section, error) -> { });

        for (int i = 0; i < 1_000; i++) updates.request(1, 1, null, SECTION, 0L);

        assertEquals(1, executor.size(), "one queued request must represent a mesh burst for one section");
        executor.runAll();
        assertEquals(List.of(SECTION), reads, "the one read observes the live state when the worker reaches it");
    }

    @Test
    void newestEditRunsBeforeAStartupBacklog() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> reads.add(section),
                (section, error) -> { });
        SectionPos edited = SectionPos.of(0, 0, 0);

        for (int x = 0; x < 15_000; x++) updates.request(1, 1, null, SectionPos.of(x, 0, 0), 0L);
        updates.request(1, 1, null, edited, 0L);

        assertEquals(1, executor.size(), "the entire backlog must be represented by one drain");
        executor.runNext();
        assertEquals(edited, reads.getFirst(), "a repeated pending edit must overtake bootstrap requests");
        assertEquals(15_000, reads.size(), "the drain must still eventually process every distinct section");
    }

    @Test
    void repeatingAPendingRequestPromotesItWithoutAddingADrain() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> reads.add(section),
                (section, error) -> { });
        SectionPos first = SectionPos.of(1, 0, 0);
        SectionPos promoted = SectionPos.of(2, 0, 0);

        updates.request(1, 1, null, first, 0L);
        updates.request(1, 1, null, promoted, 0L);
        updates.request(1, 1, null, promoted, 0L);

        assertEquals(1, executor.size(), "repeating a pending section must not enqueue another drain");
        executor.runNext();
        assertEquals(List.of(promoted, first), reads);
    }

    @Test
    void liveMeshUpdateProgressesWhileBackfillExecutorIsOccupied() {
        var resyncExecutor = new ControlledExecutor();
        var meshExecutor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = new VoxelMeshUpdates(meshExecutor, (storage, harvest, level, section, requestedAt) -> reads.add(section),
                (section, error) -> { });
        resyncExecutor.execute(() -> { }); // a full-window tail is still waiting

        updates.request(1, 1, null, SECTION, 0L);
        meshExecutor.runAll();

        assertEquals(List.of(SECTION), reads, "live edits need their own worker, not the backfill queue");
        assertEquals(1, resyncExecutor.size(), "the mesh request must not consume or wait on backfill work");
    }

    @Test
    void editArrivingWhileReadIsRunningGetsExactlyOneFollowUp() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        final VoxelMeshUpdates[] holder = new VoxelMeshUpdates[1];
        holder[0] = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> {
            reads.add(section);
            if (reads.size() == 1) holder[0].request(1, 1, null, section, 0L);
        }, (section, error) -> { });

        holder[0].request(1, 1, null, SECTION, 0L);
        executor.runNext();

        assertEquals(List.of(SECTION, SECTION), reads,
                "an edit during a read must get exactly one follow-up read");
        assertEquals(0, executor.size(), "the one drain must consume its own follow-up");
    }

    @Test
    void followUpFromTheActiveReadOutranksOlderPendingWork() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        SectionPos active = SectionPos.of(1, 0, 0);
        SectionPos older = SectionPos.of(2, 0, 0);
        final VoxelMeshUpdates[] holder = new VoxelMeshUpdates[1];
        holder[0] = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> {
            reads.add(section);
            if (reads.size() == 1) {
                holder[0].request(1, 1, null, older, 0L);
                holder[0].request(1, 1, null, active, 0L);
            }
        }, (section, error) -> { });

        holder[0].request(1, 1, null, active, 0L);
        executor.runNext();

        assertEquals(List.of(active, active, older), reads,
                "a live edit during the active read must be processed before older queued work");
    }

    @Test
    void replacementGenerationForAPendingSectionSuppressesTheOldRequest() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<Long>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> reads.add(storage),
                (section, error) -> { });

        updates.request(1, 1, null, SECTION, 0L);
        updates.request(2, 2, null, SECTION, 0L);
        executor.runNext();

        assertEquals(List.of(2L), reads,
                "a newer generation must replace the pending request instead of running both generations");
    }

    @Test
    void resetOldJobCannotRemoveNewGenerationRequest() {
        var executor = new ControlledExecutor();
        var reads = new ArrayList<Long>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> reads.add(storage),
                (section, error) -> { });

        updates.request(1, 1, null, SECTION, 0L);
        updates.reset();
        updates.request(2, 2, null, SECTION, 0L);
        executor.runNext(); // the stale request completes after the reset
        updates.request(2, 2, null, SECTION, 0L);
        assertEquals(1, executor.size(), "stale cleanup must not remove the replacement request");
        executor.runAll();

        assertEquals(List.of(2L), reads, "reset work must be discarded before it reaches the costly reader");
    }

    @Test
    void failedReadReleasesTheSectionForLaterMeshUpdates() {
        var executor = new ControlledExecutor();
        var errors = new ArrayList<Throwable>();
        var updates = new VoxelMeshUpdates(executor, (storage, harvest, level, section, requestedAt) -> {
            throw new IllegalStateException("fixture failure");
        }, (section, error) -> errors.add(error));

        updates.request(1, 1, null, SECTION, 0L);
        executor.runAll();
        updates.request(1, 1, null, SECTION, 0L);

        assertEquals(1, errors.size(), "the error is reported rather than killing the only worker");
        assertEquals(1, executor.size(), "a later mesh update must not be poisoned by the failure");
    }

    @Test
    void voxelWindowWiresLiveMeshEventsToTheDedicatedCoalescingWorker() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(source.contains("new VoxelMeshUpdates(MESH_HARVEST_EXECUTOR"),
                "mesh events must not join the resync queue");
        assertTrue(source.contains("queueMeshTriggeredHarvest(level, position, meshUpdates);"),
                "the live hook must go through the grouping queue");
        assertTrue(source.contains("slotFor(position.x(), position.y(), position.z()) < 0"),
                "outside-window mesh events must be discarded before scheduling a read");
    }

    @Test
    void voxelWindowCoalescesRepeatedLiveEventsBeforeTheReaderStarts() {
        readyWindow();
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = liveUpdates(executor, (level, section) -> {
            reads.add(section);
            return null;
        });

        for (int i = 0; i < 1_000; i++) VoxelWindow.queueMeshTriggeredHarvest(null, SECTION, updates);
        assertEquals(1, executor.size());
        executor.runAll();

        assertEquals(List.of(SECTION), reads);
    }

    @Test
    void retiredStorageAndModelsPreventQueuedLiveRequestFromReading() {
        readyWindow();
        var executor = new ControlledExecutor();
        var reads = new ArrayList<SectionPos>();
        var updates = liveUpdates(executor, (level, section) -> {
            reads.add(section);
            return null;
        });
        VoxelWindow.queueMeshTriggeredHarvest(null, SECTION, updates);
        VoxelHarvestLifecycle.onBlockAtlasRetired();
        VoxelHarvestLifecycle.onModelsPublished();

        executor.runAll();
        assertTrue(reads.isEmpty(), "out-of-date work must be dropped before touching the held world");
    }

    @Test
    void outsideWindowEventDoesNotScheduleALiveRead() {
        readyWindow();
        var executor = new ControlledExecutor();
        var updates = liveUpdates(executor, (level, section) -> {
            throw new AssertionError("outside-window event must not read");
        });

        VoxelWindow.queueMeshTriggeredHarvest(null, SectionPos.of(99, SECTION.y(), 99), updates);

        assertEquals(0, executor.size());
    }

    @Test
    void windowMoveDuringLiveReadPreventsTheOldResultFromPublishing() {
        readyWindow();
        var executor = new ControlledExecutor();
        var result = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        var updates = liveUpdates(executor, (level, section) -> {
            VoxelWindow.recenter(99, SECTION.y(), 99, 1);
            return result;
        });

        VoxelWindow.queueMeshTriggeredHarvest(null, SECTION, updates);
        executor.runAll();

        assertFalse(VoxelWindow.hasValidData(SECTION.x(), SECTION.y(), SECTION.z()),
                "a result whose section left the window while reading must not publish");
    }

    @Test
    void storageReplacementDuringLiveReadPreventsTheOldResultFromPublishing() {
        readyWindow();
        var executor = new ControlledExecutor();
        var result = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        var updates = liveUpdates(executor, (level, section) -> {
            VoxelWindow.attachRegistry(null);
            VoxelWindow.attachRegistry(registry());
            VoxelWindow.recenter(SECTION.x(), SECTION.y(), SECTION.z(), 1);
            return result;
        });

        VoxelWindow.queueMeshTriggeredHarvest(null, SECTION, updates);
        executor.runAll();

        assertFalse(VoxelWindow.hasValidData(SECTION.x(), SECTION.y(), SECTION.z()),
                "a result from before a storage reset must not publish");
    }

    @Test
    void aFoldedRequestKeepsTheWaitTheFirstOneStarted() {
        VoxelMeshHarvestTelemetry.LIVE.reset();
        var executor = new ControlledExecutor();
        var seen = new ArrayList<Long>();
        var updates = new VoxelMeshUpdates(executor,
                (storage, harvest, level, section, requestedAt) -> seen.add(requestedAt),
                (section, error) -> { });
        updates.request(1, 1, null, SECTION, 100L);
        updates.request(1, 1, null, SECTION, 500L);
        updates.request(1, 1, null, SECTION, 900L);
        executor.runAll();
        assertEquals(List.of(100L), seen, "the wait belongs to the first request, not the last");
        assertEquals(2, VoxelMeshHarvestTelemetry.LIVE.coalesced());
        VoxelMeshHarvestTelemetry.LIVE.reset();
    }

    @Test
    void aSpanIsOnlyCountedWhenItsSlotCommits() {
        VoxelMeshHarvestTelemetry.LIVE.reset();
        var telemetry = VoxelMeshHarvestTelemetry.LIVE;
        telemetry.submitting(7, 0, 0, 0, System.nanoTime(), 0L, 0L);
        telemetry.committed(9); // another slot's upload says nothing about this one
        assertEquals(0, telemetry.jobs());
        telemetry.committed(7);
        assertEquals(1, telemetry.jobs());
        telemetry.committed(7); // the span is spent, and a second fence cannot count it twice
        assertEquals(1, telemetry.jobs());
        telemetry.reset();
    }

    @Test
    void aReplacedHarvestLeavesNoSpanBehind() {
        VoxelMeshHarvestTelemetry.LIVE.reset();
        var telemetry = VoxelMeshHarvestTelemetry.LIVE;
        telemetry.submitting(3, 0, 0, 0, System.nanoTime(), 0L, 0L);
        telemetry.dropped(3);
        telemetry.committed(3);
        assertEquals(0, telemetry.jobs());
        assertEquals(0, telemetry.maxCommitNanos());
        telemetry.reset();
    }

    @Test
    void theWaitBeforeARebuildIsMeasuredFromTheFirstTimeTheSectionWasMarked() throws Exception {
        var telemetry = VoxelMeshHarvestTelemetry.LIVE;
        telemetry.reset();
        telemetry.dirtied(4, 5, 6);
        Thread.sleep(12);
        telemetry.dirtied(4, 5, 6); // marked again before its rebuild; the first stamp stands
        telemetry.requested(4, 5, 6);
        telemetry.submitting(11, 4, 5, 6, System.nanoTime(), 0L, 0L);
        telemetry.committed(11);
        assertEquals(1, telemetry.jobs());
        assertTrue(telemetry.maxDirtyNanos() >= 10_000_000L,
                "the wait spans both marks, not just the last one");
        telemetry.reset();
    }

    @Test
    void aSectionNoRebuildEverReachesLeavesNoWaitBehind() {
        var telemetry = VoxelMeshHarvestTelemetry.LIVE;
        telemetry.reset();
        telemetry.dirtied(1, 2, 3);
        telemetry.submitting(12, 9, 9, 9, System.nanoTime(), 0L, 0L);
        telemetry.committed(12);
        assertEquals(0, telemetry.maxDirtyNanos(), "another section's mark is not this one's wait");
        telemetry.reset();
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queued.addLast(command); }
        int size() { return queued.size(); }
        void runNext() { queued.removeFirst().run(); }
        void runAll() { while (!queued.isEmpty()) runNext(); }
    }
}
