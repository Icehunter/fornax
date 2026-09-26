package dev.icehunter.fornax.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.rt.vulkan.VulkanRtRequirements;
import dev.icehunter.fornax.rt.vulkan.VulkanRtSupport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Asks Blaze3D's Vulkan device for ray tracing at the one moment it can be asked: {@code
 * VulkanBackend}'s private static {@code createDevice(Collection, VulkanPhysicalDevice, Set)}.
 * That method receives the SAME live extension set and feature set its caller built from {@code
 * REQUIRED_DEVICE_EXTENSIONS} / {@code REQUIRED_DEVICE_FEATURES}, iterates the extensions into
 * {@code ppEnabledExtensionNames}, and writes every {@link VulkanFeature} into the {@code
 * VkPhysicalDeviceFeatures2} chain that becomes {@code VkDeviceCreateInfo.pNext}, all inside the
 * method. A HEAD inject that adds to both collections therefore lands before {@code vkCreateDevice}
 * and before LWJGL's {@code VkDevice} wrapper loads the per-extension entry points. Same seam and
 * same reasoning as {@link VulkanDeviceExtensionMixin}; the two coexist because each only appends.
 *
 * <p>What it adds, when {@link VulkanRtRequirements#decide} says the device can take them: the
 * three extensions, and three features expressed as Blaze3D's own records. {@code
 * bufferDeviceAddress} goes on the {@code VkPhysicalDeviceVulkan12Features} struct Blaze3D already
 * chains; {@code accelerationStructure} and {@code rayQuery} go on two structs Blaze3D does not
 * declare, which {@code VulkanPNextStruct.findOrCreateStructInPNextChain} appends on demand.
 * Feature support is queried the way Blaze3D queries its own: one {@code
 * vkGetPhysicalDeviceFeatures2} over a chain built from the same records.
 *
 * <p>Refusals are ordinary. On macOS this returns before any query, because Metal owns ray tracing
 * there and MoltenVK does not expose these extensions. On a driver that lacks an extension or a
 * feature, device creation proceeds byte-identically to vanilla. Either way the verdict, with its
 * reason, is recorded in {@link VulkanRtSupport} and logged once. With no pack active this is
 * inert: enabling a feature does no work until a dispatch uses it.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanDeviceRayTracingMixin {
    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
                    + "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void fornax$enableRayTracing(Collection<String> extensions,
            VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features,
            CallbackInfoReturnable<VkDevice> cir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        VulkanRtRequirements.Verdict verdict;
        if (os.contains("mac")) {
            verdict = VulkanRtRequirements.decide(true, 0, Set.of(), false, false, false);
        } else {
            int apiVersion = physicalDevice.vkPhysicalDeviceProperties().apiVersion();
            Set<String> missing = physicalDevice.getMissingExtensions(VulkanRtRequirements.REQUIRED_EXTENSIONS);

            VulkanFeature accelerationStructure = new VulkanFeature(
                    new VulkanPNextStruct(
                            KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF),
                    VulkanRtRequirements.ACCELERATION_STRUCTURE_FEATURE,
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
            VulkanFeature rayQuery = new VulkanFeature(
                    new VulkanPNextStruct(
                            KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF),
                    VulkanRtRequirements.RAY_QUERY_FEATURE,
                    VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
            // Blaze3D's own 1.2 struct: createDevice finds it already in the chain and sets one
            // more bit in it, next to the timelineSemaphore bit Blaze3D sets itself.
            VulkanFeature bufferDeviceAddress = new VulkanFeature(
                    VulkanBackend.VK12_FEATURES_STRUCT,
                    VulkanRtRequirements.BUFFER_DEVICE_ADDRESS_FEATURE,
                    VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);

            boolean accelerationSupported = false, rayQuerySupported = false, addressSupported = false;
            // Only a device that advertises the extensions can be asked about their features: a
            // feature struct for an unknown extension is undefined input to the query.
            if (missing.isEmpty()) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkPhysicalDeviceFeatures2 supported = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                    accelerationStructure.struct().findOrCreateStructInPNextChain(supported, stack);
                    rayQuery.struct().findOrCreateStructInPNextChain(supported, stack);
                    bufferDeviceAddress.struct().findOrCreateStructInPNextChain(supported, stack);
                    VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), supported);
                    accelerationSupported = accelerationStructure.get(supported);
                    rayQuerySupported = rayQuery.get(supported);
                    addressSupported = bufferDeviceAddress.get(supported);
                }
            }
            verdict = VulkanRtRequirements.decide(false, apiVersion, missing,
                    accelerationSupported, rayQuerySupported, addressSupported);
            if (verdict.enabled()) {
                extensions.addAll(VulkanRtRequirements.REQUIRED_EXTENSIONS);
                features.add(accelerationStructure);
                features.add(rayQuery);
                features.add(bufferDeviceAddress);
            }
        }
        VulkanRtSupport.recordDeviceVerdict(verdict);
        if (verdict.enabled()) {
            FornaxMod.LOGGER.info("[Fornax] Vulkan RT: enabled {} and the {}, {}, {} features on {}",
                    String.join(", ", VulkanRtRequirements.REQUIRED_EXTENSIONS),
                    VulkanRtRequirements.ACCELERATION_STRUCTURE_FEATURE,
                    VulkanRtRequirements.RAY_QUERY_FEATURE,
                    VulkanRtRequirements.BUFFER_DEVICE_ADDRESS_FEATURE,
                    physicalDevice.deviceName());
        } else {
            FornaxMod.LOGGER.info("[Fornax] Vulkan RT: not enabled: {}", verdict.reason());
        }
    }
}
