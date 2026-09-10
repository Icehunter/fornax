package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkImageCreateInfo;

/** Allocation policy for image access from graph compute and vanilla graphics queues. */
public final class VulkanTextureSharing {
    private VulkanTextureSharing() { }

    public static VkImageCreateInfo configure(VkImageCreateInfo info, int usage, int graphicsFamily, int computeFamily) {
        // TEXTURE_BINDING permits sampled graph-compute reads, including vanilla-owned atlases.
        // Concurrent ownership does not order uploads or same-image animation writes.
        if ((usage & (GpuTexture.USAGE_TEXTURE_BINDING | FornaxTextureUsage.STORAGE)) != 0
                && graphicsFamily != computeFamily) {
            info.sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(MemoryStack.stackGet().ints(graphicsFamily, computeFamily));
        }
        return info;
    }
}
