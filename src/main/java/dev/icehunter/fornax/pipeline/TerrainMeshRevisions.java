package dev.icehunter.fornax.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/** Per-storage stamps drawn from one process-wide sequence. New storage starts with fresh stamps,
 * so a recycled region/slot cannot alias a cached mesh even before its first upload. */
public final class TerrainMeshRevisions {
    // Zero is reserved for consumers' unknown sentinel. Overflow throws instead of reusing an ID.
    private static final AtomicLong NEXT_REVISION = new AtomicLong();
    private final long[] revisions;

    public TerrainMeshRevisions(int sectionCount) {
        if (sectionCount <= 0) throw new IllegalArgumentException("terrain storage must contain at least one section");
        revisions = new long[sectionCount];
        invalidateAll();
    }

    public synchronized long revision(int section) {
        return revisions[section];
    }

    public synchronized void invalidate(int section) {
        java.util.Objects.checkIndex(section, revisions.length);
        revisions[section] = NEXT_REVISION.updateAndGet(Math::incrementExact);
    }

    public synchronized void invalidateAll() {
        for (int section = 0; section < revisions.length; section++) invalidate(section);
    }
}
