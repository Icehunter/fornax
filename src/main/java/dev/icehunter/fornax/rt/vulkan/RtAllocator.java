package dev.icehunter.fornax.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;

import java.util.Objects;

/**
 * A second VMA allocator on Blaze3D's own device, created with {@code
 * VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT}.
 *
 * <p>Blaze3D's allocator cannot serve the ray tiers: it was created without that flag, so memory it
 * hands out is not addressable, and an acceleration-structure build addresses everything it reads.
 * The alternative, one {@code vkAllocateMemory} per buffer, runs into {@code
 * maxMemoryAllocationCount} (4096 on NVIDIA) at four buffers a section. VMA sub-allocates, so a
 * thousand sections cost a handful of device allocations.
 *
 * <p>One per device, created on first use and kept for the device's lifetime, the same as Blaze3D's
 * own. Render-thread confined; the static holder exists so every ray-tier object on one device
 * shares the allocator rather than each opening its own.
 */
public final class RtAllocator {

    private static RtAllocator current;

    private final VulkanDevice device;
    private final long vma;

    private RtAllocator(VulkanDevice device, long vma) {
        this.device = device;
        this.vma = vma;
    }

    /** The allocator for {@code device}, creating it on the first call. */
    public static RtAllocator forDevice(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        if (current != null && current.device == device) {
            return current;
        }
        if (!VulkanRtSupport.isSupported()) {
            throw new IllegalStateException("the device was created without bufferDeviceAddress: "
                    + VulkanRtSupport.unavailableReason());
        }
        if (current != null) {
            // A new device means the old one is gone with everything allocated on it.
            Vma.vmaDestroyAllocator(current.vma);
            current = null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                    .set(device.instance().vkInstance(), device.vkDevice());
            VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .physicalDevice(device.vkDevice().getPhysicalDevice())
                    .device(device.vkDevice())
                    .instance(device.instance().vkInstance())
                    .pVulkanFunctions(functions)
                    // The instance was created for 1.2; a higher device version changes nothing VMA
                    // needs, and asking for what the instance has is what Blaze3D does for its own.
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2);
            PointerBuffer out = stack.mallocPointer(1);
            int result = Vma.vmaCreateAllocator(info, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateAllocator (ray tracing, device address) failed: " + result);
            }
            current = new RtAllocator(device, out.get(0));
            FornaxMod.LOGGER.info("[Fornax] Vulkan RT: created the device-address allocator");
            return current;
        }
    }

    public VulkanDevice device() {
        return device;
    }

    /** The {@code VmaAllocator} handle, for {@link RtBuffer}. */
    long vma() {
        return vma;
    }
}
