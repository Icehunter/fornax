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
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.function.Consumer;

/** Batch workspace owned by the registry. Every access goes through SHARED_QUEUE_LOCK, one at a time. */
public final class VoxelUploadResources implements AutoCloseable {
    private final ByteBuffer allocation;
    final ByteBuffer occupancy, payload, faceSeal, palette, summary, faceTexture, lightmap, lightZero;
    private final NativeTransfer nativeTransfer;
    private final SynchronousTransfer transfer;
    private boolean closed;

    private VoxelUploadResources(VulkanComputeBackend backend) {
        int[] sizes = {(int) BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, BrickGridUpload.VOXELS_PER_SECTION,
                (int) BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT, (int) BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                (int) BrickGridUpload.BRICK_SUMMARY_BYTES_PER_SLOT, VoxelFaceTexture.BYTES_PER_SLOT,
                VoxelLightmap.BYTES_PER_SLOT, Math.toIntExact(BrickGridUpload.lightVolumeBytesPerSlot())};
        int bytes = 0;
        for (int size : sizes) bytes = Math.addExact(bytes, size);
        allocation = MemoryUtil.memCalloc(bytes);
        try {
            ByteBuffer[] slices = new ByteBuffer[sizes.length];
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                slices[i] = allocation.slice(offset, sizes[i]).order(ByteOrder.nativeOrder());
                offset += sizes[i];
            }
            occupancy = slices[0]; payload = slices[1]; faceSeal = slices[2]; palette = slices[3];
            summary = slices[4]; faceTexture = slices[5]; lightmap = slices[6]; lightZero = slices[7];
            nativeTransfer = new NativeTransfer(backend);
            transfer = new SynchronousTransfer(nativeTransfer);
        } catch (RuntimeException | Error failure) {
            MemoryUtil.memFree(allocation);
            throw failure;
        }
    }

    public static @Nullable VoxelUploadResources tryCreate() {
        requireLock();
        VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
        if (backend == null) return null;
        try {
            return new VoxelUploadResources(backend);
        } catch (RuntimeException | Error failure) {
            backend.close();
            throw failure;
        }
    }

    public boolean matchesLightLayout() {
        return lightZero.capacity() == BrickGridUpload.lightVolumeBytesPerSlot();
    }

    void execute(Consumer<VkCommandBuffer> record) {
        requireLock();
        transfer.execute(() -> record.accept(nativeTransfer.command));
    }

    public static void requireLock() {
        if (!Thread.holdsLock(VulkanComputeBackend.SHARED_QUEUE_LOCK))
            throw new IllegalStateException("Voxel upload resources require the shared queue lock");
    }

    @Override public void close() {
        requireLock();
        if (closed) return;
        transfer.close();
        MemoryUtil.memFree(allocation);
        closed = true;
    }

    private static void check(int result, String operation) {
        if (result != VK13.VK_SUCCESS)
            throw new IllegalStateException("Voxel batch " + operation + " failed with VkResult " + result);
    }

    private static final class NativeTransfer implements SynchronousTransfer.Backend {
        private final VulkanComputeBackend backend;
        private final long fence;
        private VkCommandBuffer command;

        NativeTransfer(VulkanComputeBackend backend) {
            this.backend = backend;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.mallocLong(1);
                check(VK13.vkCreateFence(backend.device().vkDevice(),
                        VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "fence creation");
                fence = out.get(0);
            }
        }

        @Override public void reset() {
            backend.commandPool().reset();
            check(VK13.vkResetFences(backend.device().vkDevice(), fence), "fence reset");
            command = backend.commandPool().allocateBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(VK13.vkBeginCommandBuffer(command,
                        VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin");
            }
        }

        @Override public void submit() {
            check(VK13.vkEndCommandBuffer(command), "end");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command));
                check(VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submit, fence), "submit");
            }
        }

        @Override public void await() {
            // UINT64_MAX: nothing reads, reuses or destroys this until the wait returns.
            check(VK13.vkWaitForFences(backend.device().vkDevice(), fence, true, -1L), "completion wait");
        }

        @Override public void close() {
            VK13.vkDestroyFence(backend.device().vkDevice(), fence, null);
            backend.close();
        }
    }
}
