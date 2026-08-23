package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.TargetRegistry;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>{@code slotOwner}/{@code slotData} are {@link ConcurrentHashMap} rather than plain {@code
 * HashMap} because {@link #onSectionHarvested} is invoked from background threads: Sodium's
 * multi-threaded chunk-build worker pool (confirmed via {@code javap} on {@code ChunkBuilder.class}
 * -- multiple concurrent background threads, not one), AND this class's own single-threaded {@link
 * #RESYNC_EXECUTOR} which runs the DEFERRED tail of the shell harvest+upload that {@link
 * #recenterAndResync} dispatches off the render thread. {@link #recenter} itself, and the
 * render-thread portion of {@link #recenterAndResync} (the geometry publish, the occupancy clear, the
 * bounded {@link #SYNC_BUDGET} synchronous harvest, and the {@code execute} submission for the
 * remainder), still run on the main/render thread -- the synchronous harvest and {@link
 * #RESYNC_EXECUTOR}'s deferred tail both run through {@link #harvestAndUploadBatch}, which calls the
 * same {@link #recordHarvest} bookkeeping {@link #onSectionHarvested} itself uses (batching only the
 * GPU write via {@link BrickGridUpload#uploadSlots}, see that method's own doc), serialized against
 * Sodium's workers the same way (see {@code VulkanComputeBackend.SHARED_QUEUE_LOCK}, already taken
 * from the render thread elsewhere in this flow by {@link BrickGridUpload#clearOccupancySlots}), so
 * no new synchronization was introduced. A plain {@code HashMap} under concurrent writes can corrupt
 * its internal bucket structure (lost entries, infinite loops on resize); {@code ConcurrentHashMap}
 * makes every individual {@code get}/{@code put} thread-safe. No compound check-then-act spans both
 * maps here -- {@code recordHarvest} writes both maps independently for the same slot key, and readers
 * (`slotFor`/`hasValidData`) only need eventually-consistent state, not atomicity between the two
 * maps -- so per-map thread safety is sufficient without a stronger lock.
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

    /** How many of a resync shell's highest-priority positions {@link #recenterAndResync} harvests
     * SYNCHRONOUSLY, on the render thread, before handing the remainder to {@link #RESYNC_EXECUTOR}
     * (see the harvest-throughput fix's own report for the full derivation). Rationale, in short:
     * a fenced {@code vkQueueSubmit}/{@code vkWaitForFences} round trip -- whether {@link
     * BrickGridUpload#uploadSlot}'s original one-slot-per-submit form, or {@link
     * BrickGridUpload#uploadSlots}'s batched form this class now uses for both the synchronous slice
     * and the async tail (see the batch-upload-throughput fix's own report) -- has a FIXED per-call
     * overhead (vkCreateFence/vkQueueSubmit/vkWaitForFences/vkDestroyFence plus backend
     * create/close) of roughly ~0.2ms on an already-warm queue (consistent with this class's
     * steady-state fence-wait characterization elsewhere, e.g. {@code VoxelDebugRaymarchPass}'s ring
     * recycle wait). Batching folds that fixed cost into ONE submit per {@link #harvestAndUploadBatch}
     * flush rather than paying it once per slot, so {@code SYNC_BUDGET} itself no longer bounds
     * render-thread fence-wait time directly (a 24-slot sync slice now costs roughly one flush's
     * ~0.2ms, not {@code 24 * 0.2ms}) -- it still exists to bound how MUCH of a section-cross's shell
     * is worth harvesting synchronously at all (packing + map bookkeeping for hundreds of slots is not
     * free even without a GPU round trip per slot), sized the same as before the batching fix. On the
     * priority side: a sprint-flying player (~20 blocks/sec, a conservative middle between vanilla
     * sprint-walk and rocket-boosted elytra) crosses a 16-block section boundary roughly every 0.8s
     * (~48 frames at 60fps) on its dominant axis, so the ~24-slot synchronous pass only needs to cover
     * the immediate, camera-forward-facing wedge of ONE newly-exposed shell face per cross -- typically
     * far narrower than the whole face -- while the executor drains whatever the sync budget didn't
     * reach during the (long, by comparison) interval before the next cross adds more work. See {@link
     * #priorityComparator} for the ordering that decides which slots fall inside vs. outside the
     * budget. */
    private static final int SYNC_BUDGET = 24;

    /** Which absolute section each toroidal slot currently holds valid data for, keyed by the slot's
     * flat index. A slot present in this map (with a matching key) is valid; a slot whose key doesn't
     * match its currently-expected section (post-recenter) is stale and needs resync. */
    private static final Map<Integer, SectionPos> slotOwner = new ConcurrentHashMap<>();
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
        registry = newRegistry;
    }

    /** The registry currently attached (the one {@link #onSectionHarvested} uploads into), or {@code
     * null} if none. A single volatile read; lets the debug raymarch pass reach the same instance
     * {@link #attachRegistry} was handed without threading it through a second field. */
    @Nullable
    public static TargetRegistry attachedRegistry() {
        return registry;
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
        return expected.equals(slotOwner.get(slot));
    }

    public static void onSectionHarvested(SectionPos position, SectionHarvester.Result result) {
        int slot = slotFor(position.x(), position.y(), position.z());
        if (slot < 0) {
            return; // harvested a section outside the current window -- discard, it'll be re-harvested if it enters the window later
        }
        // Bookkeeping (map/telemetry updates) is shared with the batched harvest paths -- see
        // recordHarvest's own doc. Sodium's worker threads call this method directly (naturally paced
        // by chunk (re)builds, not a burst), so it keeps its original one-slot-per-GPU-call shape
        // rather than routing through the batch accumulator harvestAndUploadBatch uses.
        boolean clearLight = recordHarvest(slot, position, result);

        TargetRegistry r = registry;
        if (r != null) {
            // A slot claimed by a NEW section (recenter shell, or a section first entering the
            // window) inherits the previous owner's propagated light -- zero it so light never
            // ghosts across the window boundary. Same-section re-harvests (block edits) keep their
            // light for fast re-convergence; the propagation automaton's per-iteration decay
            // handles the edited geometry. See BrickGridUpload.clearLightSlot.
            if (clearLight) {
                BrickGridUpload.clearLightSlot(r, slot);
            }
            BrickGridUpload.uploadSlot(r, slot, result);
        }
    }

    /** Recenters the window and dispatches harvest of the slots that the move newly exposed -- the
     * render-thread entry point called on every section-boundary cross. The geometry update ({@link
     * #recenter}) runs synchronously on the render thread (a single cheap volatile publish), but the
     * shell enumeration + per-section {@link DirectSectionReader#read} + GPU {@link
     * BrickGridUpload#uploadSlot} are handed to {@link #RESYNC_EXECUTOR} and run on its background
     * thread, so this method returns to the caller immediately regardless of how large the shell is --
     * a frame is never blocked on harvest/upload cost. (Previously this whole body ran inline on the
     * render thread, producing a user-visible pause proportional to shell size on large moves.)
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
     * harvest exactly once per transition -- nothing is silently skipped. The only behavioral change
     * from the old synchronous path is latency: a newly-exposed section may show stale/missing data
     * for a few extra frames until its queued task runs, instead of being guaranteed-fresh the same
     * frame. A stale task cannot corrupt a valid slot: {@link #harvestAndUploadBatch}/{@link
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
     * <p><b>Prioritized budgeted harvest.</b> A walking/flying player crosses sections continuously,
     * and the shell a single incremental move exposes can be a whole {@code diameter x diameter} face
     * (hundreds of sections for a large window radius) -- deferring that whole face to {@link
     * #RESYNC_EXECUTOR}'s single background thread falls behind under continuous crossing (each
     * per-slot GPU upload is its own fenced round trip; see {@link #SYNC_BUDGET}'s doc for the
     * arithmetic). So the shell is enumerated once into a list and sorted by {@link
     * #priorityComparator} (camera-forward-facing slots first, then nearest to the new window center);
     * the top {@link #SYNC_BUDGET} slots are harvested synchronously, right here on the render thread,
     * before this method returns -- so the area a full-window consumer like a pack's {@code
     * celestial_shadow} fullscreen pass is most likely to sample this very frame is never left
     * transiently empty. The remainder is handed to {@link #RESYNC_EXECUTOR} as the tail-drain for
     * whatever doesn't fit the synchronous budget, preserving every eventual-consistency guarantee
     * documented above (still single-threaded, still FIFO per call, still safe against a stale task
     * corrupting a valid slot). The occupancy-clear above still covers the whole exposed shell
     * regardless of budget, so the "cleared-until-harvested" invariant holds for slots outside the
     * sync budget too -- they read as empty (sky fallback) until the executor's tail-drain reaches
     * them.
     *
     * <p><b>Batched harvest upload.</b> Both the synchronous slice and the async tail-drain
     * harvest+upload through {@link #harvestAndUploadBatch} rather than one fenced GPU round trip per
     * position: harvested slots accumulate into a batch that flushes as one {@link
     * BrickGridUpload#uploadSlots} submission once it reaches an adaptively-sized target -- see {@link
     * BatchSizeController} -- rather than a hardcoded count or one submit per slot. This lets the worst
     * case (the very first {@code recenterAndResync}, a full-window scan of thousands of sections, all
     * deferred to {@link #RESYNC_EXECUTOR} past the {@link #SYNC_BUDGET} slice) drain in large,
     * GPU-time-budgeted batches instead of thousands of individually fenced round trips, while the
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
     * matters, see {@link #isFront}), used purely to order the shell; passing a zero vector degrades
     * gracefully to nearest-first-only ordering ({@link #isFront} treats a zero dot product as front). */
    public static void recenterAndResync(int newCenterX, int newCenterY, int newCenterZ, int newRadius, Level level,
                                          float forwardX, float forwardY, float forwardZ) {
        WindowState previous = state;                                       // OLD geometry, captured BEFORE the overwrite (render thread)
        recenter(newCenterX, newCenterY, newCenterZ, newRadius);           // atomic volatile publish of the NEW geometry (render thread)
        WindowState next = state;                                          // the snapshot recenter() just published

        // Enumerate the (previous -> next) shell ONCE, as positions rather than slots: shared by both
        // the synchronous occupancy-clear below and the prioritized harvest split further down, so a
        // large shell is walked exactly one time per call regardless of its size.
        List<SectionPos> shell = new ArrayList<>();
        enumerateResyncShell(previous.centerX(), previous.centerY(), previous.centerZ(), previous.radius(),
                newCenterX, newCenterY, newCenterZ, newRadius,
                (x, y, z) -> shell.add(SectionPos.of(x, y, z)));

        // Synchronously (render thread, before this method returns -- see the "Slot staleness" doc
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
                BrickGridUpload.clearOccupancySlots(r, exposed);
                // Telemetry: these slots no longer hold real geometry (shell-clear) until the harvest
                // below (sync or async) republishes them -- see populatedSlots'/clearedTotal's own doc.
                populatedSlots.removeAll(exposed);
                clearedTotal.addAndGet(exposed.size());
            }
        }

        // Prioritized budgeted harvest -- see this method's own "Prioritized budgeted harvest" and
        // "Batched harvest upload" docs, and SYNC_BUDGET's/BatchSizeController's docs, for the full
        // rationale/arithmetic.
        shell.sort(priorityComparator(newCenterX, newCenterY, newCenterZ, forwardX, forwardY, forwardZ));
        int syncCount = Math.min(SYNC_BUDGET, shell.size());
        if (syncCount > 0) {
            harvestAndUploadBatch(level, shell.subList(0, syncCount), syncHarvestedTotal::addAndGet, null);
        }
        if (syncCount < shell.size()) {
            // Copy the deferred tail into its own list: `shell` is local to this call and safe to let
            // go out of scope, but capturing a stable, independent list (rather than a live subList
            // view) keeps the executor task's data ownership unambiguous, matching one-task-per-CALL
            // (not per position) -- queue depth stays bounded by call count, never by shell size.
            List<SectionPos> deferred = new ArrayList<>(shell.subList(syncCount, shell.size()));
            pendingSlots.addAndGet(deferred.size());
            RESYNC_EXECUTOR.execute(() ->
                    harvestAndUploadBatch(level, deferred, asyncHarvestedTotal::addAndGet, pendingSlots::decrementAndGet));
        }
    }

    /** Adaptive batch-size controller for {@link #harvestAndUploadBatch}: starts conservative, then
     * grows or shrinks the NEXT batch's target slot count from the MEASURED wall-clock cost of the
     * batch just flushed, aiming to keep every {@link BrickGridUpload#uploadSlots} flush's fenced GPU
     * round trip under {@link #BATCH_TIME_BUDGET_NANOS} -- "budget-bounded by measured milliseconds,
     * not slot count" (the batch-upload-throughput fix's own brief). A single fenced round trip's
     * FIXED overhead (fence create/submit/wait/destroy, plus {@code VulkanComputeBackend.tryCreate}/
     * {@code close}) dominates at small batch sizes (~0.2ms, see {@link #SYNC_BUDGET}'s own
     * derivation); the MARGINAL per-slot cost -- recording a few {@code vkCmdUpdateBuffer} calls and
     * packing a few KB -- is far smaller, so growing the batch amortizes the fixed cost across more
     * slots almost for free, up to whatever size actually keeps the round trip under budget. This
     * naturally fast-ramps: the huge deferred tail of a first-enable/large-jump full-window scan
     * quickly discovers it can flush thousands of slots per submit, while a steady-state trickle (a
     * handful of slots per section-cross) never accumulates enough to leave {@link #INITIAL_TARGET}
     * before the deferred list itself runs out and the final partial batch flushes anyway.
     *
     * <p>Per-call instance state (a {@code new} one per {@link #harvestAndUploadBatch}
     * invocation), not a shared static field: concurrent callers on different threads (the
     * render-thread synchronous slice and {@link #RESYNC_EXECUTOR}'s own thread) must not perturb each
     * other's estimate, and one call's positions share no timing characteristics with another's (the
     * async tail's queue may be draining a full GPU generation behind the sync slice's fresh one). */
    static final class BatchSizeController {
        /** Target ceiling for a single batch's fenced round trip -- see class doc. A fraction of a
         * 16.6ms (60fps) frame budget, matching {@link #SYNC_BUDGET}'s own frame-budget reasoning; the
         * async tail-drain thread has no per-frame deadline of its own, but keeping each of its
         * individual flushes bounded avoids one pathologically large batch starving
         * {@code SHARED_QUEUE_LOCK} for other GPU work (e.g. a same-frame render-thread upload) for too
         * long at once. */
        static final long BATCH_TIME_BUDGET_NANOS = 4_000_000L; // 4ms
        /** First batch's target size, before any real measurement exists -- small (well under a
         * frame's worth of fence overhead even in the worst case) so the very first flush's
         * measurement is cheap to obtain and cannot itself blow the budget. */
        static final int INITIAL_TARGET = 64;
        /** Floor for the adaptive target: even a batch that measured far over budget still flushes at
         * least this many slots per round trip, so a single always-slow flush cannot regress all the
         * way back to the pre-batching one-slot-per-submit behavior. */
        static final int MIN_TARGET = 8;
        /** Ceiling for the adaptive target -- bounds one command buffer's total embedded byte count
         * (each Standard-detail slot embeds up to ~23KB: 512 + 4096 + 16384 + 6144 bytes) and the native scratch churn
         * of a single flush, independent of how favorable the measured per-slot cost looks. */
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
     * the slot's light volume needs zeroing -- a DIFFERENT section is claiming this toroidal index, so
     * the previous owner's propagated light must not ghost through (see {@link
     * BrickGridUpload#clearLightSlot}'s own doc) -- leaving the actual GPU write to the caller's batch
     * entry ({@link BrickGridUpload.SlotUpload#clearLight()}). */
    private static boolean recordHarvest(int slot, SectionPos position, SectionHarvester.Result result) {
        SectionPos previousOwner = slotOwner.put(slot, position);
        slotData.put(slot, result);
        populatedSlots.add(slot); // harvest publish -- see the field's own doc
        return previousOwner != null && !previousOwner.equals(position);
    }

    /** Harvests and GPU-uploads {@code positions} in adaptively time-budgeted batches instead of one
     * fenced GPU round trip per slot -- the shared core of the render-thread synchronous slice and
     * {@link #RESYNC_EXECUTOR}'s tail-drain (see {@link #recenterAndResync}'s "Batched harvest upload"
     * doc). For each position: skips it if already valid (mirrors the old {@code resyncPosition}'s
     * semantics exactly -- an in-window, correctly-owned slot needs no work), else attempts a {@link
     * DirectSectionReader#read} bootstrap; a real harvest runs {@link #recordHarvest} (bookkeeping,
     * independent of whether a registry is attached, matching {@link #onSectionHarvested}'s own
     * unconditional bookkeeping) and queues a {@link BrickGridUpload.SlotUpload} into the current
     * batch. The batch flushes -- via {@link #flushBatch}, one {@link BrickGridUpload#uploadSlots} call
     * -- whenever it reaches the current {@link BatchSizeController} target, and once more at the end
     * for any partial remainder; a {@code null} registry skips the actual GPU call (matching {@link
     * #onSectionHarvested}'s no-op-without-a-registry behavior) but still periodically clears the
     * accumulator so an unattached-registry caller (rare -- no pack active) cannot let the batch list
     * grow without limit across a huge {@code positions}.
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
                                               LongConsumer onHarvestedBatch, @Nullable Runnable onProcessed) {
        TargetRegistry r = registry;
        List<BrickGridUpload.SlotUpload> batch = new ArrayList<>();
        BatchSizeController controller = new BatchSizeController();
        long batchHarvestCount = 0; // real harvests accumulated in the CURRENT unflushed batch

        for (SectionPos pos : positions) {
            if (!hasValidData(pos.x(), pos.y(), pos.z())) {
                SectionHarvester.Result result = DirectSectionReader.read(level, pos);
                if (result != null) {
                    int slot = slotFor(pos.x(), pos.y(), pos.z());
                    if (slot >= 0) {
                        boolean clearLight = recordHarvest(slot, pos, result);
                        batch.add(new BrickGridUpload.SlotUpload(slot, result, clearLight));
                        batchHarvestCount++;
                    }
                }
            }
            if (onProcessed != null) {
                onProcessed.run();
            }
            if (batch.size() >= controller.currentTargetSize()) {
                if (r != null) {
                    flushBatch(r, batch, controller);
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
                flushBatch(r, batch, controller);
            } else {
                batch.clear();
            }
        }
        if (batchHarvestCount > 0) {
            onHarvestedBatch.accept(batchHarvestCount);
        }
    }

    /** Flushes {@code batch} as one {@link BrickGridUpload#uploadSlots} submission, measures its real
     * wall-clock cost, and feeds that measurement into {@code controller} for the NEXT batch's target
     * size -- see {@link BatchSizeController}. {@code System.nanoTime()} brackets the call rather than
     * any GPU timestamp: the caller (the render thread's synchronous slice, or {@link
     * #RESYNC_EXECUTOR}'s background thread) genuinely blocks on {@code uploadSlots}'s own fence wait
     * for that duration, so this is a real measurement of this batch's cost, the same reasoning
     * {@code GraphRunner}'s own CPU-side compute-pass timing already relies on for a synchronously
     * fence-waited dispatch. Clears {@code batch} afterward so the caller's accumulator is empty for
     * the next round regardless of size. */
    private static void flushBatch(TargetRegistry registry, List<BrickGridUpload.SlotUpload> batch,
                                    BatchSizeController controller) {
        long start = System.nanoTime();
        BrickGridUpload.uploadSlots(registry, batch);
        long elapsedNanos = System.nanoTime() - start;
        controller.recordBatch(batch.size(), elapsedNanos);
        batch.clear();
    }

    /** Orders resync-shell positions so the render thread's synchronous {@link #SYNC_BUDGET} slice
     * covers what's most likely to matter THIS frame: camera-forward-facing slots first (see {@link
     * #isFront}), then nearest to the window center ({@link #squaredDistance}) within each group. Pure
     * -- no static state -- so it is unit-tested directly (VoxelWindowTest) without a live window or
     * GPU. {@code centerX}/{@code centerY}/{@code centerZ} is the NEW window center (the camera's own
     * section), the natural distance/direction origin since every position sorted here is a candidate
     * slot in the just-published window. */
    static Comparator<SectionPos> priorityComparator(int centerX, int centerY, int centerZ,
                                                       float forwardX, float forwardY, float forwardZ) {
        return Comparator
                .comparingInt((SectionPos p) -> isFront(p, centerX, centerY, centerZ, forwardX, forwardY, forwardZ) ? 0 : 1)
                .thenComparingLong(p -> squaredDistance(p, centerX, centerY, centerZ));
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
