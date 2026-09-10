package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real CPU admission paths, fake world and no GPU: proves edit ordering, not transfer latency. */
class VoxelWindowEditRevisionTest {
    private final SectionPos position = SectionPos.of(0, 0, 0);
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final BiFunction<Level, SectionPos, SectionHarvester.Result> reader = (level, pos) -> {
        reads.incrementAndGet();
        return DirectSectionReader.emptyResultWithLight(new byte[4096]);
    };
    private final VoxelMeshUpdates updates = new VoxelMeshUpdates(work::add,
            (storage, harvest, level, pos) -> VoxelWindow.harvestMeshRequest(storage, harvest, level, pos, reader),
            (pos, error) -> { throw new AssertionError(error); });

    @BeforeEach void setup() {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of()));
        VoxelWindow.recenter(0, 0, 0, 2);
    }
    @AfterEach void detach() { VoxelWindow.attachRegistry(null); drain(); }
    private void changed() { VoxelWindow.queueMeshTriggeredHarvest(null, position, updates); }
    private void drain() { while (!work.isEmpty()) work.remove().run(); }
    private void backfill(BiFunction<Level, SectionPos, SectionHarvester.Result> source) throws Exception {
        var epoch = VoxelWindow.class.getDeclaredField("storageGeneration");
        epoch.setAccessible(true);
        var harvest = VoxelWindow.class.getDeclaredMethod("harvestAndUploadBatch", Level.class,
                List.class, LongConsumer.class, Runnable.class, long.class, long.class, BiFunction.class, long.class);
        harvest.setAccessible(true);
        harvest.invoke(null, null, List.of(position), (LongConsumer) ignored -> {}, null,
                epoch.getLong(null), VoxelHarvestLifecycle.generation(), source, System.nanoTime());
    }

    @SuppressWarnings("unchecked")
    private void acknowledgeUpload() throws Exception {
        int slot = VoxelWindow.slotFor(position.x(), position.y(), position.z());
        var field = VoxelWindow.class.getDeclaredField("slotData");
        field.setAccessible(true);
        var result = ((Map<Integer, SectionHarvester.Result>) field.get(null)).get(slot);
        // The headless registry has no GPU; model successful fenced completion explicitly.
        VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, result, true));
    }

    @Test void bootstrapThatCoversTheQueuedEditAvoidsASecondWorldReadAndUpload() throws Exception {
        changed();
        backfill(reader);
        assertTrue(VoxelWindow.hasValidData(0, 0, 0));
        acknowledgeUpload();
        drain();
        assertEquals(1, reads.get(), "initial meshing must not repeat a bootstrap read that covered its edit");
    }

    @Test void anEditAfterPopulationStillReadsAndPublishesAgain() throws Exception {
        backfill(reader);
        acknowledgeUpload();
        changed(); drain();
        changed(); drain();
        assertEquals(3, reads.get(), "valid cached geometry does not suppress later edits");
    }

    @Test void anEditDuringBootstrapRejectsThatOldReadBeforeTheMeshWorkerRuns() throws Exception {
        backfill((level, pos) -> {
            var stale = reader.apply(level, pos);
            changed();
            return stale;
        });
        assertFalse(VoxelWindow.hasValidData(0, 0, 0), "old read must not claim the newer edit revision");
        drain();
        assertEquals(2, reads.get());
        assertTrue(VoxelWindow.hasValidData(0, 0, 0));
    }

    @Test void repeatPendingEditIsNotMistakenForTheVersionAlreadyCoveredByBootstrap() throws Exception {
        changed();
        backfill(reader);
        acknowledgeUpload();
        changed();
        drain();
        assertEquals(2, reads.get(), "grouping keeps the newest edit count, not the first");
    }
    @Test void aCpuResultAwaitingOrMissingUploadCannotSuppressTheMeshRequest() throws Exception {
        changed();
        backfill(reader);
        drain();
        assertEquals(2, reads.get(), "only fenced GPU completion consumes a queued edit");
    }

    @Test void aShellClearRetiresConsumedRevisionsEvenWhenReturningToTheSameOwner() throws Exception {
        changed();
        backfill(reader);
        acknowledgeUpload();
        // Leave and return before the pending mesh job starts. A CPU refill of the returned slot
        // must not borrow successful GPU completion from before the shell clear.
        VoxelWindow.recenterAndResync(10, 0, 0, 2, null, 0, 0, 0, work::add, (level, pos) -> null);
        VoxelWindow.recenterAndResync(0, 0, 0, 2, null, 0, 0, 0, work::add,
                (level, pos) -> pos.equals(position) ? reader.apply(level, pos) : null);
        drain();
        assertEquals(3, reads.get(), "original upload, CPU refill, and still-pending edit each need their read");
    }

}
