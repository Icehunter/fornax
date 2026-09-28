package dev.icehunter.fornax.voxel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native queue operations need a client device. These source checks pin both handoff directions
 * and publication sites; VoxelUploadQueueTest and VoxelFrameOrderTest exercise their pure state. */
class VoxelFrameUploadContractTest {
    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("fornax.contract.sourceRoot", "."));
        String path = "src/main/java/dev/icehunter/fornax/" + relative + ".java";
        Path source = root.resolve(path);
        String fallback = System.getProperty("fornax.contract.fallbackRoot");
        if (!Files.exists(source) && fallback != null) source = Path.of(fallback).resolve(path);
        return Files.readString(source);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Missing method " + signature);
        int open = source.indexOf('{', start), end = open + 1, depth = 1;
        while (depth > 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return source.substring(open, end);
    }

    @Test void workersPublishImmutableIndependentSlotRecordsWithoutSubmittingToTheGpu() throws Exception {
        String body = method(source("voxel/BrickGridUpload"), "public static void uploadSlots(");
        assertTrue(body.contains("queue(registry).publish("));
        assertTrue(body.contains("source.paletteIndices().clone()"));
        assertTrue(body.contains("source.lightmap().clone()"));
        assertFalse(body.contains("resources.execute("));
        assertFalse(body.contains("uploadBatchLocked("));
        assertFalse(body.contains("vkQueueSubmit"));
    }

    @Test void workersAndRecenteringQueueEveryClearAndMetadataMutation() throws Exception {
        String upload = source("voxel/BrickGridUpload");
        assertTrue(method(upload, "public static void clearLightSlot(").contains(".clearLight("));
        assertTrue(method(upload, "public static void clearOccupancySlots(").contains("queue.clear("));
        assertTrue(method(upload, "public static void invalidateSectionStates(TargetRegistry registry, long atlasGeneration)")
                .contains(".invalidateMetadata("));
        assertFalse(upload.contains("VK13.vkQueueSubmit("));
        assertFalse(upload.contains("VK13.vkWaitForFences("));
    }

    @Test void scratchResourcesCanOnlyRecordIntoTheFrameOwnedTransfer() throws Exception {
        for (String name : new String[] {"VoxelUploadResources", "VoxelClearResources"}) {
            String scratch = source("voxel/" + name);
            assertTrue(scratch.contains("VoxelUploadFrame.record(record)"));
            assertFalse(scratch.contains("VK13.vkQueueSubmit("));
            assertFalse(scratch.contains("VK13.vkWaitForFences("));
        }
    }

    @Test void priorGraphicsCompletionIsFlushedBeforeTransferWaitsAndOutsideTheSharedLock() throws Exception {
        String begin = method(source("voxel/VoxelUploadFrame"), "private void begin()");
        int flush = begin.indexOf("fornax$flushPending()");
        int hostWait = begin.indexOf("awaitPending()");
        int frame = begin.indexOf("order.begin()");
        int lock = begin.indexOf("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)");
        assertTrue(flush >= 0 && hostWait > flush && frame > hostWait && lock > frame);
        assertTrue(begin.contains(".pWaitSemaphores(stack.longs(graphicsComplete))"));
        assertTrue(begin.contains(".pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_TRANSFER_BIT))"));
    }

    @Test void submittedUploadsAreAcquiredByAllGraphicsReadersBeforeCpuPublication() throws Exception {
        String begin = method(source("voxel/VoxelUploadFrame"), "private void begin()");
        int submit = begin.indexOf("VK13.vkQueueSubmit(");
        int acquire = begin.indexOf("graphics.waitSemaphore(uploadReady, signalValue, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)");
        int commit = begin.indexOf("callback.run()");
        assertTrue(submit >= 0 && acquire > submit && commit > acquire);
        assertTrue(begin.contains("VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT"));
        String batch = method(source("voxel/BrickGridUpload"), "private static void uploadBatchLocked(");
        int callback = batch.indexOf("VoxelUploadFrame.afterSubmit(() ->");
        assertTrue(callback >= 0 && batch.indexOf("VoxelWindow.onSectionUploadCommitted(item.token(), item.snapshot())") > callback);
    }

    @Test void missingRequiredResourcesCannotAcknowledgePendingData() throws Exception {
        String drain = method(source("voxel/BrickGridUpload"), "static void drainQueuedUploads(");
        int preflight = drain.indexOf("registry.getBuffer(PAYLOAD_TARGET) == null");
        int retained = drain.indexOf("return;", preflight);
        int mutation = drain.indexOf("invalidateSectionStatesNow(");
        int ack = drain.indexOf("VoxelUploadFrame.afterSubmit(() -> queue.acknowledge(snapshot))");
        assertTrue(preflight >= 0 && retained > preflight && mutation > retained && ack > mutation);
    }

    @Test void overlappingMetadataClearsAndPayloadWritesHaveATransferDependency() throws Exception {
        String record = method(source("voxel/VoxelUploadFrame"), "static boolean record(");
        assertTrue(record.contains(".srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)"));
        assertTrue(record.contains(".dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)"));
        int barrier = record.indexOf("VK13.vkCmdPipelineBarrier(frame.command, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,");
        assertTrue(barrier >= 0 && record.indexOf("commands.accept(frame.command)") > barrier);
        assertTrue(record.substring(barrier).contains("VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier"));
    }

    @Test void preOpaqueDrainPrecedesEveryGraphReaderAndLateRecenteringIsNextFramesWork() throws Exception {
        String graph = source("pack/graph/GraphRunner");
        int drain = graph.indexOf("VoxelUploadFrame.prepare(registry)");
        int prepare = graph.indexOf("VoxelWindow.prepareEmitterPool(registry)");
        int compute = graph.indexOf("runPreOpaqueLightingCompute(matrices, x, y, z, width, height)");
        assertTrue(drain >= 0 && prepare > drain && compute > prepare);
        int recenter = graph.indexOf("VoxelDebugRaymarchPass.onFrame(");
        assertTrue(recenter > graph.indexOf("r.swapHistory()"));
        assertTrue(source("mixin/sodium/GlobalUniformsWriteMixin").contains("hasUpdatedThisFrame"));
        String debug = source("pass/voxel/VoxelDebugRaymarchPass");
        assertTrue(debug.contains("capturedCenterX = frameWindow.centerX()"));
        assertTrue(debug.contains("push.putInt(80, currentDiameter)"));
    }

    @Test void pendingEncoderReferencesAreFlushedBeforeTimelinesAreDestroyed() throws Exception {
        String close = method(source("pack/graph/GraphRunner"), "private static void closeCurrent()");
        int flush = close.indexOf("VoxelUploadFrame.flushBeforeDestroy()");
        int idle = close.indexOf("VulkanComputeBackend.waitForGpuIdleBeforeDestroy()");
        int destroy = close.indexOf("VoxelUploadFrame.closeCurrent()");
        assertTrue(flush >= 0 && idle > flush && destroy > idle);
        String frame = source("voxel/VoxelUploadFrame");
        assertTrue(method(frame, "public static void flushBeforeDestroy()").contains("throw fatal("));
        assertTrue(method(frame, "public static void prepare(").contains("throw fatal("));
    }

    @Test void generationResetDiscardsQueuedPayloadAndReadersReleaseAfterForwardDrawing() throws Exception {
        String window = source("voxel/VoxelWindow");
        assertTrue(window.contains("BrickGridUpload.discardQueuedUploads()"));
        String graph = source("pack/graph/GraphRunner");
        assertTrue(method(graph, "public static void recordGraphicsStorageReadsComplete()").contains("VoxelUploadFrame.graphicsReadsComplete()"));
        String frame = method(source("voxel/VoxelUploadFrame"), "public static void graphicsReadsComplete()");
        assertTrue(frame.indexOf("signalSemaphore(") < frame.indexOf("current.order.published(value)"));
    }
}
