package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

/**
 * Held by the registry, long-lived, for {@link BrickGridUpload#clearOccupancySlots}. Every other
 * short-lived caller in that class builds a backend with {@code VulkanComputeBackend.tryCreate()},
 * closes it right after, and waits for the fence at once. This class instead keeps one backend, one
 * command pool, and one fence alive across many calls, and only waits for a submission to finish the
 * next time {@link #execute} runs, or when {@link #close} runs. {@code VoxelDebugRaymarchPass} uses
 * the same pattern: submit now, wait only once this slot's objects are about to be used again. That
 * way clearing a shell when the player crosses a section never blocks the render thread on its own
 * fence.
 *
 * <p>Waiting later does not change the order things happen on the GPU. This class and {@link
 * VoxelUploadResources} both submit through the same {@code VkQueue} (one shared handle per device,
 * see {@code VulkanComputeBackend}), and Vulkan runs submissions on one queue in the order they were
 * sent. So a clear submitted here still always runs on the GPU before a harvest upload sent moments
 * later through {@link VoxelUploadResources}, and before any compute step that reads the cleared
 * buffer, no matter when either side's CPU wait happens. The fence never gave that order guarantee;
 * it only says when this object's own command pool and fence are safe to use again.
 *
 * <p>This class does not share a backend, pool, or fence with {@link VoxelUploadResources} on
 * purpose. The two submit one right after the other from the same {@code recenterAndResync} call,
 * clear first, then the harvest upload. Sharing a pool would make the upload's first call wait on
 * the clear's own fence, which undoes the point of waiting later.
 */
public final class VoxelClearResources implements AutoCloseable {
    final ByteBuffer occupancyZeros, faceSealZeros, summaryPending;
    private final VulkanComputeBackend backend;
    private final long fence;
    private boolean pending;
    private boolean closed;

    private VoxelClearResources(VulkanComputeBackend backend) {
        this.backend = backend;
        occupancyZeros = MemoryUtil.memCalloc((int) BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
        faceSealZeros = MemoryUtil.memCalloc((int) BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
        // Not memCalloc: this must hold SUMMARY_PENDING, not all-zero bytes. See the note on
        // SUMMARY_PENDING in BrickGridUpload.clearOccupancySlots.
        summaryPending = MemoryUtil.memAlloc((int) BrickGridUpload.BRICK_SUMMARY_BYTES_PER_SLOT);
        summaryPending.putInt(0, BrickGridUpload.SUMMARY_PENDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            check(VK13.vkCreateFence(backend.device().vkDevice(),
                    VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "fence creation");
            fence = out.get(0);
        }
    }

    public static @Nullable VoxelClearResources tryCreate() {
        VoxelUploadResources.requireLock();
        VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
        if (backend == null) return null;
        try {
            return new VoxelClearResources(backend);
        } catch (RuntimeException | Error failure) {
            backend.close();
            throw failure;
        }
    }

    /** Waits for the last submission if one is still running, records a fresh command buffer with
     * {@code record}, and submits it. Returns at once, without waiting for this new submission to
     * finish; that wait happens on the next call to {@link #execute}, or on {@link #close}. Caller
     * holds {@code SHARED_QUEUE_LOCK}. */
    void execute(Consumer<VkCommandBuffer> record) {
        VoxelUploadResources.requireLock();
        awaitPending();
        backend.commandPool().reset();
        check(VK13.vkResetFences(backend.device().vkDevice(), fence), "fence reset");
        VkCommandBuffer command = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(VK13.vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin");
            record.accept(command);
            check(VK13.vkEndCommandBuffer(command), "end");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command));
            check(VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submit, fence), "submit");
        }
        pending = true;
    }

    private void awaitPending() {
        if (pending) {
            check(VK13.vkWaitForFences(backend.device().vkDevice(), fence, true, -1L), "completion wait");
            pending = false;
        }
    }

    @Override public void close() {
        VoxelUploadResources.requireLock();
        if (closed) return;
        // A failed wait leaves pending true; closing retries it before destroying anything.
        awaitPending();
        VK13.vkDestroyFence(backend.device().vkDevice(), fence, null);
        backend.close();
        MemoryUtil.memFree(occupancyZeros);
        MemoryUtil.memFree(faceSealZeros);
        MemoryUtil.memFree(summaryPending);
        closed = true;
    }

    private static void check(int result, String operation) {
        if (result != VK13.VK_SUCCESS)
            throw new IllegalStateException("Voxel clear " + operation + " failed with VkResult " + result);
    }
}
