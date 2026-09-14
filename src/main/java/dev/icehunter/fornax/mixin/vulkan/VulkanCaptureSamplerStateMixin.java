package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import dev.icehunter.fornax.debug.CaptureBufferUsage;
import dev.icehunter.fornax.debug.CaptureSamplerState;
import dev.icehunter.fornax.pipeline.CapturedSamplerState;
import java.util.Map;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Observes the exact Vulkan sampler creation arguments without changing them. Without startup
 * capture configuration it retains no snapshot; absent metadata makes a capture incomplete. */
@Mixin(VulkanGpuSampler.class)
abstract class VulkanCaptureSamplerStateMixin implements CapturedSamplerState {
    @Unique private Map<String, Object> fornax$capturedSamplerState;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateSampler(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSamplerCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I",
            remap = false), index = 1)
    private VkSamplerCreateInfo fornax$captureSamplerState(VkSamplerCreateInfo info) {
        if (CaptureBufferUsage.enabled()) fornax$capturedSamplerState = CaptureSamplerState.snapshot(info);
        return info;
    }

    @Override @Unique
    public Map<String, Object> fornax$samplerState() {
        return fornax$capturedSamplerState == null ? Map.of() : fornax$capturedSamplerState;
    }
}
