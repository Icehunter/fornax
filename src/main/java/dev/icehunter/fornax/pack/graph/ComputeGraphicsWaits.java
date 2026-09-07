package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.Set;
import org.lwjgl.vulkan.VK13;

/**
 * Puts off later compute handoffs across graph passes that do not touch the same data. The first
 * handoff keeps its original place: moving it could change how the shadow and opaque work reach a
 * later raw compute dispatch. Before any other compute pass runs, every pending handoff is drained,
 * for the same reason. Passes that run before opaque work do not use this per-frame object.
 *
 * <p>Each signal sent must be waited on once, even if every pass that could read it skips or fails.
 * Closing this object before history swaps and geometry outside the graph keeps both the semaphore
 * and its resources tied to one finish() call. The timeline that runs the other way, graphics to
 * compute, is unchanged.
 */
final class ComputeGraphicsWaits implements AutoCloseable {
    @FunctionalInterface
    interface Recorder {
        void waitFor(long semaphore, long stages);
    }

    private record Pending(PassSpec producer, long semaphore) {}

    private final Recorder recorder;
    private final BiPredicate<PassSpec, PassSpec> conflictPlan;
    private final List<Pending> pending = new ArrayList<>();
    private boolean firstHandoffRecorded;

    ComputeGraphicsWaits(BiPredicate<PassSpec, PassSpec> conflictPlan, Recorder recorder) {
        this.conflictPlan = conflictPlan;
        this.recorder = recorder;
    }

    /** Build once per graph rebuild. Per-frame checks build no new resource sets. */
    static BiPredicate<PassSpec, PassSpec> compile(List<PassSpec> passes) {
        Map<PassSpec, Set<PassSpec>> conflicts = new HashMap<>();
        Set<PassSpec> known = Set.copyOf(passes);
        for (PassSpec producer : passes) {
            if (producer.type() != PassType.COMPUTE) continue;
            Set<PassSpec> consumers = new HashSet<>();
            for (PassSpec next : passes) {
                if (conflicts(producer, next)) consumers.add(next);
            }
            conflicts.put(producer, Set.copyOf(consumers));
        }
        Map<PassSpec, Set<PassSpec>> plan = Map.copyOf(conflicts);
        return (producer, next) -> {
            Set<PassSpec> consumers = plan.get(producer);
            // A pass with no entry in the plan is never assumed independent during a rebuild.
            return consumers == null || !known.contains(next) || consumers.contains(next);
        };
    }

    /** Called only after a successful native submit with a nonzero binary signal stage mask. */
    void submitted(PassSpec producer, long semaphore, long stages) {
        if (!firstHandoffRecorded) {
            recorder.waitFor(semaphore, stages);
            firstHandoffRecorded = true;
        } else {
            pending.add(new Pending(producer, semaphore));
        }
    }

    /** Called before the runner, including its debug transfers and attachment load/store. */
    void beforePass(PassSpec pass) {
        for (Pending handoff : pending) {
            if (conflictPlan.test(handoff.producer(), pass)) {
                close();
                return;
            }
        }
    }

    static boolean conflicts(PassSpec producer, PassSpec next) {
        // These runners hold hidden geometry or temporal state, or submit raw compute work of their
        // own. Keep their old boundary rather than trust their sampler list to name every access.
        if (next.type() != PassType.FULLSCREEN && next.type() != PassType.MIPCHAIN
                && next.type() != PassType.COPY && next.type() != PassType.CONSOLIDATE) {
            return true;
        }
        Set<String> reads = resources(next.inputs());
        Set<String> writes = resources(next.outputs());
        if (next.target() != null) writes.add(resource(next.target()));
        // An engine output can change its real allocation with presentation state. Targets in the
        // registry, mip chains and arrays keep a stable name within this scope; an engine-owned
        // sink does not.
        if (writes.stream().anyMatch(name -> name.startsWith("builtin."))) return true;
        Set<String> computeReads = resources(producer.inputs());
        Set<String> computeWrites = resources(producer.outputs());
        return intersects(computeWrites, reads) || intersects(computeWrites, writes)
                || intersects(computeReads, writes);
    }

    private static Set<String> resources(List<String> names) {
        Set<String> result = new HashSet<>();
        for (String name : names) result.add(resource(name));
        return result;
    }

    private static String resource(String name) {
        String base = name.endsWith(".history")
                ? name.substring(0, name.length() - ".history".length()) : name;
        // GraphInputResolver points both sampler names at the same shadow texture.
        return base.equals(ShadowMapManager.RAW_TARGET) ? ShadowMapManager.TARGET : base;
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String name : left) if (right.contains(name)) return true;
        return false;
    }

    @Override
    public void close() {
        while (!pending.isEmpty()) {
            Pending handoff = pending.getFirst();
            // A Vulkan semaphore wait covers only the named stages. ALL_COMMANDS also guards a
            // fullscreen capture's transfer before its draw, and its WAW/WAR attachment access, not
            // only its fragment shader. Passes with no conflict were recorded before this boundary.
            recorder.waitFor(handoff.semaphore(), VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            pending.removeFirst();
        }
    }
}
