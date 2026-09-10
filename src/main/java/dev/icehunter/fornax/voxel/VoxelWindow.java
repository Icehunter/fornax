package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;

/**
 * A camera-relative toroidal window of harvested section data. Sized in sections (bricks); the
 * window's radius is set by whatever caller tracks render distance (not owned by this class -- it
 * only manages the mapping and slot validity once told a radius). Toroidal addressing means a window
 * shift only invalidates the shell of slots that actually left the window; slots that remain
 * in-window keep their existing data untouched. {@link #recenterAndResync} implements exactly this:
 * on an incremental camera move it harvests only the newly-exposed shell (the set-difference of the
 * new window cube minus the old one), falling back to a full-window scan only when the move is too
 * large to overlap the old window or the radius changed (see {@link #enumerateResyncShell}).
 *
 * <p>The owner and data maps are concurrent because read-only checks run beside harvesting.
 * Publishing a slot and swapping the GPU storage both take {@code
 * VulkanComputeBackend.SHARED_QUEUE_LOCK}, and queued work carries the storage generation it was
 * submitted under, so a task from old storage cannot touch the new buffers.
 *
 * <p>The window's geometry ({@code centerX}/{@code centerY}/{@code centerZ}/{@code radius}/{@code
 * diameter}) is subject to the same cross-thread access: {@link #recenter} writes it from the main
 * thread while {@link #slotFor} (and transitively {@link #hasValidData}) reads it from Sodium's
 * worker threads. Five separate non-volatile scalar fields would give worker threads
 * no happens-before guarantee of ever observing an update, and -- even if updates were observed -- no
 * guarantee of observing all five consistently (e.g. an old {@code radius} paired with a new {@code
 * diameter}), which could silently corrupt a different, still-valid slot. To avoid this, the geometry
 * is collapsed into a single immutable {@link WindowState} snapshot published through one {@code
 * volatile} reference: {@link #recenter} performs one atomic volatile write of a brand-new snapshot,
 * and every reader takes exactly one volatile read into a local variable at the top of its method,
 * guaranteeing an internally-consistent view for that method's whole execution.
 */
public final class VoxelWindow {
    /** Immutable snapshot of the window's geometry. Grouping these fields lets every reader take a
     * single atomic (volatile) read and see a value that can never be torn across a concurrent
     * {@link #recenter} call. Public so callers outside this class (e.g. {@code GraphRunner}, sizing
     * a pack-authored compute pass's DDA bound push constants) can read the current geometry via
     * {@link #currentState()} without this class exposing a mutable setter. */
    public record WindowState(int centerX, int centerY, int centerZ, int radius, int diameter) {
        static WindowState of(int centerX, int centerY, int centerZ, int radius) {
            return new WindowState(centerX, centerY, centerZ, radius, 2 * radius + 1);
        }
    }

    /** The never-centered sentinel: radius 0 is impossible for a real window (the debug pass always
     * clamps radius to at least 1), so the first {@link #recenterAndResync} sees a radius change and
     * takes the full-window-scan path, correctly populating the whole initial window. */
    private static volatile WindowState state = WindowState.of(0, 0, 0, 0);

    // Take the upload lock so a checked batch cannot meet a resize before it writes.
    private static volatile long storageGeneration;
    private static final VoxelLightUpdates lightUpdates = new VoxelLightUpdates();
    private record MeshChange(SectionPos owner, long revision) { }
    // One entry per slot, so the window bounds them. A request bumps the count before any read, a
    // CPU harvest records the count it saw, and only a finished GPU upload marks that count done.
    // A filled CPU slot on its own cannot drop a queued edit while its upload has not landed.
    private static final Map<Integer, MeshChange> meshChanges = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> slotReadRevision = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> slotCommittedReadRevision = new ConcurrentHashMap<>();
    private static long meshChangeRevision;

    private static long meshRevisionAt(SectionPos position) {
        MeshChange change = meshChanges.get(slotFor(position.x(), position.y(), position.z()));
        return change != null && change.owner().equals(position) ? change.revision() : 0;
    }
    // Only queued columns are deduplicated. Once a reader starts, a new arrival must be
    // allowed to queue a follow-up in case the current read observed the chunk before arrival.
    private record ChunkLoad(long storageGeneration, long harvestGeneration, int x, int z) { }
    private static final Set<ChunkLoad> queuedChunkLoads = new HashSet<>();
    private record UnpublishedHarvest(int slot, SectionPos owner, SectionHarvester.Result result,
                                      @Nullable SectionPos previousOwner,
                                      SectionHarvester.@Nullable Result previousData,
                                      @Nullable Long previousReadRevision) { }
    private static final VoxelSectionState sectionStates = new VoxelSectionState();
    private static final VoxelSourceInventory sourceInventory = new VoxelSourceInventory();
    private static @Nullable VoxelEmitterPool emitterPool;
    private static @Nullable VoxelSourceWindow sourceWindow;
    private static long sourceAtlasGeneration = -1; // No synchronized atlas generation yet.

    private static void invalidateStorage() {
        storageGeneration = Math.incrementExact(storageGeneration);
        meshUpdates.reset();
        meshChanges.clear();
        slotReadRevision.clear();
        slotCommittedReadRevision.clear();
        sectionStates.reset(storageGeneration);
        sourceInventory.reset();
        sourceAtlasGeneration = -1;
        lightUpdates.clear();
        queuedChunkLoads.clear();
        state = WindowState.of(0, 0, 0, 0);
        slotOwner.clear();
        slotLightOwner.clear();
        slotData.clear();
        populatedSlots.clear();
        if (sourceWindow != null) {
            sourceWindow.reset(Math.toIntExact(storageGeneration), 0);
            EngineBufferUploadQueue.discard(VoxelSourceWindow.TARGET);
        }
        if (emitterPool != null) {
            emitterPool.reset(Math.toIntExact(storageGeneration), 0);
            EngineBufferUploadQueue.discard(VoxelEmitterPool.TARGET);
            publishEmitterPoolLocked(); // Retire a pending full pool even if its consumer skipped a frame.
        }
    }

