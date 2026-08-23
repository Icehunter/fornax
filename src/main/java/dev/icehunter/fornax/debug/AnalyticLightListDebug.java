package dev.icehunter.fornax.debug;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.AnalyticLightListBuffer;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.voxel.VoxelWindow;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * One-shot GPU -&gt; CPU readback of {@code analyticLightList}'s word 0 (the atomic/capped light
 * count), for the analytic-lights milestone's M1 debug keybind (see {@link FornaxDebugKeys}). This
 * milestone has no rendering consumer yet (M2 adds {@code direct_light_analytic.fsh}), so a manual
 * readback is the only way to verify {@code light_list_build.comp} is actually populating the buffer.
 *
 * <p>Deliberately NOT built like {@link dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass}'s own
 * per-frame-optimized deferred-fence ring -- this fires at most once per keypress, not every frame, so
 * it uses the simpler stall-based one-shot precedent {@link TargetRegistry#ensureBufferSize}'s own
 * {@code clearBuffer} helper already establishes for rare ops: record a copy into a tiny host-visible
 * staging buffer, submit, {@code queue.waitIdle()}, read, destroy. There is no existing generic
 * SSBO-readback utility in this codebase to reuse -- {@link
 * dev.icehunter.fornax.pipeline.GBufferReadbackDiagnostic} is texture-only (Blaze3D {@code GpuBuffer}
 * types, not a raw {@code VkBuffer} handle), and {@code VoxelDebugRaymarchPass}'s own mapped-buffer
 * machinery is bespoke to its own private per-frame output, not generalized.
 */
public final class AnalyticLightListDebug {
    private AnalyticLightListDebug() {}

    private static final long STAGING_BYTES = 4L; // word 0 (the light count) only

    /** Sentinel: the buffer is not currently allocated (ANALYTIC_LIGHTS off, no active voxel window,
     * or no compute backend available) -- distinct from a genuine 0-lights reading. */
    public static final int NOT_ALLOCATED = -1;

    /** Sentinel: a Vulkan call failed; see the log for the real error. Distinct from a genuine
     * 0-lights reading and from {@link #NOT_ALLOCATED}. */
    public static final int READBACK_FAILED = -2;

    /**
     * Reads back {@code analyticLightList}'s word 0. Stalls the calling thread (render thread, when
     * called from a debug keybind) until the copy completes -- acceptable for a once-per-keypress
     * diagnostic, per this class's own doc comment on why the heavier deferred-fence ring is not used.
     */
    public static int readLightCount() {
        TargetRegistry registry = VoxelWindow.attachedRegistry();
        if (registry == null) {
            return NOT_ALLOCATED;
        }
        // Held across the handle read + command record + submit, matching every other real
        // BufferInstance consumer in this codebase (TargetRegistry.ensureBufferSize,
        // VoxelDebugRaymarchPass.presentIfEnabled) -- a concurrent resize/pack-reload can free/reassign
        // this same buffer, and reading its vkBuffer() handle without the lock is a use-after-free.
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            BufferInstance buffer = registry.getBuffer(AnalyticLightListBuffer.TARGET);
            if (buffer == null) {
                return NOT_ALLOCATED;
            }
            try (VulkanComputeBackend backend = VulkanComputeBackend.tryCreate()) {
                if (backend == null) {
                    return NOT_ALLOCATED;
                }
                return copyWord0(backend, buffer.vkBuffer());
            } catch (RuntimeException e) {
                FornaxMod.LOGGER.error("[Fornax] AnalyticLightListDebug: readback failed", e);
                return READBACK_FAILED;
            }
        }
    }

    private static int copyWord0(VulkanComputeBackend backend, long srcBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(STAGING_BYTES)
                    .usage(VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            // Host-visible, persistently-mapped -- the same VMA_MEMORY_USAGE_AUTO +
            // HOST_ACCESS_RANDOM_BIT|MAPPED_BIT flag combination VoxelDebugRaymarchPass.ensureOutputBuffer
            // uses for its own CPU-readable output, so vmaCreateBuffer hands back a real CPU pointer via
            // allocInfoOut.pMappedData() with no separate vmaMapMemory call needed.
            VmaAllocationCreateInfo allocCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer bufferOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VmaAllocationInfo allocInfoOut = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(backend.device().vma(), stagingInfo, allocCreateInfo,
                    bufferOut, allocationOut, allocInfoOut);
            if (result != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error(
                        "[Fornax] AnalyticLightListDebug: vmaCreateBuffer (staging) failed with VkResult {}", result);
                return READBACK_FAILED;
            }
            long stagingBuffer = bufferOut.get(0);
            long stagingAllocation = allocationOut.get(0);
            try {
                VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                VK13.vkBeginCommandBuffer(cmd, beginInfo);
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0).dstOffset(0).size(STAGING_BYTES);
                VK13.vkCmdCopyBuffer(cmd, srcBuffer, stagingBuffer, region);
                VK13.vkEndCommandBuffer(cmd);
                // Rare one-shot op: batch-then-submit-on-close (Submission.close()) + a full queue
                // waitIdle(), the same "just wait" shape TargetRegistry.clearBuffer already uses for its
                // own rare, non-per-frame compute submit -- no explicit fence/ring needed at this cadence.
                try (var submission = backend.computeQueue().beginSubmit()) {
                    submission.executeCommands(cmd);
                }
                backend.computeQueue().waitIdle();
                backend.commandPool().reset();

                // Make the just-copied bytes visible to this CPU read on non-coherent memory too (the
                // VoxelDebugRaymarchPass.presentIfEnabled precedent for reading a VMA-mapped allocation
                // after GPU writes complete).
                Vma.vmaInvalidateAllocation(backend.device().vma(), stagingAllocation, 0, STAGING_BYTES);
                ByteBuffer mapped = MemoryUtil.memByteBuffer(allocInfoOut.pMappedData(), (int) STAGING_BYTES);
                return mapped.getInt(0);
            } finally {
                Vma.vmaDestroyBuffer(backend.device().vma(), stagingBuffer, stagingAllocation);
            }
        }
    }
}
