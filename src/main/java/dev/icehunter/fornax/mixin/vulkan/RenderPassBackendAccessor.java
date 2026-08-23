package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code RenderPass}'s private backend -- the first half of the seam
 * {@code ParticlePassRunner} uses to record a raw-Vulkan draw inside a render pass Blaze3D opened.
 * Cast the result to {@code com.mojang.blaze3d.vulkan.VulkanRenderPass} only after confirming the
 * active backend is Vulkan, exactly as {@link GpuDeviceBackendAccessor} documents for its own cast;
 * on the GL backend this is a different implementation of {@code RenderPassBackend}.
 *
 * <p>Why go through Blaze3D's render pass at all, when the pipeline itself is raw Vulkan: opening
 * the pass is what performs the color/depth image layout transitions and the {@code
 * vkCmdBeginRenderingKHR}/{@code vkCmdEndRenderingKHR} pair around the draw, plus the initial
 * {@code vkCmdSetViewport}/{@code vkCmdSetScissor}. Re-implementing that would mean re-implementing
 * Blaze3D's internal per-texture layout tracking, with no way to stay in sync with it. Only the
 * PIPELINE has to be raw (Blaze3D cannot express a vertex-stage SSBO); the render pass scope does
 * not.
 */
@Mixin(RenderPass.class)
public interface RenderPassBackendAccessor {
    @Accessor("backend")
    RenderPassBackend fornax$backend();
}
