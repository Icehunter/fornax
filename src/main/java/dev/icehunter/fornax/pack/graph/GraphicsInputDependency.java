package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** Shadow maps and G-buffer attachments are inputs the graphics side writes. This pass's compute
 * work is sent straight to the GPU. Before that, it needs a writer edge for the current frame.
 * This state is independent of the previous-frame storage-reuse edge. */
final class GraphicsInputDependency {
    record Wait(long semaphore, long value) { }

    /** The refs GraphInputResolver.resolveBuiltinView serves from GBufferManager and the main
     * render target. The geometry passes write these on the graphics queue in the same frame. A
     * compute reader of any of them needs the same writer edge the shadow map already gets. */
    private static final Set<String> GBUFFER_REFS = Set.of("builtin.depth", "builtin.gNormal",
            "builtin.gAlbedo", "builtin.gMaterial", "builtin.gAo", "builtin.gMotion", "builtin.output");

    private long value;
    private boolean uncertainSubmission;

    static boolean requiredBy(List<String> inputs) {
        return inputs.stream().anyMatch(ref -> ShadowMapManager.isShadowMapRef(ref)
                || TerrainShadowResult.isRef(ref) || GBUFFER_REFS.contains(ref));
    }

    /** Signal and dispatch the producer before publishing a value for the compute wait. A failed
     * signal/flush may already have queued work: never recycle its value or use that encoder again. */
    long publish(LongConsumer signal, Runnable flush) {
        if (uncertainSubmission) {
            throw new IllegalStateException("graphics input producer submission is uncertain");
        }
        value = Math.incrementExact(value);
        uncertainSubmission = true;
        signal.accept(value);
        flush.run();
        uncertainSubmission = false;
        return value;
    }

    /** A compute submit can fail after a successful graphics flush, leaving an orphan signal that
     * no compute fence covers. If flush itself failed, waiting could hang on an unsubmitted value;
     * retain the semaphore until device destruction instead of guessing whether it is live. */
    boolean awaitBeforeDestroy(boolean computeCompleted, LongPredicate awaitCompletion) {
        return computeCompleted && !uncertainSubmission
                && (value == 0 || awaitCompletion.test(value));
    }

    /** Keep both independent timeline waits. A zero value means that edge has no producer yet. */
    static List<Wait> waits(long graphicsSemaphore, long graphicsValue,
                            long reuseSemaphore, long reuseValue) {
        List<Wait> waits = new ArrayList<>(2);
        if (graphicsValue != 0) waits.add(new Wait(graphicsSemaphore, graphicsValue));
        if (reuseValue != 0) waits.add(new Wait(reuseSemaphore, reuseValue));
        return waits;
    }
}
