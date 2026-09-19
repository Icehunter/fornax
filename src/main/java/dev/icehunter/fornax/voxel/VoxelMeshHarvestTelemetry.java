package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Host wall time for a harvest a MESH change asked for, which is the path a block edit takes.
 *
 * <p>Neither of the two counted paths covers it: {@code voxel_sync} counts the render thread and
 * {@code voxel_async} counts the resync worker, and a mesh edit runs on its own. Twenty profiler
 * dumps taken while placing and breaking blocks showed every one of those counters flat, which is
 * how this gap was found.
 *
 * <p>The span is request to commit: from the moment a mesh change asks for a harvest to the moment
 * the source window takes the replacement, which is when the light on screen can change. Its parts
 * are the wait in the queue, the harvest read, and the upload.
 *
 * <p>Totals and a worst case, not averages. A stall that happens once is what this is looking for,
 * and an average over a session buries it.
 *
 * <p>Each completed span also writes one log line, rate limited, so a reading needs no key press.
 */
public final class VoxelMeshHarvestTelemetry {
    public static final VoxelMeshHarvestTelemetry LIVE = new VoxelMeshHarvestTelemetry();
    /** At most this many lines a second, so a section storm cannot fill the log. */
    private static final long LOG_INTERVAL_NANOS = 50_000_000L;

    /** What a span spent before its upload. The upload is the rest of the total, since the commit
     * lands inside it. */
    private record Span(long requestedAt, long queueNanos, long readNanos, long dirtyNanos) {
    }

    /** A span per slot, so it can end on the thread the upload fence calls back on. A newer
     * request for the same slot replaces the older one, whose span is then dropped: two edits in
     * the same section are one answer, not two. */
    private final Map<Integer, Span> inFlight = new ConcurrentHashMap<>();
    /** When a section was marked for rebuild, by packed section. A block edit stamps this long
     * before Sodium's meshing task hands the section to Fornax, and the gap between the two is
     * scheduling this engine neither sees nor controls. */
    private final Map<Long, Long> dirtiedAt = new ConcurrentHashMap<>();
    /** The gap that stamp measured, held per section until the harvest it belongs to submits. */
    private final Map<Long, Long> dirtyGap = new ConcurrentHashMap<>();
    private long jobs, coalesced, queueNanos, readNanos, uploadNanos, commitNanos, maxCommitNanos;
    private long dirtyNanos, maxDirtyNanos;
    private long lastLogNanos;

    private VoxelMeshHarvestTelemetry() {
    }

    /** A block changed and its section wants rebuilding. */
    public void dirtied(int sectionX, int sectionY, int sectionZ) {
        // A section marked again before its rebuild keeps the FIRST stamp, which is the wait a
        // player watching that spot is actually sitting through.
        dirtiedAt.putIfAbsent(key(sectionX, sectionY, sectionZ), System.nanoTime());
        // An edit whose rebuild never arrives would otherwise hold a stamp forever. Far more than
        // a view's worth of sections means the ones at the bottom are never coming.
        if (dirtiedAt.size() > 8192) dirtiedAt.clear();
    }

    /** A mesh change asked for a harvest. Returns the time to carry through the stages. */
    public long requested(int sectionX, int sectionY, int sectionZ) {
        long now = System.nanoTime();
        Long stamp = dirtiedAt.remove(key(sectionX, sectionY, sectionZ));
        if (stamp != null) {
            long gap = Math.max(0L, now - stamp);
            dirtyGap.put(key(sectionX, sectionY, sectionZ), gap);
            synchronized (this) {
                dirtyNanos += gap;
                maxDirtyNanos = Math.max(maxDirtyNanos, gap);
            }
        }
        return now;
    }

    private static long key(int x, int y, int z) {
        return (x & 0x3FFFFFL) << 42 | (y & 0xFFFFFL) << 22 | (z & 0x3FFFFFL);
    }

    /** A request folded into one already waiting. The older request keeps its own start time, or
     * the wait being measured is the one thing hidden. */
    public synchronized void folded() {
        coalesced++;
    }

