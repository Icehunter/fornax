package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Gathers light-only section changes. Each changed section keeps a mark, so a mesh published later
 * with older light cannot replace the newer world light. Window-bounded. */
final class VoxelLightUpdates {
    private final Set<SectionPos> changed = new HashSet<>();
    private final Set<SectionPos> dirty = new HashSet<>();
    private Object queuedWorker;
    synchronized Object beginWork() {
        if (queuedWorker != null || dirty.isEmpty()) return null;
        return queuedWorker = new Object();
    }
    synchronized void endWork(Object worker) { if (queuedWorker == worker) queuedWorker = null; }
    synchronized void changed(SectionPos section) { changed.add(section); dirty.add(section); }
    synchronized void meshPublished(SectionPos section) { if (changed.contains(section)) dirty.add(section); }
    synchronized Set<SectionPos> drain(Predicate<SectionPos> inWindow) {
        changed.removeIf(p -> !inWindow.test(p));
        dirty.retainAll(changed);
        Set<SectionPos> result = Set.copyOf(dirty);
        dirty.clear();
        return result;
    }
    synchronized void clear() { changed.clear(); dirty.clear(); queuedWorker = null; }
}
