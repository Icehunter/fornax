package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional tier-zero GPU ownership and payload revisions. Mutations hold SHARED_QUEUE_LOCK;
 * concurrent readers may inspect the latest CPU upload token, which is not proof of GPU publication. */
public final class VoxelSectionState {
    public static final String TARGET = "voxelSectionState";
    // Three absolute owner coordinates, generation, two revisions, validity, one reserved word.
    public static final int WORDS_PER_SLOT = 8;
    public static final int BYTES_PER_SLOT = WORDS_PER_SLOT * Integer.BYTES;
    // Zero-filled allocation and cleared slots must be invalid, including at absolute section zero.
    public static final int PENDING = 0;
    public static final int COMMITTED = 1;

    /** The immutable payload identity captured when a harvest is queued. Revisions count accepted
     * snapshots, not equality of block bytes or a promise that the world has stopped changing. */
    public record Snapshot(int x, int y, int z, int storageGeneration,
                           int geometryRevision, int contentRevision) { }

    private final Map<Integer, Snapshot> latest = new ConcurrentHashMap<>();
    private final Map<Integer, Snapshot> committed = new ConcurrentHashMap<>();
    private int generation;
    private int geometryRevision;
    private int contentRevision;

    public void reset(long storageGeneration) {
        int nextGeneration = Math.toIntExact(storageGeneration);
        if (nextGeneration <= 0) throw new IllegalArgumentException("Voxel storage generation must be positive");
        generation = nextGeneration;
        geometryRevision = 0;
        contentRevision = 0;
        latest.clear();
        committed.clear();
    }

    public Snapshot geometry(int slot, SectionPos owner) {
        if (generation == 0) throw new IllegalStateException("Voxel section state has no storage generation");
        int nextGeometry = Math.incrementExact(geometryRevision);
        int nextContent = Math.incrementExact(contentRevision);
        Snapshot snapshot = new Snapshot(owner.x(), owner.y(), owner.z(), generation, nextGeometry, nextContent);
        geometryRevision = nextGeometry;
        contentRevision = nextContent;
        latest.put(slot, snapshot);
        return snapshot;
    }

    public Snapshot content(int slot) {
        Snapshot previous = latest.get(slot);
        if (previous == null) throw new IllegalStateException("Voxel slot " + slot + " has no geometry snapshot");
        int nextContent = Math.incrementExact(contentRevision);
        Snapshot snapshot = new Snapshot(previous.x(), previous.y(), previous.z(), generation,
                previous.geometryRevision(), nextContent);
        contentRevision = nextContent;
        latest.put(slot, snapshot);
        return snapshot;
    }

    /** A superseded CPU owner cannot cancel the clear still required by the GPU's actual owner. */
    public boolean needsLightClear(int slot, Snapshot snapshot) {
        Snapshot previous = committed.get(slot);
        return previous == null || previous.x() != snapshot.x() || previous.y() != snapshot.y()
                || previous.z() != snapshot.z();
    }

    public void commit(int slot, Snapshot snapshot) {
        if (isCurrent(slot, snapshot)) committed.put(slot, snapshot);
    }

    public void invalidate(Collection<Integer> slots) {
        for (int slot : slots) latest.remove(slot);
    }

    public boolean isCurrent(int slot, @Nullable Snapshot snapshot) {
        return snapshot != null && snapshot.equals(latest.get(slot));
    }

    public boolean hasOwner(int slot, SectionPos owner) {
        Snapshot snapshot = latest.get(slot);
        return snapshot != null && snapshot.x() == owner.x() && snapshot.y() == owner.y() && snapshot.z() == owner.z();
    }

    /** Scalar uint ABI, words 0..2 owner xyz, 3 generation, 4 geometry revision, 5 content revision,
     * 6 validity, 7 reserved zero. This is called only while recording the payload's own transfer:
     * packing a queued CPU token into another submission would falsely certify unwritten bytes. */
    public static void pack(@Nullable Snapshot snapshot, ByteBuffer destination) {
        destination.clear();
        destination.limit(BYTES_PER_SLOT);
        for (int word = 0; word < WORDS_PER_SLOT; word++) destination.putInt(word * Integer.BYTES, 0);
        if (snapshot == null) return;
        destination.putInt(0, snapshot.x());
        destination.putInt(Integer.BYTES, snapshot.y());
        destination.putInt(2 * Integer.BYTES, snapshot.z());
        destination.putInt(3 * Integer.BYTES, snapshot.storageGeneration());
        destination.putInt(4 * Integer.BYTES, snapshot.geometryRevision());
        destination.putInt(5 * Integer.BYTES, snapshot.contentRevision());
        destination.putInt(6 * Integer.BYTES, COMMITTED);
    }
}
