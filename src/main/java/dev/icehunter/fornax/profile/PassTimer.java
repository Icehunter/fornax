package dev.icehunter.fornax.profile;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pipeline.FramePacing;

import java.util.OptionalLong;

/**
 * GPU per-pass timing: owns a ring of timestamp query pools so readback never stalls the render
 * thread waiting on the GPU. Frames-in-flight are handled by round-robining {@link #FRAMES_IN_FLIGHT}
 * pools -- by the time a pool comes back around to being "current" again, the GPU has long since
 * resolved the queries its previous occupant wrote, so a non-blocking read reliably has an answer.
 * Converted durations land in the supplied {@link FrameProfiler} {@link #FRAMES_IN_FLIGHT} frames
 * late -- this is a profiler, not a fence.
 *
 * <p>CRITICAL ordering invariant (pinned by {@code PassTimerRingTest}): the real backend's
 * {@code writeTimestamp} host-resets the query index immediately (Vulkan: {@code vkResetQueryPool}
 * on the host, before the GPU-side write is even queued -- see {@link TimestampPool#write}). So a
 * slot's previously-written values MUST be drained in {@link #beginFrame()}, BEFORE this frame's
 * first bracket write touches that pool's indices -- an end-of-frame readback of the same pool
 * would only ever see freshly-reset indices and record nothing, silently, forever.
 *
 * <p>Degrades to fully disabled (one warning logged) if the backend reports {@code timestampPeriod()
 * <= 0} or pool allocation throws -- every method then becomes a no-op with zero GPU calls, so an
 * unsupported backend never pays for (or crashes on) instrumentation it can't provide.
 */
public final class PassTimer {
    static final int FRAMES_IN_FLIGHT = FramePacing.FRAMES_IN_FLIGHT;
    // Query-pool budget: covers the geometry-dwell bracket + every timestamp-consuming graph pass +
    // the whole-frame endpoint. Measured, not guessed -- one real pack enables exactly 32 bracketable
    // passes at max quality (51 declared bracketable in its graph.toml) and Plague enables ~26 of 30
    // -- so 32 was already at that pack's ceiling with zero headroom; 64 clears it with margin for
    // Stage 3b's voxel GI passes. Each bracket consumes 2 query slots (begin + end), so each pool is
    // sized MAX_BRACKETS * 2.
    static final int MAX_BRACKETS = 64;
    // Bookkeeping-row budget, independent of the query-pool budget above: a graph can legally declare
    // more brackets than MAX_BRACKETS in one frame (a "phantom" bracket past the cap is still tracked
    // for correct nesting/poisoning, it just consumes no query index -- see bracketBegin/bracketEnd).
    // Sized with real headroom over MAX_BRACKETS since bookkeeping rows are cheap (no GPU resource).
    static final int MAX_ENTRIES = 96;

    private final FrameProfiler profiler;
    private boolean enabled;
    private boolean loggedDisabled;
    private boolean loggedMismatch;
    private boolean loggedOverrun;
    private float timestampPeriodNs;

    private final TimestampPool[] pools = new TimestampPool[FRAMES_IN_FLIGHT];
    // Per-slot bookkeeping describing what was written into that slot's pool the last time it was
    // current: label of each bracket, and the query indices its begin/end timestamps landed at.
    // Read back by beginFrame()'s drain when the slot comes around again, then rebuilt from scratch.
    // Sized to MAX_ENTRIES, not MAX_BRACKETS -- a phantom entry (see bracketBegin) still gets a row
    // here, just no query indices.
    private final String[][] slotLabels = new String[FRAMES_IN_FLIGHT][MAX_ENTRIES];
    private final int[][] slotBeginIndex = new int[FRAMES_IN_FLIGHT][MAX_ENTRIES];
    private final int[][] slotEndIndex = new int[FRAMES_IN_FLIGHT][MAX_ENTRIES];
    private final int[] slotCount = new int[FRAMES_IN_FLIGHT];
    // How many of this slot's entries actually consumed a query-pool index (i.e. were NOT phantoms).
    // slotCount - slotTimedCount is this frame's drop count, published via FrameProfiler.recordValue.
    private final int[] slotTimedCount = new int[FRAMES_IN_FLIGHT];
    private final int[] slotNextQueryIndex = new int[FRAMES_IN_FLIGHT];

