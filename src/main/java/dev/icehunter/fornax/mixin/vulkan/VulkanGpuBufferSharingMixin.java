package dev.icehunter.fornax.mixin.vulkan;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.icehunter.fornax.pack.graph.VulkanBufferSharing;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Gives Direct uniform-buffer allocations access to the device's graphics and compute families.
 * The allocation argument is the last point before VMA consumes the complete native create-info.
 * This capability is established even without a pack: buffers can outlive pack activation, and
 * sharing mode cannot change afterward. It adds no work or bindings while a pack is inactive.
 * The static handler uses only constructor arguments because allocation precedes superclass init.
 */
@Mixin(VulkanGpuBuffer.Direct.class)
abstract class VulkanGpuBufferSharingMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaCreateBuffer(JLorg/lwjgl/vulkan/VkBufferCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I",
            remap = false), index = 1)
    private static VkBufferCreateInfo fornax$shareUniformBufferAcrossQueues(
            VkBufferCreateInfo info, @Local(argsOnly = true) VulkanDevice device) {
        int graphicsFamily = device.graphicsQueue().queueFamilyIndex();
        int computeFamily = device.computeQueue().queueFamilyIndex();
        return VulkanBufferSharing.configure(info, graphicsFamily, computeFamily);
    }
}
