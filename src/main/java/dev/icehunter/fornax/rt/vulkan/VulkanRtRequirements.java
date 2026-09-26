package dev.icehunter.fornax.rt.vulkan;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What a Vulkan device must offer before this engine will ask it to trace rays, and the decision
 * itself, as a pure function of the facts a device reports.
 *
 * <p>Kept free of every LWJGL type on purpose: the decision is pinned by a truth table in a unit
 * test, and the names below are string literals rather than the binding's constants so that
 * deciding never initialises a Vulkan class. The mixin that owns the live device maps a verdict
 * onto Blaze3D's {@code VulkanFeature} records; this class never touches a handle.
 *
 * <p>Why each requirement:
 * <ul>
 *   <li>{@code VK_KHR_acceleration_structure} and {@code VK_KHR_ray_query}: the traversal itself,
 *       from a compute shader, which is the only shader stage this engine dispatches.
 *   <li>{@code VK_KHR_deferred_host_operations}: a hard dependency of the acceleration-structure
 *       extension, required to be enabled alongside it even when no host build is ever deferred.
 *   <li>{@code bufferDeviceAddress}: an acceleration-structure build addresses its vertex, index,
 *       instance and scratch buffers by device address, not by binding. Blaze3D does not enable
 *       this feature for its own use, so it is requested here.
 *   <li>Vulkan 1.2: ray query shaders are SPIR-V 1.4, core from 1.2. Blaze3D itself accepts a 1.1
 *       device, so the floor has to be raised here rather than assumed.
 * </ul>
 */
public final class VulkanRtRequirements {

    /** Extension names as the loader spells them; order is the order the reason lists them in. */
    public static final List<String> REQUIRED_EXTENSIONS = List.of(
            "VK_KHR_acceleration_structure",
            "VK_KHR_ray_query",
            "VK_KHR_deferred_host_operations");

    /** Vulkan spec member names, which is what a reader greps the headers for. */
    public static final String ACCELERATION_STRUCTURE_FEATURE = "accelerationStructure";
    public static final String RAY_QUERY_FEATURE = "rayQuery";
    public static final String BUFFER_DEVICE_ADDRESS_FEATURE = "bufferDeviceAddress";

    /** {@code VK_MAKE_API_VERSION(0, 1, 2, 0)}: variant in the top three bits, major << 22, minor << 12. */
    public static final int MINIMUM_API_VERSION = (1 << 22) | (2 << 12);

    private VulkanRtRequirements() {
    }

    /**
     * Enabled with no reason, or refused with one. The same shape as {@code RayReadiness}, and for
     * the same cause: a refusal that cannot say why is indistinguishable from a device that traces
     * and finds nothing.
     */
    public record Verdict(boolean enabled, String reason) {
        public Verdict {
            Objects.requireNonNull(reason, "reason");
            if (enabled && !reason.isEmpty()) {
                throw new IllegalArgumentException("an enabled verdict states no reason, got: " + reason);
            }
            if (!enabled && reason.isEmpty()) {
                throw new IllegalArgumentException("a refused verdict must say why");
            }
        }

        static Verdict refused(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * @param macOs                 whether the process runs on macOS, where Metal owns ray tracing
     *                              and MoltenVK never exposes these extensions
     * @param apiVersion            {@code VkPhysicalDeviceProperties.apiVersion}, packed
     * @param missingExtensions     the subset of {@link #REQUIRED_EXTENSIONS} the device does not
     *                              advertise, as {@code VulkanPhysicalDevice.getMissingExtensions}
     *                              reports it
     * @param accelerationStructure {@code VkPhysicalDeviceAccelerationStructureFeaturesKHR.accelerationStructure}
     * @param rayQuery              {@code VkPhysicalDeviceRayQueryFeaturesKHR.rayQuery}
     * @param bufferDeviceAddress   {@code VkPhysicalDeviceVulkan12Features.bufferDeviceAddress}
     */
    public static Verdict decide(boolean macOs, int apiVersion, Collection<String> missingExtensions,
            boolean accelerationStructure, boolean rayQuery, boolean bufferDeviceAddress) {
        Objects.requireNonNull(missingExtensions, "missing extensions");
        if (macOs) {
            return Verdict.refused("platform is macOS; Metal owns ray tracing there");
        }
        if (!atLeast(apiVersion, MINIMUM_API_VERSION)) {
            return Verdict.refused("device is Vulkan " + describe(apiVersion)
                    + ", ray query needs 1.2 or newer");
        }
        // Extensions before features: a driver without the extension also reports the feature
        // false, and naming the extension tells a reader to update the driver rather than to
        // suspect the feature query.
        if (!missingExtensions.isEmpty()) {
            List<String> missing = REQUIRED_EXTENSIONS.stream().filter(missingExtensions::contains).toList();
            return Verdict.refused("device does not advertise " + String.join(", ", missing));
        }
        List<String> unsupported = new java.util.ArrayList<>(3);
        if (!accelerationStructure) unsupported.add(ACCELERATION_STRUCTURE_FEATURE);
        if (!rayQuery) unsupported.add(RAY_QUERY_FEATURE);
        if (!bufferDeviceAddress) unsupported.add(BUFFER_DEVICE_ADDRESS_FEATURE);
        if (!unsupported.isEmpty()) {
            return Verdict.refused("device does not support the " + String.join(", ", unsupported)
                    + " feature" + (unsupported.size() > 1 ? "s" : ""));
        }
        return new Verdict(true, "");
    }

    /** Major.minor comparison only: the variant and patch fields say nothing about what is core. */
    static boolean atLeast(int apiVersion, int minimum) {
        return major(apiVersion) > major(minimum)
                || (major(apiVersion) == major(minimum) && minor(apiVersion) >= minor(minimum));
    }

    static String describe(int apiVersion) {
        return major(apiVersion) + "." + minor(apiVersion);
    }

    private static int major(int apiVersion) {
        return (apiVersion >>> 22) & 0x7F;
    }

    private static int minor(int apiVersion) {
        return (apiVersion >>> 12) & 0x3FF;
    }
}
