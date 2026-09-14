package dev.icehunter.fornax.pack.graph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;

/** Allocation policy for uniform buffers bound on both graphics and raw-compute queues. */
public final class VulkanBufferSharing {
    private VulkanBufferSharing() {
    }

    /**
     * Uses Vulkan's concurrent sharing contract when the two queue families differ. The caller's
     * active memory stack must outlive the buffer allocation; this only changes ownership, so
     * upload visibility and frame-resource retirement still need their existing synchronization.
     */
    public static VkBufferCreateInfo configure(VkBufferCreateInfo info, int graphicsFamily, int computeFamily) {
        if ((info.usage() & VK13.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) != 0 && graphicsFamily != computeFamily) {
            info.sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(MemoryStack.stackGet().ints(graphicsFamily, computeFamily));
        }
        return info;
    }
}
