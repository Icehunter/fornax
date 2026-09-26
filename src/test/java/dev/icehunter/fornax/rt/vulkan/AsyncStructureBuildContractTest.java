package dev.icehunter.fornax.rt.vulkan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, where a device would be needed. Pins what keeps a structure build off the frame:
 * what the frame's stream carries, which queue builds, how a build is adopted, and that no step
 * host-waits. Each of these fails as frame time, not as an error.
 */
class AsyncStructureBuildContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/rt/vulkan");

    private static String read(String file) throws IOException {
        return Files.readString(SOURCE.resolve(file));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing: " + signature);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    void theFrameStreamCarriesCopiesAndTheTraceButNeverABuild() throws IOException {
        String tracer = read("MeshVulkanTracer.java");
        String schedule = method(tracer, "public int schedule(VkCommandBuffer cmd,");
        assertTrue(schedule.contains("VK13.vkCmdCopyBuffer(cmd, source.buffer().vkBuffer(), packed.buffer(), region);"),
                "the arena is the graphics queue's, so the copy out of it is the frame's");
        assertFalse(schedule.contains("recordBlasBuild(") || schedule.contains("recordTlasBuild(")
                || schedule.contains("vkCmdDispatch("), "no decode or build in the frame's stream");
        assertTrue(schedule.contains("if (pending != null) {\n            return -1;"),
                "one build in flight at a time; a later change waits for the next diff");
        assertTrue(schedule.contains("copySignalDue = copyValue;"), "the copy value is left for the caller to signal");
    }

    @Test
    void aMeshMissingFromOneSnapshotIsKeptForAGracePeriodRatherThanRebuiltAround() throws IOException {
        // A section the renderer is re-meshing is absent from a snapshot for a frame or two.
        // Dropped at once, the structure built without it stays live for the whole build round
        // trip and every lamp ray behind it leaks. Kept, it returns at the same revision and
        // costs nothing.
        String tracer = read("MeshVulkanTracer.java");
        assertTrue(tracer.contains("static final long ABSENCE_GRACE_NANOS = 2_000_000_000L;"));
        String schedule = method(tracer, "public int schedule(VkCommandBuffer cmd,");
        assertTrue(schedule.indexOf("for (CasterSource source : sources) lastSeenNanos.put(source.key(), now);")
                < schedule.indexOf("if (pending != null) {"), "presence is recorded even on a frame that cannot schedule");
        assertTrue(schedule.contains("if (seen != null && now - seen <= ABSENCE_GRACE_NANOS) {\n                next.put(entry.getKey(), entry.getValue());"),
                "an absent mesh inside the grace is carried into the next structure unchanged");
        assertTrue(schedule.indexOf("next.put(entry.getKey(), entry.getValue());")
                < schedule.indexOf("boolean setChanged = !next.keySet().equals(current.keySet());"),
                "retention happens before the set comparison, so a transient absence rebuilds nothing");
    }

    @Test
    void oneBuildTakesAtMostItsTriangleBudgetAndASingleOversizedMeshStillBuilds() {
        // A single mesh over the budget still builds: a build of nothing never converges.
        assertEquals(1, MeshVulkanTracer.budgetPrefix(new int[] {2_000_000}, 512 * 1024));
        // 300k + 200k fits; the third would cross, so it waits.
        assertEquals(2, MeshVulkanTracer.budgetPrefix(new int[] {300_000, 200_000, 100_000}, 512 * 1024));
        assertEquals(3, MeshVulkanTracer.budgetPrefix(new int[] {100, 100, 100}, 512 * 1024));
        assertEquals(0, MeshVulkanTracer.budgetPrefix(new int[0], 512 * 1024));
        assertEquals(512L * 1024, MeshVulkanTracer.BUILD_BUDGET_TRIANGLES);
    }

    @Test
    void aDeferredMeshKeepsItsOldStructureOrStaysOutRatherThanEnteringUnbuilt() throws IOException {
        // Every 16 blocks of travel a slab of sections crosses the window's edge. Built all at
        // once, their allocation and build time land in one frame. The nearest go first; a
        // deferred mesh keeps the structure it had or stays out of the scene, since an entry
        // with no structure is an instance pointing at nothing.
        String tracer = read("MeshVulkanTracer.java");
        String schedule = method(tracer, "public int schedule(VkCommandBuffer cmd,");
        assertTrue(schedule.contains("dirty.sort(Comparator.comparingLong(source -> sectionDistanceSq(source.key(), cameraSectionX, cameraSectionY, cameraSectionZ)));"));
        assertTrue(schedule.contains("int taken = budgetPrefix(triangles, BUILD_BUDGET_TRIANGLES);"));
        assertTrue(schedule.contains("if (existing != null) next.put(key, existing); else next.remove(key);"));
        assertTrue(schedule.indexOf("dirty = new ArrayList<>(dirty.subList(0, taken));")
                < schedule.indexOf("boolean setChanged = !next.keySet().equals(current.keySet());"),
                "the set comparison sees the deferred meshes' real membership");
        String provider = read("MeshVulkanProvider.java");
        assertTrue(provider.contains("tracer.schedule(cmd, sources, ox, oy, oz, sx, sy, sz)")
                && provider.contains("tracer.schedule(cmd, sources, ox, oy, oz, sectionOf(x), sectionOf(y), sectionOf(z))"),
                "both schedule call sites order by the camera's own section");
    }

    @Test
    void buildsAreSubmittedOnTheComputeQueueUnderTheSharedLockWaitingForTheCopiesOnTheGpu() throws IOException {
        String tracer = read("MeshVulkanTracer.java");
        String build = method(tracer, "private Pending recordBuild(");
        assertTrue(build.contains("recordBlasBuild(build,") && build.contains("recordTlasBuild(build,"));
        assertFalse(build.contains("beginSubmit()"), "recording never submits");
        String submit = method(tracer, "private void submit(Pending build)");
        assertTrue(submit.contains("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)"),
                "the compute queue is shared with the graph's passes");
        assertTrue(submit.contains("computeQueue.beginSubmit()"));
        assertTrue(submit.contains("submission.waitSemaphore(timeline.semaphore(), build.copyValue, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)"),
                "the decode reads the copies, so the build waits for them at the compute stage");
        assertTrue(submit.contains("submission.signalSemaphore(timeline.semaphore(), build.doneValue, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)"));
        assertTrue(build.contains("if (batchStarts[i]) AccelerationStructures.barrierBuildToBuild(build);"),
                "builds in a batch share no scratch, so only a new batch waits for earlier builds");
        assertTrue(build.contains("scratchBase + scratchOffsets[i]"));
        assertTrue(tracer.contains("static final long SCRATCH_BUDGET_BYTES = 256L << 20;"));
        assertTrue(tracer.contains("new VulkanCommandPool(device, computeQueue)"),
                "the build's command buffers come from the tier's own compute-family pool, not the graph's");
    }

    @Test
    void aBuildWithCopiesIsSubmittedOnlyAfterTheFrameThatCarriesThemHasCompleted() throws IOException {
        // Submitted at once, the build's semaphore wait sits on the compute queue ahead of the
        // graph's compute passes, which the frame's graphics submission waits on, and that
        // submission is the one that signals the value: a cycle, and the frame never ends. A
        // fence on the copying frame breaks it: the signal has landed before the wait is queued.
        String tracer = read("MeshVulkanTracer.java");
        String schedule = method(tracer, "public int schedule(VkCommandBuffer cmd,");
        assertTrue(schedule.contains("if (copyValue == 0) {\n            // Nothing to wait for") && schedule.contains("submit(pending);"),
                "only a build with nothing to wait for is submitted from schedule");
        assertFalse(schedule.contains("copyValue > 0) {\n            submit("));
        String gate = method(tracer, "public void submitIfCopiesDone()");
        assertTrue(gate.contains("pending.copyFence.awaitCompletion(0L)"), "a poll, never a wait");
        String provider = read("MeshVulkanProvider.java");
        String signal = method(provider, "private void signalCopies(");
        assertTrue(signal.indexOf("encoder.signalSemaphore(") < signal.indexOf("tracer.copiesFenced(encoder.createFence());"),
                "the fence is created after the signal is placed, in the same submission");
        String adopt = method(provider, "private void adopt(");
        assertTrue(adopt.indexOf("tracer.submitIfCopiesDone();") < adopt.indexOf("tracer.adoptIfReady("));
    }

    @Test
    void adoptionPollsTheTimelineAndWaitsForItOnTheGpuBeforeTheStructureGoesLive() throws IOException {
        String tracer = read("MeshVulkanTracer.java");
        String adopt = method(tracer, "public boolean adoptIfReady(LongConsumer waitDone)");
        assertTrue(adopt.contains("timeline.signalledValue() < pending.doneValue"), "a poll, never a wait");
        assertTrue(adopt.indexOf("waitDone.accept(pending.doneValue);") < adopt.indexOf("live = pending.result;"),
                "the graphics submission waits for the build before anything traverses it");
        assertTrue(adopt.contains("pendingFrees.add(() -> free(old));"), "what the new structure dropped retires behind the frame fence");
        assertTrue(tracer.contains("public boolean hasStructure() {\n        return live != null;"),
                "a build in flight is not a structure until adopted");
    }

    @Test
    void nothingInTheTierHostWaitsOutsideTeardown() throws IOException {
        String tracer = read("MeshVulkanTracer.java");
        int close = tracer.indexOf("public void close()");
        String beforeClose = tracer.substring(0, close);
        assertFalse(beforeClose.contains("timeline.waitFor("), "the one timeline wait is teardown's");
        assertFalse(beforeClose.contains("waitIdle("));
        assertEquals(2, beforeClose.split("awaitCompletion\\(0L\\)", -1).length - 1,
                "retirement and the copy gate poll their fences with a zero timeout");
        String provider = read("MeshVulkanProvider.java");
        assertFalse(provider.contains("encoder.submit();") || provider.contains("fornax$flushPending"));
    }

    @Test
    void theProviderAdoptsBeforeRecordingSignalsAfterAndTracesTheLiveOrigin() throws IOException {
        String provider = read("MeshVulkanProvider.java");
        String trace = method(provider, "private void traceFrame(CelestialFill request)");
        int adopt = trace.indexOf("adopt(encoder);");
        int record = trace.indexOf("VulkanMetalInterop.recordIntoStream(encoder, cmd -> {");
        int signal = trace.indexOf("signalCopies(encoder);");
        assertTrue(adopt > 0 && adopt < record && record < signal,
                "waitSemaphore closes the command buffer, so it precedes the recording; the signal follows it");
        assertTrue(trace.contains("int lx = tracer.originX(), ly = tracer.originY(), lz = tracer.originZ();"),
                "the camera is rebased onto the structure being traversed, not the one being built");
        assertTrue(trace.contains("if (sources.isEmpty() || !tracer.hasStructure()) {"),
                "no live structure publishes a cleared image, which the pack reads as raster");
        String query = method(provider, "private void ensureStructureForQuery(VulkanDevice device)");
        assertTrue(query.indexOf("adopt(encoder);") < query.indexOf("recordIntoStream(")
                && query.indexOf("recordIntoStream(") < query.indexOf("signalCopies(encoder);"));
        String adoptMethod = method(provider, "private void adopt(");
        assertTrue(adoptMethod.contains("encoder.waitSemaphore(tracer.timelineSemaphore(), value,"));
        assertTrue(adoptMethod.contains("VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT"), "ray queries run at the compute stage");
    }

    @Test
    void everyRtBufferIsSharedAcrossTheTwoQueueFamiliesWhereTheyDiffer() throws IOException {
        String buffer = read("RtBuffer.java");
        assertTrue(buffer.contains("if (graphicsFamily != computeFamily) {"));
        assertTrue(buffer.contains(".sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)"));
        assertTrue(buffer.contains(".pQueueFamilyIndices(stack.ints(graphicsFamily, computeFamily));"));
    }
}
