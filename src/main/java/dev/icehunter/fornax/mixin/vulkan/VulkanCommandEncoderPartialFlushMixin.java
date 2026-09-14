package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import dev.icehunter.fornax.pipeline.VulkanPartialFlush;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds an explicit partial dispatch to the Vulkan encoder for the MetalFX event handoffs.
 * Closing its existing submission preserves every pending command and semaphore on the same
 * graphics queue. This adds no automatic injection: with interop inactive it is never called.
 *
 * <p>The normal full submit's ALL_COMMANDS completion covers preceding partial batches on that
 * queue. Leave its completion epoch and resource retirement untouched, conservatively extending
 * GPU lifetimes until that boundary. In particular, a partial dispatch cannot satisfy an ordinary
 * encoder fence or authorize recycling a command pool. Queue/native calls can still block; this
 * operation only avoids the full-submit retirement wait.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderPartialFlushMixin implements VulkanPartialFlush {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private VulkanTransientMemory transientMemory;
    @Shadow private VulkanQueue.Submission submissionBuilder;

    @Shadow
    private void endCommandBuffer() {
        throw new AssertionError();
    }

    @Override
    @Unique
    public void fornax$flushPending() {
        endCommandBuffer();
        transientMemory.endSubmit();
        submissionBuilder.close();
        submissionBuilder = device.graphicsQueue().beginSubmit();
        // beginSubmit invalidates old transient handles and starts fresh upload recording.
        transientMemory.beginSubmit();
    }
}
