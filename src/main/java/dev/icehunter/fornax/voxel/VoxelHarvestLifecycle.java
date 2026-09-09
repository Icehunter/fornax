package dev.icehunter.fornax.voxel;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;

/** Owns CPU model/texel read leases across block-atlas retirement and model publication.
 * Lifecycle callbacks run on the render thread; readers may be meshing or resync workers.
 * A lease must end before any GPU upload lock is acquired. */
public final class VoxelHarvestLifecycle {
    private static final Gate GATE = new Gate();
    private VoxelHarvestLifecycle() { }
    public static long generation() { return GATE.generation(); }
    public static boolean isAvailable() { return GATE.isAvailable(); }
    /** Lock-free eligibility check, also safe while an uploader holds its own queue lock. */
    public static boolean isCurrent(long generation) { return GATE.isCurrent(generation); }
    public static @Nullable Lease tryAcquire() { return GATE.tryAcquire(GATE.generation()); }
    public static @Nullable Lease tryAcquire(long generation) { return GATE.tryAcquire(generation); }

    /** Called before the block atlas frees any sprite pixels, on either reload or close. */
    public static void onBlockAtlasRetired() {
        GATE.retireAndDrain();
        // No read/write lease remains held when storage takes the GPU queue lock.
        VoxelWindow.invalidateModelData();
    }

    /** Only a successful model publication may reopen reads; atlas upload alone is insufficient. */
    public static void onModelsPublished() {
        // A paused frame must not leave a stationary camera believing its shell was harvested.
        VoxelWindow.invalidateModelData();
        GATE.modelsPublished();
    }

    static final class Gate {
        private record State(long generation, boolean available) { }
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private volatile State state = new State(0, true);
        long generation() { return this.state.generation(); }
        boolean isAvailable() { return this.state.available(); }
        boolean isCurrent(long generation) {
            State current = this.state;
            return current.available() && current.generation() == generation;
        }

        @Nullable Lease tryAcquire(long generation) {
            // An inner harvest lease must not wait for publication while its caller holds an
            // outer read lease and retirement is waiting to drain it.
            if (!isCurrent(generation)) return null;
            var read = this.lock.readLock();
            read.lock();
            if (!isCurrent(generation)) {
                read.unlock();
                return null;
            }
            return new Lease(read, generation);
        }

        synchronized void retireAndDrain() {
            // Cancel before waiting: completed old results cannot upload while another reader
            // is still finishing its last safe access to the old pixels.
            this.state = new State(Math.incrementExact(this.state.generation()), false);
            var write = this.lock.writeLock();
            write.lock();
            write.unlock();
        }

        synchronized void modelsPublished() {
            long generation = Math.incrementExact(this.state.generation());
            this.state = new State(generation, false);
            var write = this.lock.writeLock();
            write.lock();
            try {
                this.state = new State(generation, true);
            } finally {
                write.unlock();
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ReentrantReadWriteLock.ReadLock read;
        private final long generation;
        private boolean closed;
        private Lease(ReentrantReadWriteLock.ReadLock read, long generation) {
            this.read = read;
            this.generation = generation;
        }
        public long generation() { return this.generation; }
        @Override public void close() {
            if (!this.closed) {
                this.closed = true;
                this.read.unlock();
            }
        }
    }
}
