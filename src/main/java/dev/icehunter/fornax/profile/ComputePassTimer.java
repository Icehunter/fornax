package dev.icehunter.fornax.profile;

import dev.icehunter.fornax.pipeline.FramePacing;
import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * Pure ring bookkeeping for one raw compute pass's Vulkan timestamp-query pool. The native query
 * pool remains owned by the injected {@link QueryResults}; this class only decides when a pair is
 * eligible for readback and publishes the resolved duration to {@link FrameProfiler}.
 *
 * <p>A slot becomes readable only after its matching compute submission fence succeeds. The caller
 * then invokes {@link #drainCompleted(int)} before resetting that slot's command pool or query pair.
 * Unresolved results are dropped rather than blocking: profiling must never become a second
 * synchronization requirement.
 */
public final class ComputePassTimer implements AutoCloseable {
    /** Minimal injectable seam over the raw query pool, keeping ring behavior GPU-free in tests. */
    public interface QueryResults extends AutoCloseable {
        OptionalLong tryRead(int queryIndex);

        @Override
        void close();
    }

    private final FrameProfiler profiler;
    private final String label;
    @Nullable
    private final QueryResults queries;
    private final float timestampPeriodNs;
    private final int timestampValidBits;
    private final boolean[] submitted = new boolean[FramePacing.FRAMES_IN_FLIGHT];

    /** A null source, non-positive period, or zero valid-bit width creates a no-op timer. */
    public ComputePassTimer(FrameProfiler profiler, String label,
                            @Nullable QueryResults queries, float timestampPeriodNs,
                            int timestampValidBits) {
        if (timestampValidBits < 0 || timestampValidBits > Long.SIZE) {
            throw new IllegalArgumentException("timestamp valid-bit width out of range: "
                    + timestampValidBits);
        }
        this.profiler = profiler;
        this.label = label;
        this.queries = queries;
        this.timestampPeriodNs = timestampPeriodNs;
        this.timestampValidBits = timestampValidBits;
    }

    public boolean isEnabled() {
        return queries != null && timestampPeriodNs > 0f && timestampValidBits > 0;
    }

    /** Publishes a query pair only after the raw queue submission which writes it succeeds. */
    public void markSubmitted(int slot) {
        checkSlot(slot);
        if (isEnabled()) {
            submitted[slot] = true;
        }
    }

    /**
     * Drains one query pair after that slot's fence has completed. This consumes the pending marker
     * even when a driver returns unresolved data because the caller is about to reset the pair.
     */
    public void drainCompleted(int slot) {
        checkSlot(slot);
        if (!isEnabled() || !submitted[slot]) {
            return;
        }
        submitted[slot] = false;

        QueryResults source = queries;
        if (source == null) {
            return;
        }
        int firstQuery = slot * 2;
        OptionalLong start = source.tryRead(firstQuery);
        OptionalLong end = source.tryRead(firstQuery + 1);
        if (start.isEmpty() || end.isEmpty()) {
            return;
        }
        profiler.record(label, ticksToMs(start.getAsLong(), end.getAsLong(),
                timestampPeriodNs, timestampValidBits));
    }

    @Override
    public void close() {
        if (queries != null) {
            queries.close();
        }
    }

    static double ticksToMs(long startTicks, long endTicks, float periodNs, int validBits) {
        long elapsedTicks;
        if (validBits == Long.SIZE) {
            // Java's defined two's-complement overflow is exactly subtraction modulo 2^64. A real
            // dispatch cannot approach the 2^63-tick ambiguity boundary, so the result is positive.
            elapsedTicks = endTicks - startTicks;
        } else {
            long mask = (1L << validBits) - 1L;
            long start = startTicks & mask;
            long end = endTicks & mask;
            elapsedTicks = (end - start) & mask;
        }
        return elapsedTicks * (double) periodNs / 1_000_000.0;
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= FramePacing.FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException("compute timestamp slot out of range: " + slot);
        }
    }
}
