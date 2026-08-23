package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@code VkCommandBuffer} a {@code VulkanRenderPass} records into -- the second half of
 * the seam described on {@link RenderPassBackendAccessor}. The field is {@code private final} and
 * assigned in the constructor from the encoder's persistent graphics command buffer, so the handle
 * is stable for the pass's whole lifetime and valid to record into between {@code createRenderPass}
 * and {@code close()}.
 *
 * <p>The class has a {@code private VkCommandBuffer commandBuffer()} method as well as the field;
 * this accessor deliberately targets the FIELD, because a private method is not reachable by an
 * {@code @Accessor} and an {@code @Invoker} on it would buy nothing -- the method is a plain getter
 * (verified in the deobf 26.2 jar: {@code aload_0; getfield commandBuffer; areturn}).
 */
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassCommandBufferAccessor {
    @Accessor("commandBuffer")
    VkCommandBuffer fornax$commandBuffer();
}
