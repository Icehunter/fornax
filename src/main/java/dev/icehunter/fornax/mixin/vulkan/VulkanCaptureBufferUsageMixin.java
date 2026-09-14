package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.debug.CaptureBufferUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds transfer-source usage to UBO allocations only when capture is configured at startup.
 * The argument hook preserves the allocator and performs no copies; without that configuration
 * it returns the original usage. A late F10 request reports unavailable instead of unsafe copying. */
@Mixin(VulkanDevice.class)
abstract class VulkanCaptureBufferUsageMixin {
    @ModifyVariable(method = "createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int fornax$captureUniformUsage(int usage) {
        return CaptureBufferUsage.forCapture(usage);
    }
}
