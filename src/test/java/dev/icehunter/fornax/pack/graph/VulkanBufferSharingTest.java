package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanConst;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;

/** Native allocation-policy checks; no Vulkan device or client is created. */
final class VulkanBufferSharingTest {
    @Test
    void uniformAllocationsShareExactlyTheTwoDistinctFamiliesIncludingCaptureAndMappedUsages() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int extra : new int[]{0, GpuBuffer.USAGE_COPY_SRC, GpuBuffer.USAGE_MAP_WRITE,
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST}) {
                int nativeUsage = VulkanConst.bufferUsageToVk(GpuBuffer.USAGE_UNIFORM | extra);
                VkBufferCreateInfo info = buffer(stack, nativeUsage);
                assertSame(info, configure(info, 0, 3));
                assertEquals(VK13.VK_SHARING_MODE_CONCURRENT, info.sharingMode());
                assertArrayEquals(new int[]{0, 3}, families(info));
                assertEquals(nativeUsage, info.usage());
                assertEquals(8192L, info.size());
                assertEquals(VK13.VK_BUFFER_CREATE_SPARSE_BINDING_BIT, info.flags());
                assertEquals(0L, info.pNext());
            }
        }
    }

    @Test
    void oneQueueFamilyKeepsTheOriginalExclusivePolicy() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = buffer(stack, VK13.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            configure(info, 4, 4);
            assertEquals(VK13.VK_SHARING_MODE_EXCLUSIVE, info.sharingMode());
            assertArrayEquals(new int[0], families(info));
        }
    }

    @Test
    void nonUniformAllocationsAreUntouchedEvenWithDistinctFamilies() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int usage : new int[]{0, VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK13.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, VK13.VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT}) {
                VkBufferCreateInfo info = buffer(stack, usage);
                assertSame(info, configure(info, 2, 5));
                assertEquals(VK13.VK_SHARING_MODE_EXCLUSIVE, info.sharingMode());
                assertArrayEquals(new int[0], families(info));
                assertEquals(usage, info.usage());
            }
        }
    }

    @Test
    void allocationPolicyDoesNotRewriteOtherCreateInfoFieldsOrAnUnrelatedSharingPolicy() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = buffer(stack, VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(stack.ints(1, 6));
            configure(info, 2, 5);
            assertEquals(VK13.VK_SHARING_MODE_CONCURRENT, info.sharingMode());
            assertArrayEquals(new int[]{1, 6}, families(info));
        }
    }

    private static VkBufferCreateInfo configure(VkBufferCreateInfo info, int graphics, int compute) {
        return VulkanBufferSharing.configure(info, graphics, compute);
    }

    private static VkBufferCreateInfo buffer(MemoryStack stack, int usage) {
        return VkBufferCreateInfo.calloc(stack).sType$Default().size(8192L).usage(usage)
                .flags(VK13.VK_BUFFER_CREATE_SPARSE_BINDING_BIT)
                .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE).pQueueFamilyIndices(null);
    }

    private static int[] families(VkBufferCreateInfo info) {
        int[] result = new int[info.queueFamilyIndexCount()];
        if (result.length > 0) info.pQueueFamilyIndices().get(0, result);
        return result;
    }
}
