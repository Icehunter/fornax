package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.pack.graph.FornaxTextureUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Makes storage graph textures legal on distinct graphics/compute queue families. */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanGpuTextureStorageSharingMixin {
    @Shadow @Final private VulkanDevice device;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I",
            remap = false), index = 1)
    private VkImageCreateInfo fornax$shareStorageImageAcrossQueues(VkImageCreateInfo info) {
        if ((((GpuTexture) (Object) this).usage() & FornaxTextureUsage.STORAGE) == 0) {
            return info;
        }
        int graphicsFamily = device.graphicsQueue().queueFamilyIndex();
        int computeFamily = device.computeQueue().queueFamilyIndex();
        if (graphicsFamily != computeFamily) {
            info.sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(MemoryStack.stackGet().ints(graphicsFamily, computeFamily));
        }
        return info;
    }
}
