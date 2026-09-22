package dev.icehunter.fornax.pipeline;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Counts which storage event invalidated a section this frame, answering why a mesh rebuilt on a
 * frame with no block change instead of a nonzero total. Counters are {@link AtomicInteger},
 * matching the sibling {@link TerrainMeshRevisions}, which also assumes no single writer thread. */
public final class TerrainMeshRevisionStats {

    public enum Site { VERTEX_DATA, SECTION_REMOVAL, STORAGE_REPLACEMENT }

    private static final AtomicInteger vertexData = new AtomicInteger();
    private static final AtomicInteger sectionRemoval = new AtomicInteger();
    private static final AtomicInteger storageReplacement = new AtomicInteger();

    private TerrainMeshRevisionStats() {
    }

    public static void count(Site site) {
        switch (site) {
            case VERTEX_DATA -> vertexData.incrementAndGet();
            case SECTION_REMOVAL -> sectionRemoval.incrementAndGet();
            case STORAGE_REPLACEMENT -> storageReplacement.incrementAndGet();
        }
    }

    /** Records this frame's counts and resets them, so next frame starts from zero. */
    public static void publish(BiConsumer<String, Double> recorder) {
        int vertex = vertexData.getAndSet(0);
        int removal = sectionRemoval.getAndSet(0);
        int replacement = storageReplacement.getAndSet(0);
        recorder.accept("rt_mesh_invalidate_vertex", (double) vertex);
        recorder.accept("rt_mesh_invalidate_remove", (double) removal);
        recorder.accept("rt_mesh_invalidate_resize", (double) replacement);
    }
}
