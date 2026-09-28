package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pipeline.VulkanPartialFlush;
import dev.icehunter.fornax.util.GpuFatalException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

/** One immutable grid per rendered frame. Workers publish CPU records; only prepare submits them. */
public final class VoxelUploadFrame implements AutoCloseable {
    private static VoxelUploadFrame current;
    private static final ThreadLocal<VoxelUploadFrame> recording = new ThreadLocal<>();
    // One initial harvest batch per frame bounds transfer packing; invalidations bypass this budget.
    private static final int MAX_SLOT_UPLOADS = VoxelWindow.BatchSizeController.INITIAL_TARGET;
    private final TargetRegistry registry;
    private final VulkanComputeBackend backend;
    private final long fence;
    private final long graphicsComplete;
    private final long uploadReady;
    private final VoxelFrameOrder order = new VoxelFrameOrder();
    private final List<Runnable> submittedCallbacks = new ArrayList<>();
    private VkCommandBuffer command;
    private boolean pending;
    private long uploadValue;

    private VoxelUploadFrame(TargetRegistry registry, VulkanComputeBackend backend) {
        this.registry = registry;
        this.backend = backend;
        long builtFence = 0, builtGraphics = 0, builtReady = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            check(VK13.vkCreateFence(backend.device().vkDevice(), VkFenceCreateInfo.calloc(stack)
                    .sType$Default(), null, out), "create fence");
            builtFence = out.get(0);
            builtGraphics = createTimeline();
            builtReady = createTimeline();
        } catch (RuntimeException | Error failure) {
            if (builtReady != 0) VK13.vkDestroySemaphore(backend.device().vkDevice(), builtReady, null);
            if (builtGraphics != 0) VK13.vkDestroySemaphore(backend.device().vkDevice(), builtGraphics, null);
            if (builtFence != 0) VK13.vkDestroyFence(backend.device().vkDevice(), builtFence, null);
            backend.close();
            throw failure;
        }
        fence = builtFence;
        graphicsComplete = builtGraphics;
        uploadReady = builtReady;
    }

    public static void prepare(TargetRegistry registry) {
        try {
            prepareFrame(registry);
        } catch (RuntimeException failure) {
            // Once a submit or encoder wait has been recorded, abandoning this frame cannot
            // safely resume rendering against the grid. Teardown must retain that dependency.
            throw fatal("prepare", failure);
        }
    }

    private static void prepareFrame(TargetRegistry registry) {
        if (registry.getBuffer(BrickGridUpload.OCCUPANCY_TARGET) == null) return;
        if (current == null) {
            VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
            if (backend == null) return;
            current = new VoxelUploadFrame(registry, backend);
        }
        if (current.registry != registry) throw new IllegalStateException("Voxel frame registry changed without teardown");
        current.begin();
    }

    private void begin() {
        VulkanCommandEncoder graphics = backend.device().createCommandEncoder();
        // Submit the already-recorded previous-frame signal BEFORE a raw transfer may wait on it.
        // This is outside SHARED_QUEUE_LOCK and never waits for this frame's future release.
        ((VulkanPartialFlush) graphics).fornax$flushPending();
        awaitPending();
        long waitValue = order.begin();
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            if (registry.getBuffer(BrickGridUpload.OCCUPANCY_TARGET) == null
                    || !BrickGridUpload.hasQueuedUploads(registry)) return;
            backend.commandPool().reset();
            check(VK13.vkResetFences(backend.device().vkDevice(), fence), "reset fence");
            command = backend.commandPool().allocateBuffer();
            submittedCallbacks.clear();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(VK13.vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin");
                VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
                VK13.vkCmdPipelineBarrier(command, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
                recording.set(this);
                try { BrickGridUpload.drainQueuedUploads(registry, MAX_SLOT_UPLOADS); }
                finally { recording.remove(); }
                VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT);
                VK13.vkCmdPipelineBarrier(command, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, after, null, null);
                check(VK13.vkEndCommandBuffer(command), "end");
                long signalValue = Math.incrementExact(uploadValue);
                VkTimelineSemaphoreSubmitInfo timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                        .pWaitSemaphoreValues(stack.longs(waitValue)).pSignalSemaphoreValues(stack.longs(signalValue));
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pNext(timeline.address())
                        .pCommandBuffers(stack.pointers(command)).pWaitSemaphores(stack.longs(graphicsComplete))
                        .pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_TRANSFER_BIT))
                        .pSignalSemaphores(stack.longs(uploadReady));
                check(VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submit, fence), "submit");
                pending = true;
                uploadValue = signalValue;
                // Semaphore waits supply execution AND memory visibility across queue families.
                graphics.waitSemaphore(uploadReady, signalValue, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                for (Runnable callback : submittedCallbacks) callback.run();
            } finally {
                recording.remove();
                submittedCallbacks.clear();
                command = null;
            }
        }
    }

    static boolean record(Consumer<VkCommandBuffer> commands) {
        VoxelUploadFrame frame = recording.get();
        if (frame == null) return false;
        // A metadata invalidation, slot clear and newer payload may overlap in this command
        // buffer. Transfer commands alone do not order those writes (Vulkan synchronization).
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK13.vkCmdPipelineBarrier(frame.command, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
        }
        commands.accept(frame.command);
        return true;
    }

    static void afterSubmit(Runnable callback) {
        VoxelUploadFrame frame = recording.get();
        if (frame == null) throw new IllegalStateException("Voxel commit outside frame transfer");
        frame.submittedCallbacks.add(callback);
    }

    public static void graphicsReadsComplete() {
        if (current == null || !current.order.isOpen()) return;
        try {
            long value = current.order.nextRelease();
            current.backend.device().createCommandEncoder().signalSemaphore(current.graphicsComplete, value,
                    VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            current.order.published(value);
        } catch (RuntimeException failure) {
            throw fatal("publish graphics completion", failure);
        }
    }

    /** Flush pending timeline references before GraphRunner waits for submitted queues to idle. */
    public static void flushBeforeDestroy() {
        if (current == null) return;
        try {
            VulkanCommandEncoder graphics = current.backend.device().createCommandEncoder();
            ((VulkanPartialFlush) graphics).fornax$flushPending();
        } catch (RuntimeException failure) {
            // Do not destroy semaphores whose encoder references could still be submitted later.
            throw fatal("flush before teardown", failure);
        }
    }

    private void awaitPending() {
        if (!pending) return;
        check(VK13.vkWaitForFences(backend.device().vkDevice(), fence, true, -1L), "recycle transfer");
        pending = false;
    }

    /** GraphRunner calls this after its existing device-idle teardown boundary. */
    public static void closeCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override public void close() {
        awaitPending();
        VK13.vkDestroySemaphore(backend.device().vkDevice(), uploadReady, null);
        VK13.vkDestroySemaphore(backend.device().vkDevice(), graphicsComplete, null);
        VK13.vkDestroyFence(backend.device().vkDevice(), fence, null);
        backend.close();
    }

    private long createTimeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK13.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            LongBuffer out = stack.mallocLong(1);
            check(VK13.vkCreateSemaphore(backend.device().vkDevice(), VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default().pNext(type.address()), null, out), "create timeline");
            return out.get(0);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK13.VK_SUCCESS) throw new GpuFatalException("Voxel frame " + operation + " failed: " + result);
    }

    private static GpuFatalException fatal(String operation, RuntimeException failure) {
        if (failure instanceof GpuFatalException gpuFailure) return gpuFailure;
        GpuFatalException result = new GpuFatalException("Voxel frame " + operation + " failed: " + failure.getMessage());
        result.initCause(failure);
        return result;
    }
}
