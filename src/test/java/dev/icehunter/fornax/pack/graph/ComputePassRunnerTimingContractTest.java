package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level contract for raw Vulkan timing. Constructing a ComputePassRunner needs a live
 * device, pipeline and descriptor graph, so ordering around vkCmdDispatch is pinned in source just
 * as other GPU contracts in this suite pin otherwise-unreachable native call order.
 */
class ComputePassRunnerTimingContractTest {
    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
    }

    @Test
    void queryResetAndStartPrecedeDispatchAndEndFollowsIt() throws IOException {
        String source = source();
        int reset = source.indexOf("vkCmdResetQueryPool(cmd, timestampQueries.pool(), firstQuery, 2)");
        int start = source.indexOf("vkCmdWriteTimestamp(cmd, VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT");
        int dispatch = source.indexOf("vkCmdDispatch(cmd, groupsX, groupsY, groupsZ)");
        int end = source.indexOf("vkCmdWriteTimestamp(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT");

        assertTrue(reset >= 0, "the slot's two queries must be reset in its command buffer");
        assertTrue(reset < start, "reset must precede the start timestamp");
        assertTrue(start < dispatch, "start timestamp must precede dispatch");
        assertTrue(dispatch < end, "end timestamp must follow dispatch");
    }

    @Test
    void timingSubmissionIsPublishedOnlyAfterQueueSubmitSucceeds() throws IOException {
        String source = source();
        int submit = source.indexOf("VK13.vkQueueSubmit");
        int success = source.indexOf("if (result != VK13.VK_SUCCESS)", submit);
        int mark = source.indexOf("computeTimer.markSubmitted(slotIndex)", submit);

        assertTrue(submit >= 0);
        assertTrue(submit < success);
        assertTrue(success < mark,
                "a failed submit must not make an unwritten query pair eligible for readback");
    }

    @Test
    void completedQueriesDrainBeforeTheCommandPoolIsReset() throws IOException {
        String source = source();
        int drain = source.indexOf("computeTimer.drainCompleted(slotIndex)");
        int reset = source.indexOf("pool.reset()", drain);

        assertTrue(drain >= 0);
        assertTrue(drain < reset,
                "the matching fence must complete and its query pair must drain before slot reuse");
    }

    @Test
    void runnerTeardownClosesItsComputeTimer() throws IOException {
        String source = source();
        int closeMethod = source.indexOf("public void close()");
        int timerClose = source.indexOf("computeTimer.close()", closeMethod);

        assertTrue(timerClose > closeMethod,
                "runner teardown must destroy the per-runner raw query pool through its timer");
    }

    @Test
    void dependencyWaitUsesLatestValueTelemetryInsteadOfGpuTimingSamples() throws IOException {
        String graphSource = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));

        assertTrue(graphSource.contains(
                        "frameProfiler.recordValue(\"compute wait \" + p.name()"),
                "host dependency waits must not enter the rolling GPU avg/p95 channel");
    }

    @Test
    void computeQueueFamilyTimestampSupportGatesQueryCommands() throws IOException {
        String source = source();
        int tryCreate = source.indexOf("RawTimestampQueries.tryCreate(backend, spec.name())");
        int guardedStart = source.indexOf("if (timestampQueries != null)", tryCreate);
        int validBitsLookup = source.indexOf("resolveTimestampValidBits(backend)");
        int unsupported = source.indexOf("if (validBits == 0)", validBitsLookup);
        int poolCreate = source.indexOf("vkCreateQueryPool", unsupported);

        assertTrue(tryCreate >= 0);
        assertTrue(tryCreate < guardedStart,
                "family capability resolution must decide whether command recording is enabled");
        assertTrue(validBitsLookup >= 0);
        assertTrue(validBitsLookup < unsupported);
        assertTrue(unsupported < poolCreate,
                "a queue family with zero valid timestamp bits must not allocate or write queries");
    }
}
