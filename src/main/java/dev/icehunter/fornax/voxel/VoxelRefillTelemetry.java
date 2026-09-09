package dev.icehunter.fornax.voxel;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Host wall-time totals for completed resync jobs, retained until process exit. Ordinary mesh
 * harvests and light-only refresh jobs are excluded. READ includes LIGHT; UPLOAD includes RECORD,
 * RESET, SUBMIT, WAIT and COMMIT. These overlapping spans must not be summed as GPU busy time. */
public final class VoxelRefillTelemetry {
    public static final VoxelRefillTelemetry LIVE = new VoxelRefillTelemetry();
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    enum Phase {
        READ("read"), LIGHT("light"), LOCK("lock_wait"), UPLOAD("upload"),
        RESET("transfer_reset"), RECORD("pack_record"), SUBMIT("submit"), WAIT("fence_wait"), COMMIT("commit");
        final String label;
        Phase(String label) { this.label = label; }
    }
    enum Count {
        VISITED, READ, NULL_READ, READ_OK, ALREADY_VALID, OBSOLETE, RETIRED, REJECTED,
        OUTSIDE_HEIGHT, LIGHT_CELLS, PACKED, COMMITTED, BATCHES;
    }
    private final long[] nanos = new long[Phase.values().length];
    private final long[] counts = new long[Count.values().length];
    private long jobs, failedJobs, queueNanos, workNanos, maxJobNanos;
    private int activeJobs;

    record Snapshot(long jobs, long failedJobs, int activeJobs, long queueNanos, long workNanos,
                    long maxJobNanos, long[] nanos, long[] counts) {
        long nanos(Phase phase) { return nanos[phase.ordinal()]; }
        long count(Count count) { return counts[count.ordinal()]; }
    }

    Job begin(long queuedAt, LongSupplier clock) {
        var job = new Job(this, queuedAt, clock, CURRENT.get());
        synchronized (this) { activeJobs++; }
        CURRENT.set(job);
        return job;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(jobs, failedJobs, activeJobs, queueNanos, workNanos, maxJobNanos,
                nanos.clone(), counts.clone());
    }

    /** Called on the render thread: FrameProfiler itself is not safe for worker writes. */
    public void publish(BiConsumer<String, Double> sink) {
        var s = snapshot();
        sink.accept("refill_jobs_total", (double) s.jobs());
        sink.accept("refill_jobs_active", (double) s.activeJobs());
        sink.accept("refill_jobs_failed", (double) s.failedJobs());
        // Nanoseconds to milliseconds; cumulative wall time can overlap across workers/jobs.
        sink.accept("refill_queue_total_ms", s.queueNanos() / 1_000_000.0);
        sink.accept("refill_work_total_ms", s.workNanos() / 1_000_000.0);
        sink.accept("refill_max_job_ms", s.maxJobNanos() / 1_000_000.0);
        for (Phase p : Phase.values()) sink.accept("refill_" + p.label + "_total_ms", s.nanos(p) / 1_000_000.0);
        for (Count c : Count.values()) sink.accept("refill_" + c.name().toLowerCase(java.util.Locale.ROOT), (double) s.count(c));
    }

    static long start() {
        Job job = CURRENT.get();
        return job == null ? 0 : job.clock.getAsLong();
    }

    static void finish(Phase phase, long start) {
        Job job = CURRENT.get();
        if (job != null) job.nanos[phase.ordinal()] += job.clock.getAsLong() - start;
    }

    static void count(Count count) { add(count, 1); }
    static void add(Count count, long amount) {
        Job job = CURRENT.get();
        if (job != null) job.counts[count.ordinal()] += amount;
    }

    static final class Job implements AutoCloseable {
        private final VoxelRefillTelemetry owner;
        private final Job parent;
        private final LongSupplier clock;
        private final long started, queued;
        private final long[] nanos = new long[Phase.values().length];
        private final long[] counts = new long[Count.values().length];
        private boolean completed, closed;
        Job(VoxelRefillTelemetry owner, long queuedAt, LongSupplier clock, Job parent) {
            this.owner = owner; this.clock = clock; this.parent = parent;
            started = clock.getAsLong(); queued = started - queuedAt;
        }
        void complete() { completed = true; }
        @Override public void close() {
            if (closed) return;
            long elapsed = clock.getAsLong() - started;
            if (parent == null) CURRENT.remove();
            else CURRENT.set(parent);
            synchronized (owner) {
                owner.activeJobs--; owner.jobs++;
                if (!completed) owner.failedJobs++;
                owner.queueNanos += queued; owner.workNanos += elapsed;
                owner.maxJobNanos = Math.max(owner.maxJobNanos, elapsed);
                for (int i = 0; i < nanos.length; i++) owner.nanos[i] += nanos[i];
                for (int i = 0; i < counts.length; i++) owner.counts[i] += counts[i];
            }
            closed = true;
        }
    }
}