    /** Cancels queued world reads and cached results when CPU atlas/model ownership changes.
     * Call only after harvest leases have drained, never while holding a harvest lease. */
    static void invalidateModelData() {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            invalidateStorage();
        }
    }

    /** Swaps or resizes the storage and its ownership in one go, before any worker can publish. */
    public static void initializeStorage(TargetRegistry newRegistry, int diameter) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            invalidateStorage();
            registry = null;
            BrickGridUpload.ensureAllocated(newRegistry, diameter);
            BrickGridUpload.invalidateSectionStates(newRegistry);
            registry = newRegistry;
            configureEmitterPool(newRegistry);
        }
    }

    /** Called before consuming graph passes. Atlas publication can change independently of graph
     * storage, so a generation change clears metadata and retires every queued upload token. The
     * radius-zero sentinel makes the next streaming update resync even at an unchanged camera. */
    public static boolean synchronizeSourceGeneration(TargetRegistry newRegistry, long atlasGeneration) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (!newRegistry.isEnabledBufferTarget(VoxelSourceSummary.TARGET)
                    && !newRegistry.isEnabledBufferTarget(VoxelSourceWindow.TARGET)) return false;
            if (registry == newRegistry && sourceAtlasGeneration == atlasGeneration) return false;
            invalidateStorage();
            registry = newRegistry;
            BrickGridUpload.invalidateSectionStates(newRegistry, atlasGeneration);
            // Publish the synchronized CPU generation only after the reset transfer succeeds.
            // A failed reset leaves the sentinel so the next frame retries instead of accepting it.
            sourceAtlasGeneration = atlasGeneration;
            if (sourceWindow != null) sourceWindow.reset(Math.toIntExact(storageGeneration), atlasGeneration);
            if (emitterPool != null) {
                emitterPool.reset(Math.toIntExact(storageGeneration), atlasGeneration);
                publishEmitterPoolLocked();
            }
            return true;
        }
    }

    /** Committed per-slot CPU inventory and upload counters; no harvesting backlog is included. */
    public static VoxelSourceInventory.Stats sourceInventoryStats() {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            return sourceInventory.stats();
        }
    }

    public static VoxelEmitterPool.Stats emitterPoolStats() {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            return emitterPool == null ? VoxelEmitterPool.EMPTY_STATS : emitterPool.stats();
        }
    }

    /** Runs before the first consuming compute pass. Only allocated/enabled pools do scan work. */
    public static void prepareEmitterPool(TargetRegistry currentRegistry) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry != currentRegistry || emitterPool == null
                    || currentRegistry.getBuffer(VoxelEmitterPool.TARGET) == null) return;
            emitterPool.advance(VoxelEmitterPool.CELL_SCAN_BUDGET);
            publishEmitterPoolLocked();
        }
    }

    /** Publish changed source membership before any compute pass that reads it records its upload
     * and dispatch, under the same queue lock it already holds. Each consumer keeps a matching
     * range/row snapshot and checks every source against current section ownership before using it. */
    public static void refreshSourceWindow(TargetRegistry currentRegistry) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry != currentRegistry || sourceWindow == null
                    || currentRegistry.getBuffer(VoxelSourceWindow.TARGET) == null) return;
            var publication = sourceWindow.preparePublication();
            if (publication == null) return;
            EngineBufferUploadQueue.publish(VoxelSourceWindow.TARGET, false,
                    VoxelEmitterPoolUpload.ranges(publication.bytes()));
            sourceWindow.markPublished(publication);
        }
    }

    private static void configureEmitterPool(@Nullable TargetRegistry currentRegistry) {
        EngineBufferUploadQueue.discard(VoxelSourceWindow.TARGET);
        sourceWindow = currentRegistry != null && currentRegistry.isEnabledBufferTarget(VoxelSourceWindow.TARGET)
                ? new VoxelSourceWindow() : null;
        if (sourceWindow != null) sourceWindow.reset(Math.toIntExact(storageGeneration), Math.max(0, sourceAtlasGeneration));
        EngineBufferUploadQueue.discard(VoxelEmitterPool.TARGET);
        emitterPool = currentRegistry != null && currentRegistry.isEnabledBufferTarget(VoxelEmitterPool.TARGET)
                ? new VoxelEmitterPool() : null;
        if (emitterPool != null) emitterPool.reset(Math.toIntExact(storageGeneration), Math.max(0, sourceAtlasGeneration));
    }

    private static void publishEmitterPoolLocked() {
        if (emitterPool != null && registry != null && registry.getBuffer(VoxelEmitterPool.TARGET) != null)
            VoxelEmitterPoolUpload.publish(emitterPool);
    }

    private static boolean sectionMetadataEnabled(TargetRegistry currentRegistry) {
        return currentRegistry.isEnabledBufferTarget(VoxelSectionState.TARGET)
                || currentRegistry.isEnabledBufferTarget(VoxelSourceSummary.TARGET);
    }

    static boolean hasCurrentSourceSummary(SectionHarvester.Result result) {
        // CPU model lifetime is mandatory even when both diagnostic buffers are disabled.
        if (!VoxelHarvestLifecycle.isCurrent(result.harvestGeneration())) return false;
        TargetRegistry r = registry;
        return r == null || (!r.isEnabledBufferTarget(VoxelSourceSummary.TARGET)
                && !r.isEnabledBufferTarget(VoxelSourceWindow.TARGET))
                || result.sourceSummary().atlasGeneration() == sourceAtlasGeneration;
    }

    static void onSectionUploadCommitted(BrickGridUpload.SlotUpload item) {
        if (!isCurrentUpload(item) || !hasCurrentSourceSummary(item.result())
                || (item.sectionState() != null && !isCurrentSectionState(item.slot(), item.sectionState()))) return;
        SectionPos owner = slotOwner.get(item.slot());
        Long readRevision = slotReadRevision.get(item.slot());
        if (readRevision != null) slotCommittedReadRevision.put(item.slot(), readRevision);
        if (item.clearLight() || !needsLightClear(item.slot(), owner)) slotLightOwner.put(item.slot(), owner);
        if (item.sectionState() != null) {
            sectionStates.commit(item.slot(), item.sectionState());
            if (registry != null && registry.isEnabledBufferTarget(VoxelSourceSummary.TARGET))
                sourceInventory.commit(item.slot(), item.result().sourceSummary(), item.result().sourceEvidence());
            if (emitterPool != null) emitterPool.commit(item.slot(), item.result(), item.sectionState());
            if (sourceWindow != null) sourceWindow.commit(item.slot(), item.result(), item.sectionState());
        }
        VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.COMMITTED);
    }

    private static boolean needsLightClear(int slot, SectionPos owner) {
        SectionPos previous = slotLightOwner.get(slot);
        return previous == null || !previous.equals(owner);
    }

    private static boolean isCurrentUpload(BrickGridUpload.SlotUpload item) {
        SectionPos owner = slotOwner.get(item.slot());
        return populatedSlots.contains(item.slot()) && slotData.get(item.slot()) == item.result()
                && owner != null && slotFor(owner.x(), owner.y(), owner.z()) == item.slot();
    }

    static void onSectionUploadDropped() { sourceInventory.dropped(); }

    /** The window's current geometry snapshot -- one volatile read, safe to call from any thread
     * (mirrors every other reader here, see the class javadoc). Used by {@code GraphRunner} to size a
     * pack-authored compute pass's (e.g. {@code rt_shadow}) push-constant DDA bounds; before the
     * window is ever centered this returns the sentinel (radius 0, diameter 1) rather than null. */
    public static WindowState currentState() {
        return state;
    }

    /** Dedicated single background thread that runs the shell enumeration + per-section harvest + GPU
     * upload that {@link #recenterAndResync} dispatches off the render thread. A single thread is
     * deliberate: it serializes all resync tasks against each other in submission order with no extra
     * locking, and it bounds queue depth by the number of render-thread {@code recenterAndResync}
     * calls issued while the thread was still busy (proportional to elapsed frames, never to shell
     * size -- each call submits exactly ONE task that loops over its own shell internally). The thread
     * is a daemon so it never blocks JVM shutdown; the executor is intentionally never shut down (this
     * class has process-static lifetime like the maps and registry it feeds). */
    private static final ExecutorService RESYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fornax-voxel-resync");
        t.setDaemon(true);
        return t;
    });

    /** Live mesh edits must not wait behind a full-window backfill or light-only refresh. */
    private static final ExecutorService MESH_HARVEST_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fornax-voxel-mesh-harvest");
        t.setDaemon(true);
        return t;
    });

    private static final VoxelMeshUpdates meshUpdates = new VoxelMeshUpdates(MESH_HARVEST_EXECUTOR,
            (storageGeneration, harvestGeneration, level, position) ->
                    harvestMeshRequest(storageGeneration, harvestGeneration, level, position, DirectSectionReader::read),
            (position, error) ->
            dev.icehunter.fornax.FornaxMod.LOGGER.error("Voxel mesh harvest failed for {}", position, error));

    /** How many sections a recenter reads on the render thread before handing the rest to
     * {@link #RESYNC_EXECUTOR}. Near sections come first in every direction. The rest share GPU
     * uploads in batches sized from measured work. This caps how much work the frame does, not how
     * long the GPU takes to finish. */
    private static final int SYNC_BUDGET = 24;

    /** Latest CPU section owner for each toroidal slot. Validity also requires populated membership;
     * a shell clear retains this record but revokes the geometry until it is harvested again. */
    private static final Map<Integer, SectionPos> slotOwner = new ConcurrentHashMap<>();
    // Advances only after an accepted fenced upload, never when CPU geometry is queued.
    // Missing ownership requires a clear: a CPU reset may retain the actual GPU light allocation.
    private static final Map<Integer, SectionPos> slotLightOwner = new ConcurrentHashMap<>();
    private static final Map<Integer, SectionHarvester.Result> slotData = new ConcurrentHashMap<>();

    // --- Streaming telemetry -------------------------------------------------------------------------
    // Cheap CPU-side counters, mutated on the exact same paths as the streaming state above (no new
    // synchronization: increments/decrements land on the render thread inside recenterAndResync, or on
    // RESYNC_EXECUTOR's single background thread inside its own task loop -- never both for the same
    // counter at the same time except pendingSlots, which is a plain AtomicInteger for that reason).
    // Fed once per frame into GraphRunner's FrameProfiler by VoxelDebugRaymarchPass#onFrame (see its
    // own doc) as HUD value rows, so a live screenshot can distinguish harvest backlog (pendingSlots
    // staying high) from per-frame churn (clearedTotal climbing while the camera is stationary) from
    // submission cadence (syncHarvestedTotal/asyncHarvestedTotal).

    /** Slots currently queued on {@link #RESYNC_EXECUTOR}, awaiting their async {@link
     * #harvestAndUploadBatch} pass -- incremented by a deferred batch's size right before {@code execute()}
     * submits it, decremented once per position as the background thread actually reaches it (whether
     * or not that position turned out to already be valid / unloaded -- either way it is no longer
     * "awaiting"). */
    private static final AtomicInteger pendingSlots = new AtomicInteger();
    /** Total slots ever harvested SYNCHRONOUSLY (render thread, inside {@link #SYNC_BUDGET}), across
     * this session -- monotonic; {@link dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass} turns
     * this into a per-frame delta for the HUD. */
    private static final AtomicLong syncHarvestedTotal = new AtomicLong();
    /** Total slots ever harvested by {@link #RESYNC_EXECUTOR}'s background thread, across this
     * session -- monotonic, same delta treatment as {@link #syncHarvestedTotal}. */
    private static final AtomicLong asyncHarvestedTotal = new AtomicLong();
    /** Total slots ever occupancy-CLEARED by {@link #recenterAndResync}'s shell-clear, across this
     * session -- monotonic. A nonzero per-frame delta while the camera is not crossing a section
     * boundary means something outside this method is re-clearing slots -- the signal this counter
     * exists to catch. */
    private static final AtomicLong clearedTotal = new AtomicLong();
    /** The set of toroidal slot indices that currently hold real, harvested (not merely cleared)
     * geometry -- added in {@link #onSectionHarvested} (a harvest publish), removed in {@link
     * #recenterAndResync} for every slot the shell-clear zeroes. Its SIZE, divided by the
     * window's {@code diameter^3}, is the population fraction {@link #populationFraction()} reports;
     * a {@link ConcurrentHashMap}-backed set since {@link #onSectionHarvested} runs on Sodium's worker
     * threads as well as the render/resync threads. */
    private static final Set<Integer> populatedSlots = ConcurrentHashMap.newKeySet();

    /** Current async-harvest queue depth -- see {@link #pendingSlots}'s own doc. */
    public static int pendingSlots() {
        return pendingSlots.get();
    }

    /** Cumulative (session-lifetime) synchronous-harvest count -- see {@link #syncHarvestedTotal}'s
     * own doc. Callers wanting a per-frame count take the delta between two calls themselves (see
     * {@link dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass}). */
    public static long syncHarvestedTotal() {
        return syncHarvestedTotal.get();
    }

    /** Cumulative (session-lifetime) async-harvest count -- see {@link #asyncHarvestedTotal}'s own
     * doc. */
    public static long asyncHarvestedTotal() {
        return asyncHarvestedTotal.get();
    }

    /** Cumulative (session-lifetime) occupancy-clear count -- see {@link #clearedTotal}'s own doc. */
    public static long clearedTotal() {
        return clearedTotal.get();
    }

    /** How many toroidal slots currently hold real harvested geometry -- see {@link #populatedSlots}'s
     * own doc. */
    public static int populatedSlotCount() {
        return populatedSlots.size();
    }

    /** Pure population-fraction math -- occupied-harvested slots divided by the window's total slot
     * count ({@code diameter^3}) -- pulled out of the live counters above so it is unit-testable
     * (VoxelWindowTest) without touching this class's global static state. Returns {@code 0.0} for a
     * non-positive diameter (the never-centered sentinel, or any degenerate caller) rather than
     * dividing by zero. */
    static double populationFraction(int populatedCount, int diameter) {
        long total = (long) diameter * diameter * diameter;
        if (total <= 0) {
            return 0.0;
        }
        return (double) populatedCount / total;
    }

    /** Live population fraction for the window's CURRENT geometry -- one volatile read of {@link
     * #state} plus {@link #populatedSlots}'s size, run through {@link #populationFraction(int, int)}. */
    public static double populationFraction() {
        WindowState s = state;
        return populationFraction(populatedSlots.size(), s.diameter());
    }

    /** The live {@link TargetRegistry} owning the brick-grid buffers this window uploads into --
     * the SAME instance {@link dev.icehunter.fornax.pack.graph.GraphRunner} itself owns, not a second
     * one (mirrors how {@code GraphRunner}'s own {@code computeBackend} is a cached static). Set when
     * {@code GraphRunner} calls {@link #attachRegistry} as its registry (re)builds; {@code null} (the
     * default, and whenever no pack is active) makes {@link #onSectionHarvested}'s upload call a
     * no-op, not a crash. */
    @Nullable
    private static volatile TargetRegistry registry;

    private VoxelWindow() {
    }

    /** Called once from wherever {@link dev.icehunter.fornax.pack.graph.GraphRunner} already has a
     * live {@link TargetRegistry} -- lets {@link #onSectionHarvested}, invoked from Sodium's worker
     * threads, reach the same registry instance without this class owning or constructing one itself.
     * Pass {@code null} to detach (e.g. on pack unload). */
    public static void attachRegistry(@Nullable TargetRegistry newRegistry) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry != newRegistry) {
                invalidateStorage();
                registry = newRegistry;
                configureEmitterPool(newRegistry);
            }
        }
    }

    /** The registry currently attached (the one {@link #onSectionHarvested} uploads into), or {@code
     * null} if none. A single volatile read; lets the debug raymarch pass reach the same instance
     * {@link #attachRegistry} was handed without threading it through a second field. */
    @Nullable
    public static TargetRegistry attachedRegistry() {
        return registry;
    }

    /** Same emptiness test {@link #onSectionHarvested} applies to its result, exposed so a caller can
     * skip the harvest itself rather than discard it afterward: two volatile reads, cheap enough to
     * call from Sodium's meshing hot path before paying for a real model/texel walk that nothing will
     * ever consume. */
    public static boolean needsHarvest() {
        return registry != null && state.radius() != 0;
    }

    /** Moves the window's origin. Does no allocation/harvesting work -- just one atomic volatile
     * publish of the new geometry snapshot (see class javadoc). Stale slots are discovered lazily by
     * {@link #slotFor}/{@link #hasValidData} comparing {@code slotOwner} against the section each slot
     * SHOULD hold post-recenter; to also harvest the newly-exposed shell in the same step, use
     * {@link #recenterAndResync}. */
    public static void recenter(int newCenterX, int newCenterY, int newCenterZ, int newRadius) {
        state = WindowState.of(newCenterX, newCenterY, newCenterZ, newRadius);
    }

    /** Returns the toroidal slot index for {@code (sectionX, sectionY, sectionZ)} if it's within the
     * current window, else {@code -1}. Does NOT guarantee the slot's data is up to date -- callers
     * needing guaranteed-fresh data should also check {@link #hasValidData}. */
    public static int slotFor(int sectionX, int sectionY, int sectionZ) {
        return slotFor(state, sectionX, sectionY, sectionZ);
    }

    /** Pure core of {@link #slotFor(int, int, int)}, taking the window geometry explicitly rather than
     * reading the mutable {@link #state} field -- lets {@link #exposedSlots} compute slot indices
     * against an arbitrary (e.g. not-yet-published) {@link WindowState} without a live volatile read,
     * and makes {@link #exposedSlots} itself unit-testable with no dependency on call ordering against
     * {@link #recenter}. */
    private static int slotFor(WindowState local, int sectionX, int sectionY, int sectionZ) {
        int dx = sectionX - local.centerX(), dy = sectionY - local.centerY(), dz = sectionZ - local.centerZ();
        if (Math.abs(dx) > local.radius() || Math.abs(dy) > local.radius() || Math.abs(dz) > local.radius()) {
            return -1;
        }
        int diameter = local.diameter();
        int wrappedX = Math.floorMod(sectionX, diameter);
        int wrappedY = Math.floorMod(sectionY, diameter);
        int wrappedZ = Math.floorMod(sectionZ, diameter);
        return (wrappedY * diameter + wrappedZ) * diameter + wrappedX;
    }

    public static boolean hasValidData(int sectionX, int sectionY, int sectionZ) {
        int slot = slotFor(sectionX, sectionY, sectionZ);
        if (slot < 0) {
            return false;
        }
        SectionPos expected = SectionPos.of(sectionX, sectionY, sectionZ);
        TargetRegistry r = registry;
        // A shell clear retains the CPU owner record but revokes geometry validity,
        // including with diagnostic metadata off. Light ownership is tracked separately.
        return populatedSlots.contains(slot) && expected.equals(slotOwner.get(slot))
                && (r == null || !sectionMetadataEnabled(r)
                    || sectionStates.hasOwner(slot, expected));
    }

    public static void onSectionHarvested(SectionPos position, SectionHarvester.Result result) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry == null || state.radius() == 0) {
                return; // no storage mapping exists until the first real camera recenter
            }
            int slot = slotFor(position.x(), position.y(), position.z());
            if (slot < 0) {
                return; // outside the window: drop it, it comes back if the window reaches it
            }
            if (!hasCurrentSourceSummary(result)) {
                sourceInventory.dropped();
                return;
            }
            // A live edit publishes right away on the mesh worker. A first mesh event whose
            // newest count backfill already finished is dropped before the world read.
            boolean clearLight = recordHarvest(slot, position, result);

            TargetRegistry r = registry;
            var snapshot = sectionMetadataEnabled(r) ? sectionStates.geometry(slot, position) : null;
            if (snapshot != null) clearLight |= sectionStates.needsLightClear(slot, snapshot);
            // Every mesh publication shares the fenced payload/light completion boundary.
            BrickGridUpload.uploadSlots(r, List.of(new BrickGridUpload.SlotUpload(slot, result, clearLight, snapshot)));
        }
    }

    /** Queues a mesh-triggered harvest on {@link #MESH_HARVEST_EXECUTOR}, away from the resync and
     * light worker. Repeat events for one section share one request until a read starts, and an
     * event during that read leaves one more behind it. New requests go ahead of the world-join
     * backlog. The harvest reads vanilla's section storage and sorts the fixed baked parts it
     * collects into the palette. It does not run from Sodium's meshing task, but makes no promise
     * that Sodium or any block-model work on other threads is held apart. See
     * {@code docs/ARCHITECTURE.md} section 12. */
    public static void queueMeshTriggeredHarvest(Level level, SectionPos position) {
        queueMeshTriggeredHarvest(level, position, meshUpdates);
    }

    /** Seam for the queueing gates: a test can pass its own worker without touching the real
     * one. */
    static void queueMeshTriggeredHarvest(Level level, SectionPos position, VoxelMeshUpdates updates) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (!needsHarvest() || !VoxelHarvestLifecycle.isAvailable()
                    || slotFor(position.x(), position.y(), position.z()) < 0) {
                return; // matches onSectionHarvested's own gate: nothing will read the result
            }
            int slot = slotFor(position.x(), position.y(), position.z());
            meshChangeRevision = Math.incrementExact(meshChangeRevision);
            meshChanges.put(slot, new MeshChange(position, meshChangeRevision));
            updates.request(storageGeneration, VoxelHarvestLifecycle.generation(), level, position);
        }
    }

    /** Seam that keeps the real gates around a reader a queue test can supply. */
    static void harvestMeshRequest(long expectedStorageGeneration, long expectedHarvestGeneration,
                                   Level level, SectionPos position,
                                   BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        final long revision;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (!isCurrentMeshRequest(expectedStorageGeneration, expectedHarvestGeneration, position)) return;
            revision = meshRevisionAt(position);
            int slot = slotFor(position.x(), position.y(), position.z());
            if (hasValidData(position.x(), position.y(), position.z())
                    && Long.valueOf(revision).equals(slotCommittedReadRevision.get(slot))) return;
        }
        SectionHarvester.Result result;
        try (var lease = VoxelHarvestLifecycle.tryAcquire(expectedHarvestGeneration)) {
            if (lease == null || !isCurrentMeshRequest(expectedStorageGeneration, expectedHarvestGeneration, position)) return;
            result = reader.apply(level, position);
        }
        if (result == null) return;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (isCurrentMeshRequest(expectedStorageGeneration, expectedHarvestGeneration, position)
                    && meshRevisionAt(position) == revision) {
                onSectionHarvested(position, result);
            }
        }
    }

    private static boolean isCurrentMeshRequest(long expectedStorageGeneration, long expectedHarvestGeneration,
                                                SectionPos position) {
        return storageGeneration == expectedStorageGeneration
                && VoxelHarvestLifecycle.isCurrent(expectedHarvestGeneration)
                && registry != null && state.radius() != 0
                && slotFor(position.x(), position.y(), position.z()) >= 0;
    }

    /** A loaded column retries missing geometry even if the camera and terrain meshes stay still.
     * Arrival before the first recenter needs no work here: that recenter reads the loaded chunk. */
    public static void onChunkLoaded(Level level, int chunkX, int chunkZ) {
        onChunkLoaded(level, chunkX, chunkZ, RESYNC_EXECUTOR, DirectSectionReader::read);
    }

    static void onChunkLoaded(Level level, int chunkX, int chunkZ, Executor executor,
                              BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        final ChunkLoad request;
        final List<SectionPos> positions = new ArrayList<>();
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            WindowState window = state;
            if (registry == null || window.radius() == 0 || !VoxelHarvestLifecycle.isAvailable()
                    || slotFor(window, chunkX, window.centerY(), chunkZ) < 0) return;
            request = new ChunkLoad(storageGeneration, VoxelHarvestLifecycle.generation(), chunkX, chunkZ);
            if (queuedChunkLoads.contains(request)) return;
            for (int y = window.centerY() - window.radius(); y <= window.centerY() + window.radius(); y++) {
                if (!hasValidData(chunkX, y, chunkZ)) positions.add(SectionPos.of(chunkX, y, chunkZ));
            }
            if (positions.isEmpty()) return;
            queuedChunkLoads.add(request);
            pendingSlots.addAndGet(positions.size());
        }
        long queuedAt = System.nanoTime();
        executor.execute(() -> {
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                queuedChunkLoads.remove(request);
            }
            harvestAndUploadBatch(level, positions, asyncHarvestedTotal::addAndGet,
                    pendingSlots::decrementAndGet, request.storageGeneration(), request.harvestGeneration(), reader, queuedAt);
        });
    }

    /** Light changes with no mesh rebuild and off screen. Gathered until the next frame. */
    public static void onSectionLightChanged(SectionPos position) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry != null && registry.isEnabledBufferTarget(VoxelLightmap.TARGET)
                    && slotFor(position.x(), position.y(), position.z()) >= 0)
                lightUpdates.changed(position);
        }
    }

    /** Called every active voxel frame, even with the camera still. Runs on the resync worker, so
     * the render thread never waits, and can queue behind resync work. */
    public static void refreshLightmaps(Level level) {
        final long generation;
        final TargetRegistry capturedRegistry;
        final Object worker;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            capturedRegistry = registry;
            if (capturedRegistry == null || !capturedRegistry.isEnabledBufferTarget(VoxelLightmap.TARGET)) return;
            generation = storageGeneration;
            worker = lightUpdates.beginWork();
        }
        if (worker == null) return;
        RESYNC_EXECUTOR.execute(() -> {
            try {
                final List<SectionPos> positions;
                synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                    if (generation != storageGeneration || capturedRegistry != registry) return;
                    // Take the list when the worker starts, so a busy resync queues one worker.
                    positions = new ArrayList<>(lightUpdates.drain(p -> slotFor(p.x(), p.y(), p.z()) >= 0));
                }
                // The geometry uploader's starting batch size, rather than a second rule.
                for (int start=0; start<positions.size(); start+=BatchSizeController.INITIAL_TARGET) {
                    var sampled = new java.util.LinkedHashMap<SectionPos, byte[]>();
                    int end = Math.min(positions.size(), start+BatchSizeController.INITIAL_TARGET);
                    for (SectionPos position : positions.subList(start,end)) {
                        if (generation != storageGeneration || capturedRegistry != registry) return;
                        if (!hasValidData(position.x(),position.y(),position.z())) continue;
                        try {
                            sampled.put(position, VoxelLightmap.capture(level,
                                    position.minBlockX(),position.minBlockY(),position.minBlockZ()));
                        } catch (RuntimeException error) {
                            onSectionLightChanged(position);
                            dev.icehunter.fornax.FornaxMod.LOGGER.error("Voxel world-light refresh failed for {}", position, error);
                        }
                    }
                    synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                        if (generation != storageGeneration || capturedRegistry != registry) return;
                        List<BrickGridUpload.SlotUpload> uploads = new ArrayList<>();
                        for (var sample : sampled.entrySet()) {
                            SectionPos position = sample.getKey();
                            int slot = slotFor(position.x(),position.y(),position.z());
                            if (slot < 0 || !hasValidData(position.x(),position.y(),position.z())) continue;
                            SectionHarvester.Result previous = slotData.get(slot);
                            if (previous == null) continue;
                            var updated = previous.withLightmap(sample.getValue());
                            slotData.put(slot, updated);
                            var snapshot = sectionMetadataEnabled(capturedRegistry)
                                    ? sectionStates.content(slot) : null;
                            boolean clearLight = needsLightClear(slot, position)
                                    || (snapshot != null && sectionStates.needsLightClear(slot, snapshot));
                            uploads.add(new BrickGridUpload.SlotUpload(slot,updated,clearLight,snapshot));
                        }
                        BrickGridUpload.uploadSlots(capturedRegistry,uploads);
                    }
                }
            } finally {
                lightUpdates.endWork(worker);
            }
        });
    }

    /** Recenters the window and dispatches harvest of the slots that the move newly exposed -- the
     * render-thread entry point called on every section-boundary cross. The geometry update ({@link
     * #recenter}) runs synchronously on the render thread (a single cheap volatile publish), but the
     * shell enumeration + per-section {@link DirectSectionReader#read} + GPU {@link
     * BrickGridUpload#uploadSlot} are handed to {@link #RESYNC_EXECUTOR} and run on its background
     * thread, so this method returns to the caller immediately regardless of how large the shell is --
     * a frame is never blocked on harvest/upload cost.
     *
     * <p>For the common incremental move (the new center still within the old window's bounds) the
     * dispatched task touches only the newly-exposed shell, NOT the whole {@code diameter^3} cube:
     * sections that stay in-window keep their existing valid data (their toroidal slot index is
     * center-independent, so it is unchanged). Only a move too large to overlap the old window, a
     * radius change, or the first-enable sentinel triggers a full-window scan (see {@link
     * #enumerateResyncShell}).
     *
     * <p><b>Eventual consistency.</b> Because {@link #RESYNC_EXECUTOR} is single-threaded, tasks run
     * one at a time in submission order. A second {@code recenterAndResync} issued before a prior
     * task finishes simply queues another task scoped to ITS OWN captured (old, new) transition, so
     * every section that is ever newly exposed at any point along the movement is still enqueued for
     * harvest exactly once per transition -- nothing is silently skipped. The tradeoff is latency: a
     * newly-exposed section may show stale/missing data for a few extra frames until its queued task
     * runs, rather than being guaranteed-fresh the same frame. A stale task cannot corrupt a valid
     * slot: {@link #harvestAndUploadBatch}/{@link
     * #onSectionHarvested} re-map the absolute section through the CURRENT window at write time and
     * discard (via {@code slotFor < 0}) anything no longer in-window, and the toroidal invariant
     * guarantees at most one in-window section maps to any given slot.
     *
     * <p><b>Slot staleness.</b> "Stale/missing" above could otherwise mean the DDA reads a
     * newly-exposed slot's leftover bytes as real, in-place geometry -- the toroidal slot's
     * occupancy/payload/palette keep whatever a previous, unrelated section left there the last time
     * the window scrolled through that slot index (buffers are zero-cleared only at allocation, never
     * per-resync). A ray marching into one of these slots before the harvest above lands would render
     * that previous section's geometry "teleported" into the new slot's world-space position. This
     * method closes that gap synchronously, on the render thread, before the harvest above is even
     * dispatched: every slot the move newly exposes gets its GPU occupancy zeroed via {@link
     * BrickGridUpload#clearOccupancySlots} (one batched, fenced submit -- see its own doc for why
     * occupancy alone is sufficient and why it is GPU-visible before this method returns). Until the
     * async harvest overwrites it for real, the slot reads as empty -> sky fallback, trading a
     * transient pop-to-sky for eliminating the displaced-geometry artifact. See {@link
     * DirectSectionReader}'s class doc for the read-side hazard this closes.
     *
     * <p><b>Prioritized budgeted harvest.</b> Walk the shell once and sort nearest first; camera
     * facing only breaks ties between sections the same distance away. A light or blocker close by
     * but off screen still changes what is on screen. The first {@link #SYNC_BUDGET} sections read
     * here on the render thread and the rest read on the background resync worker. Every newly
     * exposed slot is cleared at once, and a section the game has not loaded yet stays waiting even
     * when it fell inside that first group.
     *
     * <p><b>Batched harvest upload.</b> Both the synchronous slice and the async tail-drain
     * harvest+upload through {@link #harvestAndUploadBatch} rather than one fenced GPU round trip per
     * position: harvested slots accumulate into a batch that flushes as one {@link
     * BrickGridUpload#uploadSlots} submission once it reaches an adaptively-sized target -- see {@link
     * BatchSizeController} -- rather than a hardcoded count or one submit per slot. This lets the worst
     * case (the very first {@code recenterAndResync}, a full-window scan of thousands of sections, all
     * deferred to {@link #RESYNC_EXECUTOR} past the {@link #SYNC_BUDGET} slice) drain in large,
     * CPU-work-budgeted batches instead of thousands of individually fenced round trips, while the
     * common steady-state case (a handful of slots per section-cross) still flushes promptly since the
     * deferred list's own end always triggers a final flush of whatever didn't reach the adaptive
     * target.
     *
     * <p>Must be called from the render/main thread (it captures the old {@link WindowState} and
     * publishes the new one). The captured {@code level} reference is read from both this thread (the
     * synchronous portion) and the background thread (the deferred remainder); that is safe for the
     * same reason Sodium's own chunk-build workers read block data off-thread (the lock-free {@code
     * PalettedContainer} read path never trips a threading detector -- see {@link SectionHarvester} /
     * {@link DirectSectionReader}). {@code forwardX}/{@code forwardY}/{@code forwardZ} is the camera's
     * current look direction (need not be normalized -- only its sign relative to each candidate slot
     * matters, see {@link #isFront}), used to break ties between sections the same distance away in
     * the shell; passing a zero vector degrades
     * gracefully to nearest-first-only ordering ({@link #isFront} treats a zero dot product as front). */
    public static void recenterAndResync(int newCenterX, int newCenterY, int newCenterZ, int newRadius, Level level,
                                          float forwardX, float forwardY, float forwardZ) {
        recenterAndResync(newCenterX, newCenterY, newCenterZ, newRadius, level,
                forwardX, forwardY, forwardZ, RESYNC_EXECUTOR, DirectSectionReader::read);
    }

    static void recenterAndResync(int newCenterX, int newCenterY, int newCenterZ, int newRadius, Level level,
                                 float forwardX, float forwardY, float forwardZ, Executor executor,
                                 BiFunction<Level, SectionPos, SectionHarvester.Result> reader) {
        if (!VoxelHarvestLifecycle.isAvailable()) return;
        final long generation;
        final long harvestGeneration;
        List<SectionPos> shell = new ArrayList<>();
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (!VoxelHarvestLifecycle.isAvailable()) return;
            generation = storageGeneration;
            harvestGeneration = VoxelHarvestLifecycle.generation();
            WindowState previous = state;                                       // OLD geometry, captured BEFORE the overwrite (render thread)
            recenter(newCenterX, newCenterY, newCenterZ, newRadius);           // atomic volatile publish of the NEW geometry (render thread)
            WindowState next = state;                                          // the snapshot recenter() just published

            // Enumerate the (previous -> next) shell ONCE, as positions rather than slots: shared by both
            // the synchronous occupancy-clear below and the prioritized harvest split further down, so a
            // large shell is walked exactly one time per call regardless of its size.
            enumerateResyncShell(previous.centerX(), previous.centerY(), previous.centerZ(), previous.radius(),
                    newCenterX, newCenterY, newCenterZ, newRadius,
                    (x, y, z) -> shell.add(SectionPos.of(x, y, z)));

            // Synchronously (render thread, before this method returns: see the "Slot staleness" doc
            // above) zero GPU occupancy for every slot this move newly exposes, so the DDA never reads a
            // previous owner's stale geometry there before the harvest below (sync or async) catches up.
            TargetRegistry r = registry;
            if (r != null && !shell.isEmpty()) {
                Set<Integer> exposed = new HashSet<>();
                for (SectionPos pos : shell) {
                    int slot = slotFor(next, pos.x(), pos.y(), pos.z());
                    if (slot >= 0) {
                        exposed.add(slot);
                    }
                }
                if (!exposed.isEmpty()) {
                    sectionStates.invalidate(exposed);
                    sourceInventory.invalidate(exposed);
                    if (emitterPool != null) emitterPool.invalidate(exposed);
                    if (sourceWindow != null) sourceWindow.invalidate(exposed);
                    BrickGridUpload.clearOccupancySlots(r, exposed);
                    publishEmitterPoolLocked();
                    // Telemetry: these slots hold no real geometry (shell-clear) until the harvest
                    // below (sync or async) republishes them: see populatedSlots'/clearedTotal's own doc.
                    populatedSlots.removeAll(exposed);
                    // A slot back on its old owner cannot reuse a done mark from before this clear.
                    for (int slot : exposed) slotCommittedReadRevision.remove(slot);
                    clearedTotal.addAndGet(exposed.size());
                }
            }
        }

        // Prioritized budgeted harvest -- see this method's own "Prioritized budgeted harvest" and
        // "Batched harvest upload" docs, and SYNC_BUDGET's/BatchSizeController's docs, for the full
        // rationale/arithmetic.
        shell.sort(priorityComparator(newCenterX, newCenterY, newCenterZ, forwardX, forwardY, forwardZ));
        int syncCount = Math.min(SYNC_BUDGET, shell.size());
        if (syncCount > 0) {
            harvestAndUploadBatch(level, shell.subList(0, syncCount), syncHarvestedTotal::addAndGet, null, generation, harvestGeneration, reader, System.nanoTime());
        }
        if (syncCount < shell.size()) {
            // Copy the deferred tail into its own list: `shell` is local to this call and safe to let
            // go out of scope, but capturing a stable, independent list (rather than a live subList
            // view) keeps the executor task's data ownership unambiguous, matching one-task-per-CALL
            // (not per position) -- queue depth stays bounded by call count, never by shell size.
            List<SectionPos> deferred = new ArrayList<>(shell.subList(syncCount, shell.size()));
            pendingSlots.addAndGet(deferred.size());
            long queuedAt = System.nanoTime();
            executor.execute(() ->
                    harvestAndUploadBatch(level, deferred, asyncHarvestedTotal::addAndGet, pendingSlots::decrementAndGet, generation, harvestGeneration, reader, queuedAt));
        }
    }

    /** Sets the next batch size from the measured reset, packing, submit and commit work. Waiting
     * for the queue lock or the GPU fence holds other drawing work, so counting it as per-slot cost
     * would drive the batch down to its floor on a busy GPU and pay the same waits far more often.
     * Each harvest call has its own controller. */
    static final class BatchSizeController {
        /** Four milliseconds of CPU upload work, a slice of a 60fps frame. It sizes the next
         * batch; it does not bound how long the GPU fence or the thread scheduler takes. */
        static final long BATCH_TIME_BUDGET_NANOS = 4_000_000L;
        /** First batch size, before any measurement exists, and the floor a slow batch cannot fall
         * below. */
        static final int INITIAL_TARGET = 64;
        static final int MIN_TARGET = 8;
        /** Caps one command buffer's embedded bytes: a Standard-detail slot embeds up to about 23KB
         * (512 + 4096 + 16384 + 6144). */
        static final int MAX_TARGET = 4096;

        private int targetSize = INITIAL_TARGET;

        /** The batch size {@link #harvestAndUploadBatch} should accumulate toward before flushing --
         * either {@link #INITIAL_TARGET} (no measurement yet) or the size {@link #recordBatch} last
         * projected from real timing. */
        int currentTargetSize() {
            return targetSize;
        }

        /** Folds one flushed batch's real measurement into the next target. Pure arithmetic --
         * {@code actualSize} and {@code elapsedNanos} are both caller-supplied numbers, no static or
         * GPU state touched here -- so it is unit-tested directly (see {@code VoxelWindowTest}) without
         * a live batch or GPU. Non-positive inputs (a flush that measured zero elapsed time, or an
         * empty/degenerate batch) leave the target unchanged rather than dividing by zero or projecting
         * a nonsensical size. */
        void recordBatch(int actualSize, long elapsedNanos) {
            if (actualSize <= 0 || elapsedNanos <= 0) {
                return;
            }
            double nanosPerSlot = (double) elapsedNanos / actualSize;
            long projected = (long) (BATCH_TIME_BUDGET_NANOS / nanosPerSlot);
            targetSize = (int) Math.clamp(projected, MIN_TARGET, MAX_TARGET);
        }
    }

    /** CPU-side bookkeeping for one harvested slot -- the non-GPU portion of what {@link
     * #onSectionHarvested} does for a single slot (map/telemetry updates), pulled out so {@link
     * #harvestAndUploadBatch} can run it once per slot while leaving the actual GPU write to a shared
     * batch flush instead of {@link #onSectionHarvested}'s original per-slot GPU call. Returns whether
     * the slot's light volume needs zeroing: its last successfully uploaded owner differs, so
     * that owner's propagated light must not ghost through (see {@link
     * BrickGridUpload#clearLightSlot}'s own doc) -- leaving the actual GPU write to the caller's batch
     * entry ({@link BrickGridUpload.SlotUpload#clearLight()}). */
    private static boolean recordHarvest(int slot, SectionPos position, SectionHarvester.Result result) {
        lightUpdates.meshPublished(position);
        slotOwner.put(slot, position);
        slotData.put(slot, result);
        slotReadRevision.put(slot, meshRevisionAt(position));
        if (sourceWindow != null) sourceWindow.pending(slot);
        populatedSlots.add(slot); // harvest publish -- see the field's own doc
        return needsLightClear(slot, position);
    }

    /** Harvests and GPU-uploads {@code positions} in adaptively time-budgeted batches instead of one
     * fenced GPU round trip per slot -- the shared core of the render-thread synchronous slice and
     * {@link #RESYNC_EXECUTOR}'s tail-drain (see {@link #recenterAndResync}'s "Batched harvest upload"
     * doc). For each position: skips it if already valid (mirrors the old {@code resyncPosition}'s
     * semantics exactly -- an in-window, correctly-owned slot needs no work), else attempts a {@link
     * DirectSectionReader#read} bootstrap; a real harvest runs {@link #recordHarvest} (bookkeeping,
     * only while its captured storage generation is current) and queues a slot upload. New storage
     * stops that task publishing or reading, though its processed count still drains.
     *
     * <p>{@code onHarvestedBatch} is invoked once per flush (sync or "clear-only" no-GPU flush alike)
     * with the count of REAL harvests that flush contained, and once more after the loop for any
     * final partial count -- never once for the whole call -- so a caller sampling the monotonic
     * totals once per frame (see {@code VoxelDebugRaymarchPass.publishVoxelTelemetry}) observes the
     * count climbing progressively as the async tail-drain works through a large deferred list, not a
     * single delayed jump when the whole task finally finishes. {@code onProcessed}, if non-null, is
     * invoked once per position INDEPENDENT of whether it was actually harvested -- used by the async
     * tail-drain to decrement {@link #pendingSlots} per position exactly as the old {@code
     * resyncPosition} call site did. */
    private static void harvestAndUploadBatch(Level level, List<SectionPos> positions,
                                               LongConsumer onHarvestedBatch, @Nullable Runnable onProcessed, long generation,
                                               long harvestGeneration,
                                               BiFunction<Level, SectionPos, SectionHarvester.Result> reader, long queuedAt) {
        try (var profile = VoxelRefillTelemetry.LIVE.begin(queuedAt, System::nanoTime)) {
            TargetRegistry r = registry;
            List<BrickGridUpload.SlotUpload> batch = new ArrayList<>();
            List<UnpublishedHarvest> unpublished = new ArrayList<>();
            BatchSizeController controller = new BatchSizeController();
            long batchHarvestCount = 0; // real harvests accumulated in the CURRENT unflushed batch
            int processedCount = 0;

            try {
                for (SectionPos pos : positions) {
                    VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.VISITED);
                    // A move can leave queued positions outside the window without changing storage.
                    // Reject them before world reads; the locked check below still covers movement during a read.
                    boolean current = generation == storageGeneration && slotFor(pos.x(), pos.y(), pos.z()) >= 0;
                    boolean valid = current && hasValidData(pos.x(), pos.y(), pos.z());
                    if (current && !valid) {
                        long readRevision = meshRevisionAt(pos);
                        SectionHarvester.Result result = null;
                        // Acquire before touching the captured world. Old queued jobs stay cancelled even
                        // after a later model publication reopens harvesting for a newer generation.
                        try (var lease = VoxelHarvestLifecycle.tryAcquire(harvestGeneration)) {
                            if (lease != null && generation == storageGeneration) {
                                VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.READ);
                                long readStart = VoxelRefillTelemetry.start();
                                try { result = reader.apply(level, pos); }
                                finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.READ, readStart); }
                                VoxelRefillTelemetry.count(result == null ? VoxelRefillTelemetry.Count.NULL_READ
                                        : VoxelRefillTelemetry.Count.READ_OK);
                            } else VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.RETIRED);
                        }
                        // Every CPU lease is closed before the GPU queue lock or upload below.
                        if (result != null) {
                            long lockStart = VoxelRefillTelemetry.start();
                            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                                VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.LOCK, lockStart);
                                int slot = slotFor(pos.x(), pos.y(), pos.z());
                                if (generation == storageGeneration && r == registry && slot >= 0
                                        && meshRevisionAt(pos) == readRevision
                                        && !hasValidData(pos.x(), pos.y(), pos.z()) && hasCurrentSourceSummary(result)) {
                                    unpublished.add(new UnpublishedHarvest(slot, pos, result,
                                            slotOwner.get(slot), slotData.get(slot), slotReadRevision.get(slot)));
                                    boolean clearLight = recordHarvest(slot, pos, result);
                                    var snapshot = r != null && sectionMetadataEnabled(r)
                                            ? sectionStates.geometry(slot, pos) : null;
                                    if (snapshot != null) clearLight |= sectionStates.needsLightClear(slot, snapshot);
                                    batch.add(new BrickGridUpload.SlotUpload(slot, result, clearLight, snapshot));
                                    batchHarvestCount++;
                                } else VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.REJECTED);
                            }
                        }
                    } else VoxelRefillTelemetry.count(valid ? VoxelRefillTelemetry.Count.ALREADY_VALID
                            : VoxelRefillTelemetry.Count.OBSOLETE);
                    if (onProcessed != null) {
                        processedCount++;
                        onProcessed.run();
                    }
                    if (batch.size() >= controller.currentTargetSize()) {
                        if (r != null) {
                            flushBatch(r, batch, controller, generation);
                            unpublished.clear();
                        } else {
                            batch.clear();
                        }
                        if (batchHarvestCount > 0) {
                            onHarvestedBatch.accept(batchHarvestCount);
                            batchHarvestCount = 0;
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    if (r != null) {
                        flushBatch(r, batch, controller, generation);
                        unpublished.clear();
                    } else {
                        batch.clear();
                    }
                }
                if (batchHarvestCount > 0) {
                    onHarvestedBatch.accept(batchHarvestCount);
                }
            } finally {
                rollbackUnpublished(r, generation, unpublished);
                // Failed reads/transfers must drain the unread tail for every async caller.
                if (onProcessed != null) {
                    for (int remaining = processedCount; remaining < positions.size(); remaining++) onProcessed.run();
                }
            }
            profile.complete();
        }
    }

    /** A read or transfer can fail after CPU bookkeeping but before the batch publishes. Restore
     * only records still owned by that batch. Completed light ownership is unchanged by rollback.
     * No GPU writes are attempted while unwinding a failed transfer. */
    private static void rollbackUnpublished(@Nullable TargetRegistry expectedRegistry, long generation,
                                            List<UnpublishedHarvest> unpublished) {
        if (unpublished.isEmpty()) return;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (generation != storageGeneration || expectedRegistry != registry) return;
            for (UnpublishedHarvest item : unpublished) {
                int slot = item.slot();
                if (slotData.get(slot) != item.result() || !item.owner().equals(slotOwner.get(slot))) continue;
                if (item.previousOwner() == null) slotOwner.remove(slot);
                else slotOwner.put(slot, item.previousOwner());
                if (item.previousData() == null) slotData.remove(slot);
                else slotData.put(slot, item.previousData());
                if (item.previousReadRevision() == null) slotReadRevision.remove(slot);
                else slotReadRevision.put(slot, item.previousReadRevision());
                populatedSlots.remove(slot);
                sectionStates.invalidate(List.of(slot));
                if (sourceWindow != null) sourceWindow.invalidate(List.of(slot));
            }
        }
    }

    /** A queued token cannot survive a newer harvest, a shell clear, a recenter out of range, or a
     * storage replacement. The uploader checks this under the same lock before writing any bytes. */
    static boolean isCurrentSectionState(int slot, VoxelSectionState.Snapshot snapshot) {
        return sectionStates.isCurrent(slot, snapshot)
                && slotFor(snapshot.x(), snapshot.y(), snapshot.z()) == slot;
    }

    /** Checks ownership again, uploads a batch, and feeds the CPU upload work to the controller.
     * The fence still finishes before this returns, so leaving the wait out of the measure changes
     * the batch size only, not ordering or when the GPU data is safe to use. */
    private static void flushBatch(TargetRegistry registry, List<BrickGridUpload.SlotUpload> batch,
                                    BatchSizeController controller, long generation) {
        // harvestAndUploadBatch always holds a refill telemetry scope. Size from the work this
        // code does, not time spent waiting on drawing or another uploader; zero leaves it alone.
        long workBefore = VoxelRefillTelemetry.uploadWorkNanos();
        long lockStart = VoxelRefillTelemetry.start();
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.LOCK, lockStart);
            if (generation == storageGeneration && registry == VoxelWindow.registry) {
                // Reads can overlap a recenter or newer mesh publication without replacing storage.
                // Enforce CPU ownership here even when optional GPU metadata is disabled.
                batch.removeIf(item -> !isCurrentUpload(item));
                long uploadStart = VoxelRefillTelemetry.start();
                try { BrickGridUpload.uploadSlots(registry, batch); }
                finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.UPLOAD, uploadStart); }
            }
        }
        controller.recordBatch(batch.size(), VoxelRefillTelemetry.uploadWorkNanos() - workBefore);
        batch.clear();
    }

    /** Nearest sections first in every direction: a light or blocker off screen still changes what
     * is on screen. Camera facing only breaks ties between sections the same distance away, so a
     * next-door section never waits for a ring of far sections in front. */
    static Comparator<SectionPos> priorityComparator(int centerX, int centerY, int centerZ,
                                                       float forwardX, float forwardY, float forwardZ) {
        return Comparator
                .comparingLong((SectionPos p) -> squaredDistance(p, centerX, centerY, centerZ))
                .thenComparingInt(p -> isFront(p, centerX, centerY, centerZ, forwardX, forwardY, forwardZ) ? 0 : 1);
    }

    /** Whether {@code p} lies in the camera's forward-facing hemisphere from {@code (centerX,
     * centerY, centerZ)} -- a deliberately coarse proxy for "inside the view frustum" (a true frustum
     * needs FOV + aspect + near/far, none of which this class otherwise touches) that is a SUPERSET of
     * the real frustum (never excludes a slot the real frustum would include), so it cannot rank a
     * genuinely on-screen slot behind an off-screen one. A zero-length forward vector (dot product 0
     * for every candidate) makes every slot "front", degrading gracefully to nearest-first-only
     * ordering rather than crashing or biasing incorrectly. Section-space deltas are used directly
     * (not converted to blocks): both axes share the same 16-block section size, so the direction of
     * the delta vector is identical in section- and block-space, differing only by a uniform scale
     * factor that never flips a dot product's sign. */
    static boolean isFront(SectionPos p, int centerX, int centerY, int centerZ,
                           float forwardX, float forwardY, float forwardZ) {
        double dx = p.x() - centerX, dy = p.y() - centerY, dz = p.z() - centerZ;
        double dot = dx * forwardX + dy * forwardY + dz * forwardZ;
        return dot >= 0.0;
    }

    /** Squared Euclidean distance, in sections, from {@code (centerX, centerY, centerZ)} to {@code p}
     * -- squared (not the real distance) since only relative ordering matters here and {@code sqrt} is
     * pure overhead for a comparator. Pure, unit-tested directly alongside {@link #isFront}. */
    static long squaredDistance(SectionPos p, int centerX, int centerY, int centerZ) {
        long dx = p.x() - centerX, dy = p.y() - centerY, dz = p.z() - centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The toroidal slot indices -- mapped through {@code next}'s own geometry -- that {@link
     * #enumerateResyncShell} visits for the {@code (previous -> next)} transition: exactly the set
     * {@link #recenterAndResync} occupancy-clears synchronously. Pure (reads no static/mutable state,
     * unlike {@link #slotFor(int, int, int)}), so it is unit-tested directly (VoxelWindowTest) without
     * a live {@link TargetRegistry}/GPU -- the same production entry point {@code recenterAndResync}
     * itself calls, not a parallel reimplementation of it. */
    static Set<Integer> exposedSlots(WindowState previous, WindowState next) {
        Set<Integer> slots = new HashSet<>();
        enumerateResyncShell(previous.centerX(), previous.centerY(), previous.centerZ(), previous.radius(),
                next.centerX(), next.centerY(), next.centerZ(), next.radius(),
                (x, y, z) -> {
                    int slot = slotFor(next, x, y, z);
                    if (slot >= 0) {
                        slots.add(slot);
                    }
                });
        return slots;
    }


    /** Visitor over absolute section coordinates. Package-private so the shell-delta enumeration can
     * be unit-tested against a collecting visitor without a live {@link Level}. */
    @FunctionalInterface
    interface SectionVisitor {
        void visit(int sectionX, int sectionY, int sectionZ);
    }

    /**
     * Invokes {@code visitor} exactly once for each absolute section position whose slot may need
     * (re)harvesting after the window center moves from {@code (oldCenter*)} to {@code (newCenter*)} --
     * no position missed, none visited twice.
     *
     * <p><b>Incremental move</b> (same radius, and the move is strictly less than one full diameter on
     * every axis): only the newly-exposed shell is visited -- the set-difference {@code (new cube \
     * old cube)}. Because the two equal-size cubes are simply offset, that difference decomposes into
     * up to three pairwise-disjoint axis-aligned slabs, "peeling" one axis at a time: (1) every new X
     * layer outside the old X range, spanning the full new Y,Z; (2) within the X-overlap, every new Y
     * layer outside the old Y range, spanning the full new Z; (3) within the X- and Y-overlap, every
     * new Z layer outside the old Z range. A position is in the difference iff at least one coordinate
     * is outside the old cube, and each such position is caught by exactly the slab for its
     * first-differing axis -- so coverage is complete and the slabs never overlap. Sections that stay
     * in-window are skipped: their toroidal slot index is unchanged (floorMod is center-independent),
     * so their existing data is still valid.
     *
     * <p><b>Full-scan fallback</b>: if the radius changed (the toroidal slot indices differ, so old
     * data is meaningless) or the move is {@code >=} a full diameter on any axis (the old and new
     * cubes share no layer on that axis, so nothing carries over), the entire new cube is legitimately
     * stale and every position in it is visited. It is visited <b>nearest-camera-first</b> -- the
     * center section, then each Chebyshev-distance shell outward to {@code radius} -- so the area right
     * around the player populates and starts rendering before farther sections do, instead of an
     * arbitrary bottom-up Y-sweep. The visited set is unchanged (still the whole cube, once); only the
     * order differs. The never-centered sentinel (radius 0) always lands here on first enable,
     * populating the whole initial window.
     */
    static void enumerateResyncShell(int oldCenterX, int oldCenterY, int oldCenterZ, int oldRadius,
                                     int newCenterX, int newCenterY, int newCenterZ, int newRadius,
                                     SectionVisitor visitor) {
        int r = newRadius;
        int diameter = 2 * r + 1;

        int moveX = Math.abs(newCenterX - oldCenterX);
        int moveY = Math.abs(newCenterY - oldCenterY);
        int moveZ = Math.abs(newCenterZ - oldCenterZ);
        if (oldRadius != newRadius || moveX >= diameter || moveY >= diameter || moveZ >= diameter) {
            // Full-scan fallback, visited NEAREST-CAMERA-FIRST rather than as a bottom-up Y-sweep: the
            // camera's own section first, then each Chebyshev-distance shell outward. The old geometry is
            // irrelevant here (the whole new cube is stale), so each shell is expressed as concentric
            // cubes about the NEW center -- radius (k-1) growing to radius k, zero center movement -- and
            // handed to the SAME slab decomposition the incremental path uses. That decomposition returns
            // exactly the Chebyshev-distance-k shell, so shells 0..r partition the full cube with no gaps
            // and no duplicates -- identical total visited set to the old triple loop, only reordered.
            visitor.visit(newCenterX, newCenterY, newCenterZ); // shell 0: the center itself. oldRadius 0
            // (a legitimate single-point cube) is the smallest we ever pass to the helper; oldRadius -1
            // is never passed -- the r=0 shell is this explicit standalone visit, not a helper call.
            for (int shell = 1; shell <= r; shell++) {
                enumerateBoxDelta(newCenterX, newCenterY, newCenterZ, shell - 1,
                        newCenterX, newCenterY, newCenterZ, shell, visitor);
            }
            return;
        }

        // Same radius and every axis moved < one diameter, so the old cube is the new cube shifted by
        // (moveX,moveY,moveZ) and is guaranteed to overlap it on all three axes: visit only the newly-
        // exposed shell (new cube \ old cube), both cubes at radius r.
        enumerateBoxDelta(oldCenterX, oldCenterY, oldCenterZ, r,
                newCenterX, newCenterY, newCenterZ, r, visitor);
    }

    /**
     * Visits every position in {@code (new box \ old box)} exactly once, where each box is the
     * axis-aligned cube of its own center and radius. Shared by both callers in {@link
     * #enumerateResyncShell}: the incremental-move path (equal radii, offset centers, guaranteed
     * overlap) and the nearest-first full-scan path (equal centers, radii {@code k-1} and {@code k},
     * concentric). The difference decomposes into up to three pairwise-disjoint axis-aligned slabs,
     * peeling one axis at a time by first-differing axis: (1) every new X layer outside the old X
     * range, spanning full new Y,Z; (2) within the X-overlap, every new Y layer outside the old Y
     * range, spanning full new Z; (3) within the X- and Y-overlap, every new Z layer outside the old Z
     * range. A position is in the difference iff at least one coordinate is outside the old box, and
     * each such position is caught by exactly the slab for its first-differing axis -- so coverage is
     * complete and the slabs never overlap. This holds for any two boxes (overlapping or not); when
     * they do not overlap on an axis, that axis's overlap range is empty and the later slabs simply do
     * no work, leaving slab 1 to cover the whole new box.
     */
    private static void enumerateBoxDelta(int oldCenterX, int oldCenterY, int oldCenterZ, int oldRadius,
                                          int newCenterX, int newCenterY, int newCenterZ, int newRadius,
                                          SectionVisitor visitor) {
        int nx0 = newCenterX - newRadius, nx1 = newCenterX + newRadius;
        int ny0 = newCenterY - newRadius, ny1 = newCenterY + newRadius;
        int nz0 = newCenterZ - newRadius, nz1 = newCenterZ + newRadius;
        int ox0 = oldCenterX - oldRadius, ox1 = oldCenterX + oldRadius;
        int oy0 = oldCenterY - oldRadius, oy1 = oldCenterY + oldRadius;
        int oz0 = oldCenterZ - oldRadius, oz1 = oldCenterZ + oldRadius;
        int ovX0 = Math.max(nx0, ox0), ovX1 = Math.min(nx1, ox1);
        int ovY0 = Math.max(ny0, oy0), ovY1 = Math.min(ny1, oy1);

        // Slab 1 -- X shell: every new X layer NOT in the old box's X range; full new Y,Z.
        for (int x = nx0; x <= nx1; x++) {
            if (x >= ox0 && x <= ox1) {
                continue; // this X layer overlaps the old box -> its non-stale interior is handled by slabs 2/3
            }
            for (int y = ny0; y <= ny1; y++) {
                for (int z = nz0; z <= nz1; z++) {
                    visitor.visit(x, y, z);
                }
            }
        }
        // Slab 2 -- Y shell within the X-overlap: X in overlap, Y NOT in old Y range; full new Z.
        for (int x = ovX0; x <= ovX1; x++) {
            for (int y = ny0; y <= ny1; y++) {
                if (y >= oy0 && y <= oy1) {
                    continue; // this Y layer overlaps the old box -> handled by slab 3
                }
                for (int z = nz0; z <= nz1; z++) {
                    visitor.visit(x, y, z);
                }
            }
        }
        // Slab 3 -- Z shell within the X- and Y-overlap: X,Y in overlap, Z NOT in old Z range.
        for (int x = ovX0; x <= ovX1; x++) {
            for (int y = ovY0; y <= ovY1; y++) {
                for (int z = nz0; z <= nz1; z++) {
                    if (z >= oz0 && z <= oz1) {
                        continue; // fully inside the old box -> still valid, do not revisit
                    }
                    visitor.visit(x, y, z);
                }
            }
        }
    }
}