    /** The worker reached the request. */
    public synchronized void dequeued(long requestedAt) {
        queueNanos += Math.max(0L, System.nanoTime() - requestedAt);
    }

    /** The harvest read finished. */
    public synchronized void read(long nanos) {
        readNanos += Math.max(0L, nanos);
    }

    /**
     * About to hand the harvest over for upload.
     *
     * <p>Registered BEFORE the upload, not after. {@code BrickGridUpload.uploadSlots} calls the
     * commit back on this same thread before it returns, so a span registered afterwards is never
     * found by its own commit: it waits in the map until the NEXT edit of that section claims it,
     * and then reads as however long the player took to swing again.
     */
    public void submitting(int slot, int sectionX, int sectionY, int sectionZ, long requestedAt,
                           long queuedNanos, long readNanos) {
        Long gap = dirtyGap.remove(key(sectionX, sectionY, sectionZ));
        inFlight.put(slot, new Span(requestedAt, Math.max(0L, queuedNanos), Math.max(0L, readNanos),
                gap == null ? -1L : gap));
    }

    /** The source window took the replacement, which is when the light can change. */
    public void committed(int slot) {
        Span span = inFlight.remove(slot);
        if (span == null) return;
        long total = Math.max(0L, System.nanoTime() - span.requestedAt());
        long upload = Math.max(0L, total - span.queueNanos() - span.readNanos());
        boolean log;
        synchronized (this) {
            jobs++;
            uploadNanos += upload;
            commitNanos += total;
            maxCommitNanos = Math.max(maxCommitNanos, total);
            long now = System.nanoTime();
            log = now - lastLogNanos >= LOG_INTERVAL_NANOS;
            if (log) lastLogNanos = now;
        }
        if (log) {
            FornaxMod.LOGGER.info(
                    "[Fornax] mesh edit slot {}: dirty {} queue {} read {} upload {} total {} ms",
                    slot, span.dirtyNanos() < 0 ? "n/a" : ms(span.dirtyNanos()),
                    ms(span.queueNanos()), ms(span.readNanos()), ms(upload), ms(total));
        }
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /** A slot whose harvest was replaced before it committed keeps no start time. */
    public void dropped(int slot) {
        inFlight.remove(slot);
    }

    public synchronized void publish(BiConsumer<String, Double> sink) {
        sink.accept("mesh_edit_jobs", (double) jobs);
        sink.accept("mesh_edit_coalesced", (double) coalesced);
        sink.accept("mesh_edit_in_flight", (double) inFlight.size());
        sink.accept("mesh_edit_queue_total_ms", queueNanos / 1_000_000.0);
        sink.accept("mesh_edit_read_total_ms", readNanos / 1_000_000.0);
        sink.accept("mesh_edit_upload_total_ms", uploadNanos / 1_000_000.0);
        sink.accept("mesh_edit_commit_total_ms", commitNanos / 1_000_000.0);
        sink.accept("mesh_edit_max_ms", maxCommitNanos / 1_000_000.0);
        sink.accept("mesh_edit_dirty_total_ms", dirtyNanos / 1_000_000.0);
        sink.accept("mesh_edit_dirty_max_ms", maxDirtyNanos / 1_000_000.0);
    }

    /** Test seam: a fresh set of totals, since LIVE outlives any one test. */
    synchronized void reset() {
        jobs = coalesced = queueNanos = readNanos = uploadNanos = commitNanos = maxCommitNanos = 0L;
        dirtyNanos = maxDirtyNanos = 0L;
        lastLogNanos = 0L;
        inFlight.clear();
        dirtiedAt.clear();
        dirtyGap.clear();
    }

    synchronized long jobs() { return jobs; }
    synchronized long coalesced() { return coalesced; }
    synchronized long maxCommitNanos() { return maxCommitNanos; }
    synchronized long maxDirtyNanos() { return maxDirtyNanos; }
}
