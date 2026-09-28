package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Pending voxel writes, coalesced by slot and acknowledged only after a successful transfer.
 * Payloads must be immutable after publication. This class orders CPU work; GPU reader/writer
 * synchronization belongs to the caller. Clears are never delayed by the geometry upload budget. */
public final class VoxelUploadQueue<T> implements AutoCloseable {
    public record Entry<T>(int slot, @Nullable T data, boolean clearOccupancy, boolean clearLight) { }

    private record Ticket<T>(Entry<T> entry, long dataRevision, long clearRevision, long lightRevision) { }

    public static final class Snapshot<T> {
        private final VoxelUploadQueue<T> owner;
        private final long generation;
        private final List<Ticket<T>> tickets;
        private final List<Entry<T>> entries;
        private final long metadataRevision;
        private final long atlasGeneration;

        private Snapshot(VoxelUploadQueue<T> owner, long generation, List<Ticket<T>> tickets,
                         long metadataRevision, long atlasGeneration) {
            this.owner = owner;
            this.generation = generation;
            this.tickets = List.copyOf(tickets);
            this.entries = tickets.stream().map(Ticket::entry).toList();
            this.metadataRevision = metadataRevision;
            this.atlasGeneration = atlasGeneration;
        }

        public long generation() { return generation; }
        public List<Entry<T>> entries() { return entries; }
        public boolean invalidateMetadata() { return metadataRevision != 0; }
        public long atlasGeneration() { return atlasGeneration; }
        public boolean isEmpty() { return entries.isEmpty() && !invalidateMetadata(); }
    }

    private static final class Pending<T> {
        @Nullable T data;
        long dataRevision, clearRevision, lightRevision;
        boolean isEmpty() { return dataRevision == 0 && clearRevision == 0 && lightRevision == 0; }
    }

    private final LinkedHashMap<Integer, Pending<T>> pending = new LinkedHashMap<>();
    private long generation, revision, metadataRevision, atlasGeneration;
    private boolean metadataKnown, closed;

    public VoxelUploadQueue(long generation) { this.generation = generation; }

    /** A stale worker publication is rejected without disturbing current-generation work. */
    public synchronized boolean publish(long expectedGeneration, int slot, T data, boolean clearLight) {
        requireOpen();
        requireSlot(slot);
        Objects.requireNonNull(data, "data");
        if (expectedGeneration != generation) return false;
        Pending<T> entry = pending.computeIfAbsent(slot, ignored -> new Pending<>());
        entry.data = data;
        entry.dataRevision = nextRevision();
        if (clearLight) entry.lightRevision = nextRevision();
        return true;
    }

    /** Invalidates geometry immediately at the next drain and discards an older queued payload. */
    public synchronized boolean clear(long expectedGeneration, int slot) {
        requireOpen();
        requireSlot(slot);
        if (expectedGeneration != generation) return false;
        Pending<T> entry = pending.computeIfAbsent(slot, ignored -> new Pending<>());
        entry.data = null;
        entry.dataRevision = 0;
        entry.clearRevision = nextRevision();
        return true;
    }

    /** A light-volume reset does not erase queued geometry or an occupancy invalidation. */
    public synchronized boolean clearLight(long expectedGeneration, int slot) {
        requireOpen();
        requireSlot(slot);
        if (expectedGeneration != generation) return false;
        pending.computeIfAbsent(slot, ignored -> new Pending<>()).lightRevision = nextRevision();
        return true;
    }

    public synchronized boolean invalidateMetadata(long expectedGeneration, long nextAtlasGeneration) {
        requireOpen();
        if (expectedGeneration != generation) return false;
        if (!metadataKnown || atlasGeneration != nextAtlasGeneration) {
            metadataKnown = true;
            atlasGeneration = nextAtlasGeneration;
            metadataRevision = nextRevision();
        }
        return true;
    }

    /** Storage replacement drops every old slot ticket and requires known metadata to be reset. */
    public synchronized void replaceGeneration(long nextGeneration) {
        requireOpen();
        if (nextGeneration < generation) throw new IllegalArgumentException("generation cannot decrease");
        if (nextGeneration == generation) return;
        generation = nextGeneration;
        pending.clear();
        metadataRevision = metadataKnown ? nextRevision() : 0;
    }

    public synchronized Snapshot<T> snapshot(int uploadLimit) {
        requireOpen();
        if (uploadLimit < 0) throw new IllegalArgumentException("upload limit cannot be negative");
        List<Ticket<T>> tickets = new ArrayList<>();
        int uploads = 0;
        for (var item : pending.entrySet()) {
            Pending<T> entry = item.getValue();
            boolean includeData = entry.dataRevision != 0 && uploads < uploadLimit;
            if (!includeData && entry.clearRevision == 0 && entry.lightRevision == 0) continue;
            if (includeData) uploads++;
            Entry<T> value = new Entry<>(item.getKey(), includeData ? entry.data : null,
                    entry.clearRevision != 0, entry.lightRevision != 0);
            tickets.add(new Ticket<>(value, includeData ? entry.dataRevision : 0,
                    entry.clearRevision, entry.lightRevision));
        }
        return new Snapshot<>(this, generation, tickets, metadataRevision, atlasGeneration);
    }

    /** Call only after the entire snapshot's transfer succeeds. A failed transfer leaves it pending.
     * Revision checks preserve any updates published after this snapshot was taken. */
    public synchronized void acknowledge(Snapshot<T> snapshot) {
        requireOpen();
        if (snapshot.owner != this) throw new IllegalArgumentException("snapshot belongs to another queue");
        if (snapshot.generation != generation) return;
        for (Ticket<T> ticket : snapshot.tickets) {
            int slot = ticket.entry.slot();
            Pending<T> entry = pending.get(slot);
            if (entry == null) continue;
            if (ticket.dataRevision != 0 && entry.dataRevision == ticket.dataRevision) {
                entry.data = null;
                entry.dataRevision = 0;
            }
            if (ticket.clearRevision != 0 && entry.clearRevision == ticket.clearRevision)
                entry.clearRevision = 0;
            if (ticket.lightRevision != 0 && entry.lightRevision == ticket.lightRevision)
                entry.lightRevision = 0;
            if (entry.isEmpty()) pending.remove(slot);
        }
        if (snapshot.metadataRevision != 0 && metadataRevision == snapshot.metadataRevision)
            metadataRevision = 0;
    }

    public synchronized boolean hasPending() { return !pending.isEmpty() || metadataRevision != 0; }
    public synchronized long generation() { return generation; }

    @Override public synchronized void close() {
        if (closed) return;
        pending.clear();
        metadataRevision = 0;
        closed = true;
    }

    private long nextRevision() { return revision = Math.incrementExact(revision); }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("voxel upload queue is closed");
    }
    private static void requireSlot(int slot) {
        if (slot < 0) throw new IllegalArgumentException("slot cannot be negative");
    }
}
