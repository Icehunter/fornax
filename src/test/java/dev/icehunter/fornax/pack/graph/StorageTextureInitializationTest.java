package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.icehunter.fornax.util.GpuFatalException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU queue-order regression: exercises production admission and reuse, not Vulkan execution. */
class StorageTextureInitializationTest {
    @Test void initializationCompletionPreventsDelayedClearFromSurvivingUntilWeatherChanges() {
        Fixture unordered = new Fixture();
        unordered.allocate();
        unordered.dispatch(0);
        unordered.graphics.submit();
        unordered.graphics.drain();
        unordered.dispatch(0);
        assertEquals(0, unordered.image[0], "a delayed clear destroys the result while its key still matches");
        assertEquals(1, unordered.dispatches);
        unordered.dispatch(1);
        assertEquals(8, unordered.image[0], "changed weather causes the next kernel to restore the image");

        Fixture ordered = new Fixture();
        ordered.allocate();
        ordered.initialization.complete(() -> ordered.graphics);
        ordered.initialization.requireComplete("table");
        ordered.dispatch(0);
        ordered.graphics.drain();
        ordered.dispatch(0);
        assertEquals(7, ordered.image[0], "unchanged input must retain the computed result");
        assertEquals(1, ordered.dispatches);
        assertEquals(List.of("fence", "submit", "await", "close", "dispatch"), ordered.graphics.events);
    }

    @Test void newStorageAllocationsCoalesceAndUnchangedOrRasterOnlyFramesNeverSubmit() {
        StorageTextureInitialization initialization = new StorageTextureInitialization();
        initialization.complete(() -> { throw new AssertionError("empty initialization must not create an encoder"); });
        initialization.allocated(false);
        initialization.complete(() -> { throw new AssertionError("raster allocation must not create an encoder"); });
        initialization.requireComplete("raster");
        QueueEncoder graphics = new QueueEncoder();
        initialization.allocated(true);
        initialization.allocated(true);
        initialization.complete(() -> graphics);
        assertEquals(List.of("fence", "submit", "await", "close"), graphics.events);
        initialization.requireComplete("table");
        initialization.complete(() -> { throw new AssertionError("unchanged frame must not create an encoder"); });
        initialization.allocated(true);
        assertThrows(GpuFatalException.class, () -> initialization.requireComplete("resized table"));
        initialization.complete(() -> graphics);
        assertEquals(2, graphics.submitIndex, "one new batch follows a replacement allocation");
    }

    @Test void replacementIsInitializedBeforeAChangedResourceIdentityCanDispatch() {
        Fixture fixture = new Fixture();
        fixture.allocate();
        fixture.initialization.complete(() -> fixture.graphics);
        fixture.dispatch(0);
        fixture.allocate();
        assertThrows(GpuFatalException.class, () -> fixture.initialization.requireComplete("table"));
        fixture.initialization.complete(() -> fixture.graphics);
        fixture.dispatch(0);
        fixture.graphics.drain();
        assertEquals(7, fixture.image[0]);
        assertEquals(2, fixture.dispatches, "replacement must dispatch despite unchanged weather");
    }

    @Test void timeoutDoesNotAdmitComputeOrForgetPendingInitialization() {
        Fixture fixture = new Fixture();
        fixture.allocate();
        fixture.graphics.completes = false;
        assertThrows(GpuFatalException.class, () -> fixture.initialization.complete(() -> fixture.graphics));
        GpuFatalException failure = assertThrows(GpuFatalException.class,
                () -> fixture.initialization.requireComplete("first raw compute"));
        assertTrue(failure.getMessage().contains("first raw compute"));
        assertEquals(0, fixture.dispatches);
        fixture.graphics.completes = true;
        fixture.initialization.complete(() -> fixture.graphics);
        fixture.initialization.requireComplete("table");
        fixture.dispatch(0);
        assertEquals(7, fixture.image[0]);
    }

    @Test void exceptionsAtEveryCompletionStageKeepAdmissionClosed() {
        for (String stage : List.of("encoder", "fence", "submit", "await", "close")) {
            StorageTextureInitialization initialization = new StorageTextureInitialization();
            initialization.allocated(true);
            QueueEncoder graphics = new QueueEncoder();
            graphics.failure = stage;
            assertThrows(IllegalStateException.class, () -> initialization.complete(() -> {
                graphics.fail("encoder");
                return graphics;
            }), stage);
            assertThrows(GpuFatalException.class, () -> initialization.requireComplete(stage), stage);
        }
    }

    private static final class Fixture {
        final StorageTextureInitialization initialization = new StorageTextureInitialization();
        final QueueEncoder graphics = new QueueEncoder();
        final ComputeReuseState cache = new ComputeReuseState();
        int[] image;
        int dispatches;

        void allocate() {
            image = new int[]{-1}; // Distinguishes uninitialized allocation from its zero clear.
            int[] allocation = image;
            graphics.recorded.add(() -> allocation[0] = 0);
            initialization.allocated(true);
        }

        void dispatch(int weather) {
            int[] values = {weather};
            Object[] resources = {image};
            long[] revisions = {};
            boolean dispatch = cache.needsDispatch(values, resources, revisions);
            if (dispatch) {
                image[0] = 7 + weather; // A fixture kernel result, deliberately distinct from clear zero.
                dispatches++;
                graphics.events.add("dispatch");
            }
            cache.submitted(dispatch, values, resources, revisions);
        }
    }

    /** Separates recording, submission and completion; a fence snapshots the current batch. */
    private static final class QueueEncoder extends CommandEncoder {
        final List<Runnable> recorded = new ArrayList<>();
        final List<Runnable> submitted = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int submitIndex;
        boolean completes = true;
        String failure;

        QueueEncoder() { super(null, null, null); }
        void fail(String stage) {
            if (stage.equals(failure)) throw new IllegalStateException(stage);
        }
        @Override public void submit() {
            events.add("submit");
            fail("submit");
            submitted.addAll(recorded);
            recorded.clear();
            submitIndex++;
        }
        void drain() {
            submitted.forEach(Runnable::run);
            submitted.clear();
        }
        @Override public GpuFence createFence() {
            events.add("fence");
            fail("fence");
            int fenceIndex = submitIndex;
            return new GpuFence() {
                @Override public boolean awaitCompletion(long timeout) {
                    events.add("await");
                    fail("await");
                    assertTrue(timeout > 0, "completion must use a positive deadline");
                    if (fenceIndex >= submitIndex) throw new IllegalStateException("fence batch not submitted");
                    if (completes) drain();
                    return completes;
                }
                @Override public void close() { events.add("close"); fail("close"); }
            };
        }
    }
}
