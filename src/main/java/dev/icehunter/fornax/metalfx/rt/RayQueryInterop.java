package dev.icehunter.fornax.metalfx.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.pipeline.VulkanPartialFlush;
import dev.icehunter.fornax.rt.BufferQuery;
import dev.icehunter.fornax.rt.AtlasUvEncoding;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Carries one batch of pack-declared rays across to Metal and the answers back.
 *
 * <p>The pack's two buffers are Vulkan and the traversal is Metal, and neither can address the
 * other's memory, so each batch is a round trip: copy both buffers into exported ones, dispatch,
 * copy the hits back. The hit buffer goes ACROSS as well as back, which looks redundant and is not:
 * the kernel reads each record's tier to leave a higher tier's answer alone, so it has to see what
 * is already there.
 *
 * <p>Three timeline values, the same shape the mesh shadow path uses: Vulkan input, Metal output,
 * Vulkan copy-back. One extra submission on each side per ray-query pass, in a frame position where
 * raw compute passes already submit on their own queue every frame.
 */
public final class RayQueryInterop implements AutoCloseable {

    /** {@code MTLResourceUsageRead}. */
    private static final long READ_USAGE = 1;

    private static final long THREADS_PER_GROUP = 64;

    // MTLCommandBufferStatus (MTLCommandBuffer.h): the trace buffer is polled, never waited on, so
    // only "did it finish" and "did it finish cleanly" matter here.
    private static final long MTL_COMMAND_BUFFER_STATUS_COMPLETED = 4;
    private static final long MTL_COMMAND_BUFFER_STATUS_ERROR = 5;

    private MetalRtGeometry.ExportedBuffer requests;
    private MetalRtGeometry.ExportedBuffer hits;
    private VulkanMetalInterop.SharedTimeline timeline;
    private MetalRtShaders.CompiledKernel kernel;
    private long nextValue = 1;
    private long lastValue;

    // One trace command buffer at a time is tracked for GPU timing. Retained (see answer()) so it
    // survives after this method's own autorelease pool pops. Metal keeps a committed buffer
    // alive on its own until it completes, but that is a different, driver-internal reference and
    // not one this class may read the buffer through once its own has gone. Polled rather than
    // read via a completion handler: this bridge has no block-literal support, and polling a
    // buffer several frames later is exactly the "read it late, never stall" shape PassTimer's own
    // ring already uses.
    private long pendingMetalCommandBuffer;
    @Nullable
    private String pendingMetalPassName;

    /**
     * Answers {@code query} at {@code tier} against {@code structure}.
     *
     * @param atlas    the block atlas the cutout alpha test samples. Without it every record that
     *                 carries UVs reads alpha zero and the ray passes through geometry it should
     *                 have hit, which is a see-through world rather than an error.
     * @param resident every handle the structure refers to. An encoder cannot see through an
     *                 acceleration structure to the buffers beneath it, and a dispatch that misses
     *                 one reads unresident memory and returns misses for every ray.
     * @return false when the batch could not be answered at all, so the caller leaves the records
     *         for a lower tier rather than reporting a failure
     */
    boolean answer(BufferQuery query, int tier, long structure, List<Long> resident, long atlas,
            float cameraInStructureX, float cameraInStructureY, float cameraInStructureZ) {
        drainPendingMetalTiming();
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null || structure == 0 || query.rayCount() <= 0) {
            return false;
        }
        long queue = VulkanMetalInterop.metalCommandQueue();
        if (queue == 0) {
            return false;
        }
        // Atlas size becomes known at runtime. Reject an unrepresentable exact address before any
        // copies/dispatch; truncating its high bits would silently shade a different texel.
        if (query.atlasUvEncoding() == AtlasUvEncoding.TEXEL_U16) {
            if (atlas == 0) {
                throw new IllegalArgumentException("ray query '" + query.passName()
                        + "' texel_u16 needs a bound atlas");
            }
            query.atlasUvEncoding().validateAtlasDimensions(
                    Objc.msgSendLong(atlas, Objc.selector("width")),
                    Objc.msgSendLong(atlas, Objc.selector("height")));
        }
        long requestBytes = RayQueryAbi.requestByteSize(query.rayCount());
        long hitBytes = RayQueryAbi.hitByteSize(query.rayCount());
        ensureBuffers(device, requestBytes, hitBytes);
        if (timeline == null) {
            timeline = VulkanMetalInterop.createSharedTimeline(device);
        }
        if (kernel == null) {
            kernel = MetalRtShaders.compileKernel(Objc.msgSendId(queue, Objc.selector("device")),
                    MetalRtShaders.RAY_QUERY_RESOURCE, MetalRtShaders.RAY_QUERY_FUNCTION);
        }

