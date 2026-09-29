package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native queue submission needs a live Vulkan device. These source contracts pin the runner's
 * use of the independently tested dependency state; they do not establish driver execution. */
class ComputePassRunnerGraphicsInputTest {
    @Test
    void graphicsProducerIsSubmittedBeforeComputeAndItsOutgoingGraphicsWait() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int publish = source.indexOf("long graphicsInputValue = publishGraphicsInputs();");
        int reuse = source.indexOf("imageReuseSequence.beginWrite()", publish);
        int submit = source.indexOf("result = VK13.vkQueueSubmit(", publish);
        int outgoing = source.indexOf("graphics.waitSemaphore(slot.graphicsSemaphore", submit);
        assertTrue(publish >= 0, "current graphics shadow writes need a submitted producer signal");
        assertTrue(reuse > publish && submit > reuse && outgoing > submit,
                "flush producer before compute submit, and only then queue the compute-to-graphics wait");
    }

    @Test
    void producerBoundaryUsesDeclaredAliasesAndPartialFlushWithoutAHostWait() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        String graph = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        assertTrue(graph.contains("Set<String> graphicsComputePasses = graphicsStreamComputePasses(currentPack.graph(), compileValues)"));
        assertTrue(graph.contains("frameProfiler, graphicsComputePasses.contains(p.name()))"),
                "runner construction must receive membership in the same graph routing plan used by handoffs");
        assertTrue(graph.contains("GraphicsInputDependency.requiredBy(pass.inputs(), graphicsWritten)"),
                "the transitive graph plan retains builtin and declared graphics aliases as its seeds");
        assertTrue(source.contains("compiledSpirv, graphicsStream);"));
        assertTrue(source.contains("this.graphicsStream = graphicsStream;"),
                "the constructor must retain the passed routing decision");
        int start = source.indexOf("private long publishGraphicsInputs()");
        String publish = source.substring(start, source.indexOf("private boolean captureReuseInputs", start));
        assertTrue(publish.indexOf("if (graphicsInputDependency == null) return 0;")
                < publish.indexOf("createCommandEncoder()"), "unrelated inputs must leave graphics recording alone");
        assertTrue(publish.contains("graphicsInputDependency.publish("));
        assertTrue(publish.contains("graphics.signalSemaphore(graphicsInputTimelineSemaphore, value,"));
        assertTrue(publish.contains("VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT"));
        assertTrue(publish.contains("partialFlush::fornax$flushPending"));
        assertFalse(publish.contains(".submit()"));
        assertFalse(publish.contains("vkWait") || publish.contains(".waitSemaphore("));
    }

    @Test
    void nativeSubmitKeepsBothTimelineWaitsAlignedWithStagesAndItsBinarySignal() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int start = source.indexOf("List<GraphicsInputDependency.Wait> waits =");
        String submit = source.substring(start, source.indexOf("int result;", start));
        assertTrue(submit.contains("graphicsInputTimelineSemaphore, graphicsInputValue,"));
        assertTrue(submit.contains("imageReuseTimelineSemaphore, reuseTicket != null ? reuseTicket.waitValue() : 0"));
        assertTrue(submit.contains("waitSemaphores.put(i, waits.get(i).semaphore())"));
        assertTrue(submit.contains("waitValues.put(i, waits.get(i).value())"));
        assertTrue(submit.contains("waitStages.put(i, capture != null ? VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT"));
        assertTrue(submit.contains("VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"));
        assertTrue(submit.contains(".pWaitSemaphoreValues(waitValues)"));
        assertTrue(submit.contains(".pWaitSemaphores(waitSemaphores).pWaitDstStageMask(waitStages)"));
        assertTrue(submit.contains("timelineInfo.pSignalSemaphoreValues(stack.longs(0L))"));
        assertTrue(submit.contains("submitInfo.pSignalSemaphores(stack.longs(slot.graphicsSemaphore))"));
    }

    @Test
    void destructionWaitsTheGraphicsProducerSeparatelyFromSubmittedComputeFences() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int close = source.indexOf("public void close()");
        int ring = source.indexOf("destroyRingResources();", close);
        int producer = source.indexOf("destroyGraphicsInputTimeline(computeCompleted);", ring);
        assertTrue(ring > close && producer > ring);
        int start = source.indexOf("private void destroyGraphicsInputTimeline(boolean computeCompleted)");
        String teardown = source.substring(start, source.indexOf("private void destroyImageReuseTimeline()", start));
        assertTrue(teardown.contains("graphicsInputDependency.awaitBeforeDestroy(computeCompleted, value ->"));
        assertTrue(teardown.contains("VK13.vkWaitSemaphores("));
        int denied = teardown.indexOf("if (!completed)");
        int retain = teardown.indexOf("return;", denied);
        int destroy = teardown.indexOf("VK13.vkDestroySemaphore(");
        assertTrue(denied >= 0 && retain > denied && destroy > retain,
                "uncertain submission or completion must not destroy a live graphics signal");
    }

    /** Builtin graphics inputs seed the graph routing plan. Declared targets can also become
     * graphics-written through a producer or scratch use; the graph-level tests cover that closure. */
    @Test
    void graphicsOwnedGBufferInputsSelectGraphicsStreamMode() {
        assertTrue(GraphicsInputDependency.requiredBy(
                        List.of("globals", "packOptions", "builtin.depth", "builtin.gNormal")),
                "G-buffer refs in the input list must select graphics-stream mode");
        assertFalse(GraphicsInputDependency.requiredBy(
                        List.of("globals", "packOptions", "someTarget")),
                "an ordinary target is not a builtin seed; its producers determine the graph routing");
    }

}
