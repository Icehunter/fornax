package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Groups repeat mesh-triggered harvest requests into one, on its own worker rather than the
 * resync worker. */
final class VoxelMeshUpdates {
    @FunctionalInterface
    interface Reader {
        void read(long storageGeneration, long harvestGeneration, Level level, SectionPos section);
    }

    private static final class Pending {
        final long storageGeneration;
        final long harvestGeneration;
        final Level level;
        final SectionPos section;

        Pending(long storageGeneration, long harvestGeneration, Level level, SectionPos section) {
            this.storageGeneration = storageGeneration;
            this.harvestGeneration = harvestGeneration;
            this.level = level;
            this.section = section;
        }
    }

    private final Executor executor;
    private final Reader reader;
    private final BiConsumer<SectionPos, Throwable> failures;
    private final LinkedHashMap<SectionPos, Pending> pending = new LinkedHashMap<>();
    private long drainGeneration;
    private boolean drainScheduled;

    VoxelMeshUpdates(Executor executor, Reader reader, BiConsumer<SectionPos, Throwable> failures) {
        this.executor = executor;
        this.reader = reader;
        this.failures = failures;
    }

    void request(long storageGeneration, long harvestGeneration, Level level, SectionPos section) {
        long scheduledGeneration;
        synchronized (this) {
            Pending existing = pending.get(section);
            if (existing != null && existing.storageGeneration == storageGeneration
                    && existing.harvestGeneration == harvestGeneration) {
                pending.remove(section);
                pending.put(section, existing);
            } else {
                Pending next = new Pending(storageGeneration, harvestGeneration, level, section);
                pending.put(section, next);
            }
            scheduledGeneration = scheduleDrain();
        }
        if (scheduledGeneration >= 0) executor.execute(() -> drain(scheduledGeneration));
    }

    synchronized void reset() {
        pending.clear();
        drainGeneration++;
        drainScheduled = false;
    }

    private long scheduleDrain() {
        if (drainScheduled) return -1;
        drainScheduled = true;
        return drainGeneration;
    }

    private void drain(long generation) {
        long continuation = -1;
        try {
            while (true) {
                Pending item;
                synchronized (this) {
                    if (generation != drainGeneration || pending.isEmpty()) return;
                    Map.Entry<SectionPos, Pending> newest = pending.lastEntry();
                    pending.remove(newest.getKey());
                    item = newest.getValue();
                }
                try {
                    reader.read(item.storageGeneration, item.harvestGeneration, item.level, item.section);
                } catch (RuntimeException error) {
                    failures.accept(item.section, error);
                }
            }
        } finally {
            synchronized (this) {
                if (generation == drainGeneration && drainScheduled) {
                    drainScheduled = false;
                    if (!pending.isEmpty()) continuation = scheduleDrain();
                }
            }
            if (continuation >= 0) {
                long nextDrain = continuation;
                executor.execute(() -> drain(nextDrain));
            }
        }
    }
}
