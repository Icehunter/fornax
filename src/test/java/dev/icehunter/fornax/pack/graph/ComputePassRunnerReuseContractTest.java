package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native call ordering needs a Vulkan device; pin the cache branch at that otherwise untestable seam. */
class ComputePassRunnerReuseContractTest {
    @Test void kernelReuseKeepsSubmissionDescriptorsAndBothQueueHandoffs() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int branch = source.indexOf("if (dispatchKernel) {");
        assertTrue(branch >= 0, "the kernel needs an explicit dispatch decision");
        int open = source.indexOf('{', branch), depth = 1, end = open + 1;
        while (depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        String kernel = source.substring(open, end);
        assertTrue(kernel.contains("vkCmdDispatch(cmd, groupsX, groupsY, groupsZ)"));
        assertTrue(kernel.contains("vkCmdResetQueryPool"));
        assertTrue(kernel.contains("VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"));
        assertTrue(source.indexOf("updateAndBindDescriptorSet(registry, cmd,") < branch);
        assertTrue(source.indexOf("recordComputeWriteReleaseBarrier(cmd, stack,", end) >= end);
        assertTrue(source.indexOf("VK13.vkQueueSubmit", end) >= end);
        assertTrue(source.indexOf("graphics.waitSemaphore(slot.graphicsSemaphore", end) >= end);
        assertTrue(source.indexOf("pendingGraphicsRelease = reuseTicket;", end) >= end);
        int submitted = source.indexOf("reuseState.submitted(dispatchKernel");
        assertTrue(submitted > source.indexOf("if (result != VK13.VK_SUCCESS)", end));
    }

    @Test void replacingATargetInvalidatesItsContentBeforeTheNextConsumer() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/TargetRegistry.java"));
        int replacement = source.indexOf("TargetInstance old = targets.put(name, next)");
        int invalidation = source.indexOf("invalidateComputeContent(name);", replacement);
        assertTrue(replacement >= 0 && invalidation > replacement);
        assertTrue(invalidation < source.indexOf("if (old != null)", replacement));
    }

    @Test void frameMirrorIsPublishedAtTheExistingUniformWritesAndResetBeforeTerrain() throws Exception {
        String writer = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/GlobalUniformsWriteMixin.java"));
        assertTrue(writer.contains("FrameUniformValues.CURRENT.skyState("));
        assertTrue(writer.contains("FrameUniformValues.CURRENT.frameState("));
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        int prepare = graph.indexOf("public static void prepare(");
        assertTrue(graph.indexOf("FrameUniformValues.CURRENT.beginFrame();", prepare) > prepare);
    }
}
