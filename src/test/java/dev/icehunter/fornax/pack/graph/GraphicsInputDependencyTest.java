package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicsInputDependencyTest {
    @ParameterizedTest
    @ValueSource(strings = {"sunShadowMap", "sunShadowMapRaw", "sunEntityShadowMap",
            "sunEntityShadowMapRaw", "rtTerrainShadowDepth"})
    void everyGraphicsOwnedShadowAliasRequiresTheProducerBoundary(String ref) {
        assertTrue(GraphicsInputDependency.requiredBy(List.of("globals", ref, "packOptions")));
    }

    @Test
    void unrelatedInputsDoNotRequestAGraphicsFlush() {
        assertFalse(GraphicsInputDependency.requiredBy(List.of()));
        assertFalse(GraphicsInputDependency.requiredBy(List.of(
                "globals", "packOptions", "arbitraryLut", "builtin.gNormal", "sunShadowMap.history")));
    }

    @Test
    void producerSignalAndFlushFinishBeforeTheComputeWaitCanBePublished() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        List<String> events = new ArrayList<>();
        long first = dependency.publish(value -> events.add("signal " + value), () -> events.add("flush"));
        events.add("compute waits " + first);
        long second = dependency.publish(value -> events.add("signal " + value), () -> events.add("flush"));
        events.add("compute waits " + second);
        assertEquals(List.of("signal 1", "flush", "compute waits 1",
                "signal 2", "flush", "compute waits 2"), events);
    }

    @Test
    void neverPublishedDependencyCanBeDestroyedWithoutWaiting() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        assertTrue(dependency.awaitBeforeDestroy(true, value -> { throw new AssertionError("no producer"); }));
    }

    @Test
    void orphanSignalAfterFailedComputeSubmitStillRequiresCompletionAtTeardown() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        List<Long> waited = new ArrayList<>();
        dependency.publish(value -> { }, () -> { });
        dependency.publish(value -> { }, () -> { });
        // No compute submission or fence is needed to create this graphics-owned lifetime.
        assertTrue(dependency.awaitBeforeDestroy(true, value -> { waited.add(value); return true; }));
        assertEquals(List.of(2L), waited);
    }

    @Test
    void failedCompletionWaitDoesNotAuthorizeDestruction() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        dependency.publish(value -> { }, () -> { });
        assertFalse(dependency.awaitBeforeDestroy(true, value -> false));
    }

    @Test
    void failedComputeCompletionCannotDestroyASemaphoreStillReferencedByItsWait() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        dependency.publish(value -> { }, () -> { });
        assertFalse(dependency.awaitBeforeDestroy(false, value -> {
            throw new AssertionError("producer completion cannot prove consumer completion");
        }));
    }

    @Test
    void uncertainFlushCannotWaitAnUnsubmittedValueOrDestroyItsSemaphore() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        dependency.publish(value -> { }, () -> { });
        assertThrows(IllegalStateException.class, () -> dependency.publish(value -> { }, () -> {
            throw new IllegalStateException("submission may have been accepted");
        }));
        assertFalse(dependency.awaitBeforeDestroy(true, value -> { throw new AssertionError("could hang"); }));
        assertThrows(IllegalStateException.class,
                () -> dependency.publish(value -> { throw new AssertionError("poisoned"); }, () -> { }));
    }

    @Test
    void failedSignalIsAlsoConservativelyRetained() {
        GraphicsInputDependency dependency = new GraphicsInputDependency();
        assertThrows(IllegalStateException.class, () -> dependency.publish(value -> {
            throw new IllegalStateException("encoder may already reference semaphore");
        }, () -> { throw new AssertionError("must not flush after failed signal"); }));
        assertFalse(dependency.awaitBeforeDestroy(true, value -> { throw new AssertionError("could hang"); }));
    }

    @Test
    void submissionRetainsBothProducerAndPreviousFrameReuseWaits() {
        // Distinct fake handles/values expose accidental replacement or misaligned arrays.
        assertEquals(List.of(new GraphicsInputDependency.Wait(17, 3),
                        new GraphicsInputDependency.Wait(29, 8)),
                GraphicsInputDependency.waits(17, 3, 29, 8));
        assertEquals(List.of(new GraphicsInputDependency.Wait(17, 3)),
                GraphicsInputDependency.waits(17, 3, 29, 0));
        assertEquals(List.of(new GraphicsInputDependency.Wait(29, 8)),
                GraphicsInputDependency.waits(17, 0, 29, 8));
        assertTrue(GraphicsInputDependency.waits(17, 0, 29, 0).isEmpty());
    }
}
