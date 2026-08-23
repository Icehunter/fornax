package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.icehunter.fornax.FornaxMod;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Appends {@code VK_EXT_metal_objects} to the device-extension set Blaze3D passes to {@code
 * vkCreateDevice} (MetalFX spike M1 -- the Vulkan-to-Metal texture-interop prerequisite; see the
 * approved plan's Q1). Injection point: {@code VulkanBackend}'s private static {@code
 * createDevice(Collection, VulkanPhysicalDevice, Set)} receives the SAME live mutable {@code
 * HashSet} its public caller built from {@code REQUIRED_DEVICE_EXTENSIONS} and iterates it
 * directly into {@code ppEnabledExtensionNames} -- so a HEAD inject mutating the collection lands
 * strictly before {@code vkCreateDevice} runs AND before the LWJGL {@code VkDevice} wrapper loads
 * its per-extension capabilities (both happen inside the target method), which is what makes
 * {@code EXTMetalObjects.vkExportMetalObjectsEXT} callable on the resulting device.
 *
 * <p>Guarded twice: platform (macOS/aarch64 -- the only place MoltenVK, and therefore this
 * extension, exists) and an advertise-check via {@code VulkanPhysicalDevice.hasDeviceExtension}
 * (which wraps the {@code vkEnumerateDeviceExtensionProperties} results it already fetched at
 * construction -- no second enumeration). On any other platform or an older MoltenVK this method
 * is a silent no-op and device creation proceeds byte-identically to vanilla. Enabling the
 * extension is inert until something calls an interop entry point, so this cannot change rendering
 * behavior on its own.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanDeviceExtensionMixin {
    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
                    + "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void fornax$enableMetalObjectsExtension(Collection<String> extensions,
            VulkanPhysicalDevice physicalDevice, Set<?> features,
            CallbackInfoReturnable<VkDevice> cir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") || !arch.equals("aarch64")) {
            return;
        }
        String name = EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME;
        if (physicalDevice.hasDeviceExtension(name)) {
            extensions.add(name);
            FornaxMod.LOGGER.info("[Fornax] Enabled device extension {} for Metal interop", name);
        } else {
            FornaxMod.LOGGER.info("[Fornax] {} not advertised by this driver -- Metal interop unavailable", name);
        }
    }
}
