package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.util.GpuFatalException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * Drains {@link EngineBufferUploadQueue} for the targets whose only enabled readers are graphics
 * passes (fullscreen or particles), on the graphics queue, in a transient command buffer.
 *
 * <p>Single queue family, so this never performs a queue-family ownership transfer: a buffer that
 * both queues can touch is already created {@code VK_SHARING_MODE_CONCURRENT} across families
 * when they differ ({@link TargetRegistry}'s own allocation), and that has nothing to do with
 * which queue records a given frame's write. When one of these targets is created it is filled
 * with zeros on the compute queue, and that work waits for the queue to go idle (see
 * {@link TargetRegistry}'s own sizing path), so it is done before the first graphics write this
 * method records.
 *
 * <p>Must run outside any render pass, since {@link VulkanCommandEncoder#execute} throws if called
 * while one is active, the same rule {@code ArrayTextures.copyLayer} states for its own transient
 * buffer.
 */
final class GraphicsBufferUploads {
    private GraphicsBufferUploads() {}

    /**
     * Records this frame's pending writes for {@code targets} into a transient graphics command
     * buffer and runs it at once: no flush, no fence, no wait on the host. Returns without
     * allocating a command buffer when nothing is pending, or when there is no Vulkan device yet.
     *
     * <p>Checks every pending target's ranges against its buffer's real size before allocating the
     * command buffer. {@link EngineBufferUploadQueue#recordForBindings} runs the same check while
     * draining, and if it threw partway through it would leak the command buffer already being
     * recorded, every frame the fault repeats. A target that fails the check is dropped from the
     * queue and logged once per pack session. Every other target still runs.
     */
    static void record(TargetRegistry registry, List<String> targets) {
        if (targets.isEmpty()) {
            return;
        }
        boolean anyPending = false;
        for (String target : targets) {
            if (!EngineBufferUploadQueue.hasPending(target)) {
                continue;
            }
            BufferInstance buffer = registry.getBuffer(target);
            if (buffer != null && !EngineBufferUploadQueue.pendingFitsBuffer(target, buffer.sizeBytes())) {
                EngineBufferUploadQueue.discard(target);
                GraphRunner.logGraphicsUploadFailureOnce(new IllegalArgumentException(
                        "engine buffer upload for '" + target + "' does not fit its " + buffer.sizeBytes()
                                + "-byte buffer; dropping this frame's update for it"));
                continue;
            }
            anyPending = true;
        }
        if (!anyPending) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return;
        }

        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EngineBufferUploadQueue.recordForBindings(cmd, stack, registry, targets,
                    VK13.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }

        int result = VK13.vkEndCommandBuffer(cmd);
        if (result != VK13.VK_SUCCESS) {
            throw new GpuFatalException("vkEndCommandBuffer failed: " + result);
        }
        encoder.execute(cmd);
    }
}
