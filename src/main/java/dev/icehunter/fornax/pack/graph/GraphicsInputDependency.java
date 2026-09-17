package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** Graphics-owned external shadow inputs need a current-frame producer edge before raw compute
 * submission. This state is independent of the previous-frame storage-reuse edge. */
final class GraphicsInputDependency {
    record Wait(long semaphore, long value) { }

    private long value;
    private boolean uncertainSubmission;

    static boolean requiredBy(List<String> inputs) {
        return inputs.stream().anyMatch(ref -> ShadowMapManager.isShadowMapRef(ref)
                || TerrainShadowResult.isRef(ref));
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
