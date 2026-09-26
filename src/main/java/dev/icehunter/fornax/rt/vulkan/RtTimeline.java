package dev.icehunter.fornax.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.nio.LongBuffer;

/**
 * One timeline semaphore for the ray-tracing tier: the graphics queue signals it when a frame's
 * mesh copies are recorded, the compute queue waits for that value, builds, and signals a later
 * value when the structure is complete. Values are handed out in order and never reused, so a
 * value passed is a value every earlier submission has passed too.
 *
 * <p>Render-thread confined.
 */
public final class RtTimeline implements AutoCloseable {
    private final VulkanDevice device;
    private final long semaphore;
    private long lastValue;

    private RtTimeline(VulkanDevice device, long semaphore) {
        this.device = device;
        this.semaphore = semaphore;
    }

    public static RtTimeline create(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer out = stack.mallocLong(1);
            int result = VK13.vkCreateSemaphore(device.vkDevice(), info, null, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSemaphore (ray tracing timeline) failed: " + result);
            }
            return new RtTimeline(device, out.get(0));
        }
    }

    public long semaphore() {
        return semaphore;
    }

    /** The next value to signal: strictly increasing, one per use. */
    public long allocateValue() {
        return ++lastValue;
    }

    public long lastValue() {
        return lastValue;
    }

    /** The highest value the GPU has signalled. Never waits. */
    public long signalledValue() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            int result = VK12.vkGetSemaphoreCounterValue(device.vkDevice(), semaphore, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkGetSemaphoreCounterValue failed: " + result);
            }
            return out.get(0);
        }
    }

    /** Host-waits for {@code value}; only for teardown, where the device is going idle anyway. */
    public void waitFor(long value, long timeoutNanos) {
        if (value <= 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo info = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .pSemaphores(stack.longs(semaphore))
                    .pValues(stack.longs(value));
            int result = VK12.vkWaitSemaphores(device.vkDevice(), info, timeoutNanos);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkWaitSemaphores (ray tracing timeline, value " + value + ") returned " + result);
            }
        }
    }

    @Override
    public void close() {
        VK13.vkDestroySemaphore(device.vkDevice(), semaphore, null);
    }
}
