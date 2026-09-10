package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkImageCreateInfo;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the allocation policy on native create-info structs without creating a Vulkan device. */
class VulkanTextureSharingTest {
    @Test void sampledBaseArraysAndNeutralArraysShareBothDistinctQueueFamilies() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int layers : new int[]{1, 3}) {
                for (int size : new int[]{1, 4096}) {
                    VkImageCreateInfo info = image(stack, layers, size);
                    int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
                    assertSame(info, VulkanTextureSharing.configure(info, usage, 0, 3));
                    assertEquals(VK13.VK_SHARING_MODE_CONCURRENT, info.sharingMode());
                    assertArrayEquals(new int[]{0, 3}, families(info));
                    assertEquals(layers, info.arrayLayers());
                    assertEquals(size, info.extent().width());
                    assertEquals(VK13.VK_IMAGE_USAGE_SAMPLED_BIT | VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                            info.usage(), "sharing must not add storage usage to atlas allocations");
                }
            }
        }
    }

    @Test void everySampledOrStorageUsageCombinationSharesButTransferOnlyImagesStayExclusive() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Mojang uses bits0..4; Fornax STORAGE uses bit5. Cover every current flag combination.
            for (int usage = 0; usage < 64; usage++) {
                VkImageCreateInfo info = image(stack, 1, 16);
                VulkanTextureSharing.configure(info, usage, 2, 5);
                boolean shared = (usage & (GpuTexture.USAGE_TEXTURE_BINDING | FornaxTextureUsage.STORAGE)) != 0;
                assertEquals(shared ? VK13.VK_SHARING_MODE_CONCURRENT : VK13.VK_SHARING_MODE_EXCLUSIVE,
                        info.sharingMode(), "usage=" + usage);
                assertArrayEquals(shared ? new int[]{2, 5} : new int[0], families(info), "usage=" + usage);
            }
        }
    }

    @Test void aSingleQueueFamilyRemainsExclusiveWithoutDuplicateIndices() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int usage = 0; usage < 64; usage++) {
                VkImageCreateInfo info = image(stack, 3, 1);
                VulkanTextureSharing.configure(info, usage, 4, 4);
                assertEquals(VK13.VK_SHARING_MODE_EXCLUSIVE, info.sharingMode());
                assertEquals(0, info.queueFamilyIndexCount());
            }
        }
    }

    private static VkImageCreateInfo image(MemoryStack stack, int layers, int size) {
        return VkImageCreateInfo.calloc(stack).sType$Default().arrayLayers(layers)
                .extent(extent -> extent.set(size, size, 1))
                .usage(VK13.VK_IMAGE_USAGE_SAMPLED_BIT | VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
    }
    private static int[] families(VkImageCreateInfo info) {
        int[] result = new int[info.queueFamilyIndexCount()];
        if (result.length > 0) info.pQueueFamilyIndices().get(0, result);
        return result;
    }
}
