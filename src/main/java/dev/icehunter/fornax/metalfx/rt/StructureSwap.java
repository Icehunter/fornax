package dev.icehunter.fornax.metalfx.rt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks which structure is live and when the next one takes over. It has no Metal code, so
 * its rule can be a plain unit test. A structure is only one number, its handle; this class
 * never opens it, builds it, or frees it. The caller owns everything behind a handle and
 * decides what freeing it means.
 *
 * <p>Whether a build is done is a fact the caller supplies, not something this class checks.
 * The caller's own Vulkan encoder signals this same timeline every frame, from a different
 * queue. Nothing keeps that signal behind a build's own completion. A later frame's signal
 * can pass a build that is still running. The caller checks a fact about the build, such as
 * its command buffer's status, and passes it in as {@code ready}.
 *
 * <p>A handle offered as {@code next} becomes {@code live} once the caller says it is ready.
 * Only one {@code next} exists at a time. A second {@link #offer} before that point throws
 * away the first, which never went live. {@link #retire} and {@link #drainRetired} are
 * separate. They free an old structure's buffers once enough of the caller's timeline has
 * passed. This has nothing to do with how promotion is decided.
 */
final class StructureSwap {
    private record Retiring(List<Long> handles, long afterValue) {}

    private long live;
    private boolean pending;
    private long next;
    private final Deque<Retiring> retiring = new ArrayDeque<>();
    /** The highest value ever passed to {@link #drainRetired}. An MTLSharedEvent can report a
     * lower value later, if two command buffers signal different values and finish out of order.
     * That must not stall an entry a higher, earlier reading already cleared. */
    private long highestSignalled;

    /** The structure this tier should trace against, or 0 before any promotion. */
    long live() {
        return live;
    }

    /**
     * Records a structure under construction. Returns the structure a prior {@link #offer} left
     * pending, if that one was never promoted. The caller must retire it; this class never knows
     * what backs it. Returns 0 if nothing was pending. {@code structure} can genuinely be 0, an
     * empty scene, so a separate {@code pending} flag tracks whether one is set.
     */
    long offer(long structure) {
        long discarded = pending ? next : 0;
        next = structure;
        pending = true;
        return discarded;
    }

    /**
     * Promotes the pending structure to live when {@code ready} is true. Returns the structure
     * that was live before this. The caller retires it once its own last trace against it is
     * done. Returns 0 if nothing was promoted, because nothing was pending or it was not ready yet.
     */
    long promoteIfReady(boolean ready) {
        if (!pending || !ready) return 0;
        long old = live;
        live = next;
        pending = false;
        next = 0;
        return old;
    }

    /** Queues handles to free once {@code signalledValue} reaches {@code afterValue}. */
    void retire(List<Long> handles, long afterValue) {
        if (!handles.isEmpty()) retiring.addLast(new Retiring(List.copyOf(handles), afterValue));
    }

    /** Every handle whose retirement value is reached, in the order they were retired. A later
     * entry with an unreached value stops the drain. Entries are queued in non-decreasing order,
     * so nothing after the first unreached entry can be reached either. A lower {@code
     * signalledValue} reading does not break this: the comparison uses the highest value ever
     * seen. So a late, lower reading never stalls an entry that was already reachable. A drained
     * entry also leaves the queue, so nothing ever drains twice. */
    List<Long> drainRetired(long signalledValue) {
        highestSignalled = Math.max(highestSignalled, signalledValue);
        List<Long> out = new ArrayList<>();
        while (!retiring.isEmpty() && retiring.peekFirst().afterValue() <= highestSignalled) {
            out.addAll(retiring.removeFirst().handles());
        }
        return out;
    }
}
