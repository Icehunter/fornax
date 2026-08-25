package dev.icehunter.fornax.util;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.util.List;
import java.util.OptionalLong;

/**
 * VRAM estimates for the SSAA settings UI and the paged block atlas's budget. blaze3d itself has
 * no VRAM query API, but the Vulkan backend it wraps does: {@link #detectedVramBytesFromDevice}
 * reads the physical device's memory heaps directly, accurate on every platform this engine
 * targets, including Apple Silicon under MoltenVK where OSHI ({@link #detectedVramBytes}, kept for
 * the GL-backend/pre-device-availability case) is known to report 0/unavailable. Callers MUST
 * treat an empty result as "unknown," never as "zero risk."
 */
public final class GpuMemoryEstimator {
    private static final int GBUFFER_BYTES_PER_PIXEL = 24; // normal(8) + albedo(4) + material(4) + motion(4) + depth(4)
    private static final int NATIVE_TARGET_BYTES_PER_PIXEL = 8; // RGBA8_UNORM color(4) + D32_FLOAT depth(4)

    private GpuMemoryEstimator() {
    }

    /** Empty if VRAM could not be determined on this platform -- do not assume 0 means "no VRAM." */
    public static OptionalLong detectedVramBytes() {
        List<GraphicsCard> cards = new SystemInfo().getHardware().getGraphicsCards();
        long vram = cards.stream().mapToLong(GraphicsCard::getVRam).filter(v -> v > 0).max().orElse(0L);

        return vram > 0 ? OptionalLong.of(vram) : OptionalLong.empty();
    }

    /**
     * Real device-local VRAM (or Apple Silicon's unified pool), summed across every
     * {@code VK_MEMORY_HEAP_DEVICE_LOCAL_BIT} heap the physical device reports. Empty on the GL
     * backend, when no device exists yet, or on any query failure. A conformant Vulkan
     * implementation always reports at least one device-local heap, so a genuinely absent result
     * here means the query itself could not run, not that the device has no VRAM.
     */
    public static OptionalLong detectedVramBytesFromDevice(GpuDevice device) {
        if (device == null) {
            return OptionalLong.empty();
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return OptionalLong.empty();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physicalDevice = vulkanDevice.vkDevice().getPhysicalDevice();
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);

            long total = 0;
            for (int i = 0; i < props.memoryHeapCount(); i++) {
                VkMemoryHeap heap = props.memoryHeaps(i);
                if ((heap.flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    total += heap.size();
                }
            }
            return total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.warn("[Fornax] Vulkan device-local VRAM query failed", e);
            return OptionalLong.empty();
        }
    }

    /** Estimated bytes this preset's scaled G-buffer + native target would need, at a given native resolution. */
    public static long estimateBytes(SsaaPreset preset, int nativeWidth, int nativeHeight) {
        long scaledPixels = Math.round(nativeWidth * nativeHeight * (double) preset.pixelCountMultiplier());
        long nativePixels = (long) nativeWidth * nativeHeight;

        return scaledPixels * GBUFFER_BYTES_PER_PIXEL + nativePixels * NATIVE_TARGET_BYTES_PER_PIXEL;
    }
}
