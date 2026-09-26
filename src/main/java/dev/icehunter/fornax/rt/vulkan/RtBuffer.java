package dev.icehunter.fornax.rt.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * A buffer with a device address, allocated through {@link RtAllocator}: what an
 * acceleration-structure build reads its vertices, instances and scratch from, what it writes the
 * structure into, and what the ray kernels reach through {@code GL_EXT_buffer_reference}.
 *
 * <p>Two kinds. A device-local buffer is written by GPU commands only. A host-visible one is
 * persistently mapped and coherent, for the small per-frame tables the CPU fills (instance
 * records, the per-mesh primitive address table); {@link #mappedBytes()} is its window.
 *
 * <p>Sizes are 4-byte multiples by construction, so {@link #zeroFill}, a {@code vkCmdFillBuffer},
 * can clear a whole buffer; VRAM is not zero-filled on every backend.
 *
 * <p>Render-thread confined, like every raw handle in this engine.
 *
 * @param mapped the host pointer of a host-visible buffer, 0 for a device-local one
 */
public record RtBuffer(long buffer, long allocation, long sizeBytes, long deviceAddress, long mapped) {

    /** Every buffer here is addressed; callers add what else they need (storage, transfer, AS). */
    public static final int BASE_USAGE = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    public RtBuffer {
        if (buffer == 0 || allocation == 0 || deviceAddress == 0) {
            throw new IllegalArgumentException("an RtBuffer carries live handles and a nonzero address");
        }
        checkedSize(sizeBytes);
    }

    /** Positive and a multiple of 4, so the whole buffer can be cleared with one fill. */
    static long checkedSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes % 4 != 0) {
            throw new IllegalArgumentException("buffer size must be a positive multiple of 4, got " + sizeBytes);
        }
        return sizeBytes;
    }

    /** The caller's usage plus the address bit; a buffer nobody can address is not this class. */
    static int usageFor(int usage) {
        return usage | BASE_USAGE;
    }

    /** A device-local buffer, not zero-filled: a caller that reads before it writes records
     * {@link #zeroFill} first. */
    public static RtBuffer create(RtAllocator allocator, long sizeBytes, int usage, String what) {
        return allocate(allocator, sizeBytes, usage, false, what);
    }

    /** A host-visible, host-coherent, persistently mapped buffer. */
    public static RtBuffer createHostVisible(RtAllocator allocator, long sizeBytes, int usage, String what) {
        return allocate(allocator, sizeBytes, usage, true, what);
    }

    private static RtBuffer allocate(RtAllocator allocator, long sizeBytes, int usage, boolean hostVisible, String what) {
        checkedSize(sizeBytes);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(usageFor(usage))
                    .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
            // Structures are built on the compute queue and traversed on the graphics queue, and
            // a mesh's packed copy is written by one and decoded by the other. Where those are
            // different families, exclusive ownership would need a release/acquire pair around
            // every crossing; concurrent sharing removes that requirement, and the timeline
            // semaphore between the queues is what carries availability and visibility.
            int graphicsFamily = allocator.device().graphicsQueue().queueFamilyIndex();
            int computeFamily = allocator.device().computeQueue().queueFamilyIndex();
            if (graphicsFamily != computeFamily) {
                bufferInfo.sharingMode(VK13.VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsFamily, computeFamily));
            }
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                allocationInfo
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT)
                        // Coherent, so a CPU write is visible to the GPU without a flush call
                        // between the write and the submit that reads it.
                        .requiredFlags(VK13.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                | VK13.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            } else {
                allocationInfo.requiredFlags(VK13.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
            LongBuffer bufferOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VmaAllocationInfo resultInfo = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(allocator.vma(), bufferInfo, allocationInfo,
                    bufferOut, allocationOut, resultInfo);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer (" + what + ", " + sizeBytes + " bytes) failed: " + result);
            }
            long buffer = bufferOut.get(0);
            long allocation = allocationOut.get(0);

            VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(buffer);
            long address = VK12.vkGetBufferDeviceAddress(allocator.device().vkDevice(), addressInfo);
            if (address == 0) {
                Vma.vmaDestroyBuffer(allocator.vma(), buffer, allocation);
                throw new IllegalStateException("vkGetBufferDeviceAddress (" + what + ") returned 0: "
                        + "was bufferDeviceAddress enabled at device creation?");
            }
            long mapped = hostVisible ? resultInfo.pMappedData() : 0L;
            if (hostVisible && mapped == 0) {
                Vma.vmaDestroyBuffer(allocator.vma(), buffer, allocation);
                throw new IllegalStateException("VMA did not map the host-visible buffer (" + what + ")");
            }
            return new RtBuffer(buffer, allocation, sizeBytes, address, mapped);
        }
    }

    /** The host window of a host-visible buffer, little-endian. Throws for a device-local one. */
    public ByteBuffer mappedBytes() {
        if (mapped == 0) {
            throw new IllegalStateException("not a host-visible buffer");
        }
        return MemoryUtil.memByteBuffer(mapped, Math.toIntExact(sizeBytes)).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Records a clear of the whole buffer. Run before the first read of freshly allocated VRAM. */
    public void zeroFill(VkCommandBuffer cmd) {
        VK13.vkCmdFillBuffer(cmd, buffer, 0L, sizeBytes, 0);
    }

    /** Frees the buffer and its sub-allocation. No submitted work may still read the buffer. */
    public void destroy(RtAllocator allocator) {
        Vma.vmaDestroyBuffer(allocator.vma(), buffer, allocation);
    }
}