        long value = nextValue;
        nextValue += 3;

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Orders the read behind clearHits's same-queue fill of the hit buffer.
                // vkCmdPipelineBarrier only orders work within one queue. It does not cover the compute
                // pass's write to the request buffer on another queue. That cross-queue edge is the
                // semaphore GraphRunner.computeGraphicsWaitStages signals at TRANSFER stage.
                barrier(cmd, stack, VK13.VK_ACCESS_MEMORY_WRITE_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
                copy(cmd, stack, query.requestBuffer(), requests.vkBuffer(), requestBytes);
                copy(cmd, stack, query.hitBuffer(), hits.vkBuffer(), hitBytes);
            }
        });
        encoder.signalSemaphore(timeline.vkSemaphore, value, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        // The handoff is the timeline value reaching Metal. A full submit also waits for resource
        // retirement, which this path does not need: the trace waits on the value, not a fence.
        ((VulkanPartialFlush) encoder).fornax$flushPending();

        long pool = Objc.autoreleasePoolPush();
        try {
            long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
            if (cb == 0) {
                throw new IllegalStateException("Metal command buffer nil (ray query)");
            }
            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"), timeline.mtlSharedEvent, value);
            long computeEncoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
            if (computeEncoder == 0) {
                throw new IllegalStateException("Metal compute encoder nil (ray query)");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment constants = arena.allocate(32L);
                constants.set(ValueLayout.JAVA_INT, 0L, RayQueryAbi.ABI_VERSION);
                constants.set(ValueLayout.JAVA_INT, 4L, query.rayCount());
                constants.set(ValueLayout.JAVA_INT, 8L, tier);
                // Fill mode: this batch may already carry a higher tier's answers.
                constants.set(ValueLayout.JAVA_INT, 12L, 1);
                constants.set(ValueLayout.JAVA_FLOAT, 16L, cameraInStructureX);
                constants.set(ValueLayout.JAVA_FLOAT, 20L, cameraInStructureY);
                constants.set(ValueLayout.JAVA_FLOAT, 24L, cameraInStructureZ);
                constants.set(ValueLayout.JAVA_INT, 28L, query.atlasUvEncoding().wireValue());
                Objc.msgSendVoid(computeEncoder, Objc.selector("setComputePipelineState:"), kernel.pipeline());
                Objc.msgSendVoidIdLong(computeEncoder,
                        Objc.selector("setAccelerationStructure:atBufferIndex:"), structure, 1L);
                Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBytes:length:atIndex:"),
                        constants.address(), 32L, 0L);
                Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"),
                        requests.mtlBuffer(), 0L, 2L);
                Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"),
                        hits.mtlBuffer(), 0L, 3L);
                Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), atlas, 0L);
                for (long handle : resident) {
                    Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"), handle, READ_USAGE);
                }
                long threads = Math.min(THREADS_PER_GROUP,
                        Objc.msgSendLong(kernel.pipeline(), Objc.selector("maxTotalThreadsPerThreadgroup")));
                long groups = (query.rayCount() + threads - 1) / threads;
                Objc.dispatchThreadgroups(computeEncoder, groups, 1L, 1L, threads, 1L, 1L);
            } finally {
                // Metal aborts the process if an encoder is released without endEncoding, so this
                // runs even when the binding calls above threw.
                Objc.msgSendVoid(computeEncoder, Objc.selector("endEncoding"));
            }
            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeSignalEvent:value:"), timeline.mtlSharedEvent, value + 1);
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            // Metal GPU timing is off: retaining and polling this buffer crashes the render
            // thread with an unrecognized selector at launch. Nothing is retained, so
            // drainPendingMetalTiming() below always finds nothing to read.
        } finally {
            Objc.autoreleasePoolPop(pool);
        }

        encoder = device.createCommandEncoder();
        encoder.waitSemaphore(timeline.vkSemaphore, value + 1, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                copy(cmd, stack, hits.vkBuffer(), query.hitBuffer(), hitBytes);
                // The pack reads these hits from a later pass, possibly on the other queue.
                barrier(cmd, stack, VK13.VK_ACCESS_TRANSFER_WRITE_BIT, VK13.VK_ACCESS_MEMORY_READ_BIT);
            }
        });
        encoder.signalSemaphore(timeline.vkSemaphore, value + 2, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        // A full submit, unlike the two above. The pack reads these hits from a compute pass, and
        // the compute queue is its own family here, so keeping the graphics queue in order is not
        // enough to order the read behind this write. A partial flush leaves the reader seeing an
        // untouched buffer, which reads back as no tier having answered.
        encoder.submit();
        lastValue = value + 2;
        return true;
    }

    /**
     * Zeroes a pack-declared hit buffer, which every ray-query pass needs before any tier runs.
     *
     * <p>A declared target persists between frames, so without this the buffer still carries last
     * frame's tier words, every tier's fill skips every record, and the pack reads a frozen answer
     * that is indistinguishable from a fresh one. The cost is one fill of {@code rays * 32} bytes.
     */
    public static void clearHits(long hitBuffer, int rayCount) {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null || hitBuffer == 0 || rayCount <= 0) {
            return;
        }
        long bytes = RayQueryAbi.hitByteSize(rayCount);
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // The pass that last read these hits may still be in flight on another queue.
                barrier(cmd, stack, VK13.VK_ACCESS_MEMORY_READ_BIT, VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
                VK13.vkCmdFillBuffer(cmd, hitBuffer, 0L, bytes, 0);
                barrier(cmd, stack, VK13.VK_ACCESS_TRANSFER_WRITE_BIT, VK13.VK_ACCESS_MEMORY_READ_BIT);
            }
        });
        // The clear's own reader is the trace, later on the same queue.
        ((VulkanPartialFlush) encoder).fornax$flushPending();
    }

    /**
     * Checks, without waiting, the trace command buffer a previous {@link #answer} committed.
     * Publishes one GPU row, labelled {@code "<pass name> metal"}, once the buffer reports it
     * finished. {@code gpuStartTime}/{@code gpuEndTime} are the real Metal trace span, not the
     * surrounding buffer copies the Vulkan-side {@code PassTimer} bracket times. Still running is
     * not an error: the next call checks again. An errored buffer (device lost, validation
     * failure) has no real timing and is dropped rather than reported as zero.
     */
    private void drainPendingMetalTiming() {
        if (pendingMetalCommandBuffer == 0) {
            return;
        }
        long status = Objc.msgSendLong(pendingMetalCommandBuffer, Objc.selector("status"));
        if (status != MTL_COMMAND_BUFFER_STATUS_COMPLETED && status != MTL_COMMAND_BUFFER_STATUS_ERROR) {
            return; // still running; the retain keeps the handle valid to check again later
        }
        if (status == MTL_COMMAND_BUFFER_STATUS_COMPLETED) {
            double startSeconds = Objc.msgSendDouble(pendingMetalCommandBuffer, Objc.selector("gpuStartTime"));
            double endSeconds = Objc.msgSendDouble(pendingMetalCommandBuffer, Objc.selector("gpuEndTime"));
            if (endSeconds > startSeconds) {
                dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler()
                        .record(pendingMetalPassName + " metal", (endSeconds - startSeconds) * 1000.0);
            }
        }
        Objc.msgSendVoid(pendingMetalCommandBuffer, Objc.selector("release"));
        pendingMetalCommandBuffer = 0;
        pendingMetalPassName = null;
    }

    private void ensureBuffers(VulkanDevice device, long requestBytes, long hitBytes) {
        if (requests != null && requests.sizeBytes() >= requestBytes
                && hits != null && hits.sizeBytes() >= hitBytes) {
            return;
        }
        await(device);
        MetalRtGeometry.destroyOne(device, requests);
        MetalRtGeometry.destroyOne(device, hits);
        requests = MetalRtGeometry.createExportedBuffer(device, requestBytes);
        hits = MetalRtGeometry.createExportedBuffer(device, hitBytes);
    }

    private static void copy(VkCommandBuffer cmd, MemoryStack stack, long source, long destination, long bytes) {
        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
        region.srcOffset(0).dstOffset(0).size(bytes);
        VK13.vkCmdCopyBuffer(cmd, source, destination, region);
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, int sourceAccess, int destinationAccess) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
    }

    private void await(VulkanDevice device) {
        if (timeline != null && lastValue > 0) {
            VulkanMetalInterop.waitTimeline(device, timeline, lastValue);
        }
    }

    @Override
    public void close() {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device != null) {
            // Waits for the timeline value the last answer() signalled. The Metal trace buffer
            // signals that value before the Vulkan copy-back that follows it, so when this
            // returns the trace buffer is done and the ordinary read-and-release path below
            // applies rather than a forced one.
            await(device);
            drainPendingMetalTiming();
            MetalRtGeometry.destroyOne(device, requests);
            MetalRtGeometry.destroyOne(device, hits);
            if (timeline != null) {
                VK13.vkDestroySemaphore(device.vkDevice(), timeline.vkSemaphore, null);
            }
        }
        if (pendingMetalCommandBuffer != 0) {
            // No Vulkan device to synchronize through, or the buffer was somehow still running
            // above: release the retain without reading rather than leak it.
            Objc.msgSendVoid(pendingMetalCommandBuffer, Objc.selector("release"));
            pendingMetalCommandBuffer = 0;
            pendingMetalPassName = null;
        }
        if (kernel != null) {
            kernel.release();
            kernel = null;
        }
        requests = null;
        hits = null;
        timeline = null;
        nextValue = 1;
        lastValue = 0;
    }
}
