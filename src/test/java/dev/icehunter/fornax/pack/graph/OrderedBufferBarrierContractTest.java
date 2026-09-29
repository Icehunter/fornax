package dev.icehunter.fornax.pack.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vulkan command recording needs a client device; these checks pin only the declared barriers. */
class OrderedBufferBarrierContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
    }

    @Test
    void graphicsDispatchOrdersPriorGraphicsReadsAndWritesBeforeTheKernel() throws Exception {
        String source = source();
        int begin = source.indexOf("private long runInGraphicsStream(");
        int end = source.indexOf("private long publishGraphicsInputs()", begin);
        String body = source.substring(begin, end);
        int barrier = body.indexOf("recordGraphicsToComputeBarrier(cmd, stack)");
        assertTrue(barrier >= 0 && barrier < body.indexOf("VK13.vkCmdDispatch"),
                "same-queue recording alone does not order fragment reads before a buffer overwrite");
        int helper = source.indexOf("private static void recordGraphicsToComputeBarrier(");
        assertTrue(helper >= 0, "the graphics dependency must be recorded, not merely named");
        String helperBody = source.substring(helper, source.indexOf("\n    }", helper));
        assertTrue(helperBody.contains("VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT"));
        assertTrue(helperBody.contains("VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"));
        assertTrue(helperBody.contains(".srcAccessMask(VK13.VK_ACCESS_MEMORY_WRITE_BIT)"));
        assertTrue(helperBody.contains("VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT"));
        assertTrue(helperBody.contains("VK13.vkCmdPipelineBarrier"));
    }

    @Test
    void computeReleaseMakesWritesVisibleToLaterReadsAndOverwrites() throws Exception {
        String source = source();
        int helper = source.indexOf("private static void recordComputeWriteReleaseBarrier(");
        String helperBody = source.substring(helper, source.indexOf("\n    }", helper));
        assertTrue(helperBody.contains(".dstAccessMask(dstAccessMask | VK13.VK_ACCESS_SHADER_WRITE_BIT)"),
                "the release must include later shader writes as well as reads for scratch WAW dependencies");
    }
}
