package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.icehunter.fornax.pack.graph.FornaxTextureUsage;
import org.lwjgl.vulkan.VK13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds Fornax's graph-only storage bit to Mojang's Vulkan image-usage translation. */
@Mixin(VulkanConst.class)
abstract class VulkanConstStorageTextureMixin {
    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void fornax$addStorageUsage(int usage, GpuFormat format,
                                               CallbackInfoReturnable<Integer> cir) {
        if ((usage & FornaxTextureUsage.STORAGE) != 0) {
            cir.setReturnValue(cir.getReturnValueI() | VK13.VK_IMAGE_USAGE_STORAGE_BIT);
        }
    }
}
