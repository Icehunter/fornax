package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code GpuDevice}'s private backend instance -- the Vulkan bring-up seam. Cast the
 * result to {@code com.mojang.blaze3d.vulkan.VulkanDevice} only after confirming the active
 * backend is Vulkan (see {@code VulkanComputeBackend.tryCreate}); on the GL backend this is a
 * different (GL) implementation of {@code GpuDeviceBackend} and the cast would throw.
 */
@Mixin(GpuDevice.class)
public interface GpuDeviceBackendAccessor {
    @Accessor("backend")
    GpuDeviceBackend fornax$backend();
}