    // Open (begun-but-not-ended) brackets in the current slot, as entry indices into this slot's
    // bookkeeping arrays -- a plain int stack, since brackets nest strictly (frame > geometry dwell /
    // per-pass). bracketEnd() verifies the label matches the top of stack and poisons the frame on
    // mismatch rather than ever pairing a begin timestamp with the wrong end silently. A phantom
    // bracket (see bracketBegin) is pushed here exactly like a timed one, so this label check and
    // endFrame()'s "openDepth != 0 means an unclosed bracket" rule need no special case for it.
    private final int[] openStack = new int[MAX_ENTRIES];
    private int openDepth;
    private boolean frameCorrupt;

    private int slot = FRAMES_IN_FLIGHT - 1; // first beginFrame() advances this to 0

    // Pre-HUD, this throttled summary line was the only externally observable proof the timing path
    // was alive end-to-end; now that ProfilerOverlay/ProfilerLogDump give players their own view,
    // it's demoted to DEBUG -- kept only for headless (no-GUI) diagnosis, ~one line per 15s at 60fps.
    private static final int LOG_INTERVAL_FRAMES = 900;
    private long framesEnded;

    /** Production path: builds the pool ring on the live device, or degrades to disabled. */
    public PassTimer(FrameProfiler profiler) {
        this.profiler = profiler;

        GpuDevice device = RenderSystem.getDevice();
        float period = device.getDeviceInfo().timestampPeriod();
        if (period <= 0f) {
            disable();
            return;
        }
        try {
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                pools[i] = new GpuTimestampPool(device.createTimestampQueryPool(MAX_BRACKETS * 2));
            }
            timestampPeriodNs = period;
            enabled = true;
        } catch (RuntimeException e) {
            closePools();
            disable();
        }
    }

    /** Test seam: injects fake pools so the ring/drain ordering is verifiable without a GPU. */
    PassTimer(FrameProfiler profiler, TimestampPool[] testPools, float periodNs) {
        this.profiler = profiler;
        System.arraycopy(testPools, 0, pools, 0, FRAMES_IN_FLIGHT);
        this.timestampPeriodNs = periodNs;
        this.enabled = periodNs > 0f;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rotates to the next ring slot and -- FIRST, before any of this frame's writes host-reset the
     * pool's indices -- drains the values the slot's previous occupant ({@link #FRAMES_IN_FLIGHT}
     * frames ago) left behind. A value the GPU hasn't resolved yet reads empty and is skipped
     * silently (ring still warming up, or an exceptionally long frame in flight).
     */
    public void beginFrame() {
        if (!enabled) {
            return;
        }
        slot = (slot + 1) % FRAMES_IN_FLIGHT;
        drainSlot(slot);

        slotCount[slot] = 0;
        slotTimedCount[slot] = 0;
        slotNextQueryIndex[slot] = 0;
        openDepth = 0;
        frameCorrupt = false;
    }

    public void bracketBegin(String label) {
        if (!enabled || frameCorrupt) {
            return;
        }
        int count = slotCount[slot];
        if (count >= MAX_ENTRIES) {
            // Bookkeeping-row overrun, not just a query-budget overrun: even a phantom has nowhere
            // to record its label/nesting. Unlike the MAX_BRACKETS case below, there is no correct
            // way to track this bracket's close, so poison rather than risk a later bracketEnd()
            // matching the wrong open entry.
            frameCorrupt = true;
            if (!loggedMismatch) {
                FornaxMod.LOGGER.warn("[Fornax] PassTimer exceeded MAX_ENTRIES ({}) at '{}'; "
                        + "dropping this frame's timings", MAX_ENTRIES, label);
                loggedMismatch = true;
            }
            return;
        }
        boolean phantom = slotTimedCount[slot] >= MAX_BRACKETS;
        if (phantom) {
            // Past the query-pool budget: still tracked (pushed to openStack, given a bookkeeping
            // row) so nesting/poisoning stays correct, but consumes no query index -- drainSlot()
            // already skips endIndex < 0, so this never reaches the profiler as a fabricated 0ms row.
            slotBeginIndex[slot][count] = -1;
            if (!loggedOverrun) {
                FornaxMod.LOGGER.warn("[Fornax] graph declares more than {} timed brackets; '{}' "
                        + "and later passes are untimed this session (raise PassTimer.MAX_BRACKETS)",
                        MAX_BRACKETS, label);
                loggedOverrun = true;
            }
        } else {
            int queryIndex = slotNextQueryIndex[slot]++;
            pools[slot].write(queryIndex);
            slotBeginIndex[slot][count] = queryIndex;
            slotTimedCount[slot]++;
        }
        slotLabels[slot][count] = label;
        slotEndIndex[slot][count] = -1;
        openStack[openDepth++] = count;
        slotCount[slot] = count + 1;
    }

    public void bracketEnd(String label) {
        if (!enabled || frameCorrupt) {
            return;
        }
        if (openDepth == 0 || !label.equals(slotLabels[slot][openStack[openDepth - 1]])) {
            // Mispaired end (a genuine caller nesting bug -- NOT an overrun, which tracks as an open
            // phantom entry and matches this check normally): poison the whole frame rather
            // than ever pairing a begin timestamp with the wrong end. endFrame() zeroes the slot's
            // bookkeeping so the poisoned indices are never drained.
            frameCorrupt = true;
            if (!loggedMismatch) {
                FornaxMod.LOGGER.warn("[Fornax] PassTimer bracket mismatch at '{}'; dropping this frame's timings", label);
                loggedMismatch = true;
            }
            return;
        }
        int entry = openStack[--openDepth];
        if (slotBeginIndex[slot][entry] < 0) {
            return; // phantom -- no query index was ever written for this bracket
        }
        int queryIndex = slotNextQueryIndex[slot]++;
        pools[slot].write(queryIndex);
        slotEndIndex[slot][entry] = queryIndex;
    }

    /**
     * Closes out the frame's bookkeeping. The actual GPU readback happens in {@link #beginFrame()}
     * {@link #FRAMES_IN_FLIGHT} frames from now (see the class invariant above) -- this only
     * validates that every bracket was closed, dropping the frame's data if not.
     */
    public void endFrame() {
        if (!enabled) {
            return;
        }
        if (frameCorrupt || openDepth != 0) {
            slotCount[slot] = 0; // drop -- never drain a mispaired or half-open frame
        } else {
            // Published every healthy frame (not just when > 0) so the HUD reflects the CURRENT
            // count rather than latching the last nonzero value forever once a graph change stops
            // overrunning MAX_BRACKETS -- see FrameProfiler.recordValue's own "latest-value-wins"
            // semantics.
            profiler.recordValue("timer drops", slotCount[slot] - slotTimedCount[slot]);
        }
        if (++framesEnded % LOG_INTERVAL_FRAMES == 0) {
            logSummary();
        }
    }

    private void logSummary() {
        java.util.List<FrameProfiler.Stat> stats = profiler.snapshot();
        if (stats.isEmpty()) {
            return; // nothing resolved yet (warm-up, or the GPU never caught up) -- stay quiet
        }
        StringBuilder line = new StringBuilder();
        for (FrameProfiler.Stat s : stats) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(String.format(java.util.Locale.ROOT, "%s %.3f/%.3f", s.label(), s.avgMs(), s.p95Ms()));
        }
        FornaxMod.LOGGER.debug("[Fornax] GPU pass timing ms avg/p95: {}", line);
    }

    private void drainSlot(int s) {
        TimestampPool pool = pools[s];
        for (int i = 0; i < slotCount[s]; i++) {
            int endIndex = slotEndIndex[s][i];
            if (endIndex < 0) {
                continue;
            }
            OptionalLong begin = pool.tryRead(slotBeginIndex[s][i]);
            OptionalLong end = pool.tryRead(endIndex);
            if (begin.isEmpty() || end.isEmpty()) {
                continue; // not resolved yet -- results land frames late, this is a profiler not a fence
            }
            profiler.record(slotLabels[s][i], ticksToMs(begin.getAsLong(), end.getAsLong(), timestampPeriodNs));
        }
    }

    public void close() {
        closePools();
        enabled = false;
    }

    private void closePools() {
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (pools[i] != null) {
                try {
                    pools[i].close();
                } catch (RuntimeException ignored) {
                    // best-effort teardown; a failed close must not take down the frame
                }
                pools[i] = null;
            }
        }
    }

    private void disable() {
        if (!loggedDisabled) {
            FornaxMod.LOGGER.warn("[Fornax] GPU timestamps unsupported on this backend; frame profiler disabled");
            loggedDisabled = true;
        }
        enabled = false;
    }

    // Pure seam, unit-tested: guarded by isEnabled() at call sites, not here.
    static double ticksToMs(long startTicks, long endTicks, float periodNs) {
        return (endTicks - startTicks) * (double) periodNs / 1_000_000.0;
    }

    /** The real adapter: {@code CommandEncoder.writeTimestamp} + non-blocking {@code getValue}. */
    private static final class GpuTimestampPool implements TimestampPool {
        private final GpuQueryPool pool;

        GpuTimestampPool(GpuQueryPool pool) {
            this.pool = pool;
        }

        @Override
        public void write(int index) {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index);
        }

        @Override
        public OptionalLong tryRead(int index) {
            return pool.getValue(index);
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
