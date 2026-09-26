package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RayTracingMode;

import java.util.Objects;

/**
 * Live availability of the Vulkan ray-tracing backend: the device verdict crossed with the user's
 * setting.
 *
 * <p>Unlike {@code MetalRtSupport}, there is no probe here. The only moment a Vulkan device's
 * extensions and features can be chosen is {@code vkCreateDevice}, so the decision is made there,
 * once, by {@code VulkanDeviceRayTracingMixin}, and recorded through {@link #recordDeviceVerdict}.
 * Everything else in the engine asks this class. Before any device exists (the GL backend, or a
 * unit test) nothing is supported, and the reason says that rather than blaming the driver.
 *
 * <p>The {@link RayTracingMode} setting is read fresh on every call, so flipping it takes effect
 * without a device recreation, the same as the Metal side.
 */
public final class VulkanRtSupport {

    /** Set exactly once per process, by the device-creation mixin. Null until then. */
    private static volatile VulkanRtRequirements.Verdict deviceVerdict;

    private VulkanRtSupport() {
    }

    /**
     * Records what device creation decided. Blaze3D creates one device per process, so a second
     * recording means the mixin fired twice, which is a fault to stop on rather than a field to
     * update: the first device's extensions are the ones every later handle was created against.
     */
    public static void recordDeviceVerdict(VulkanRtRequirements.Verdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        synchronized (VulkanRtSupport.class) {
            if (deviceVerdict != null) {
                throw new IllegalStateException("Vulkan RT device verdict already recorded as "
                        + deviceVerdict + "; refusing to replace it with " + verdict);
            }
            deviceVerdict = verdict;
        }
    }

    /** Whether the live device was created with the ray-tracing extensions and features enabled. */
    public static boolean isSupported() {
        VulkanRtRequirements.Verdict verdict = deviceVerdict;
        return verdict != null && verdict.enabled();
    }

    /** Whether the backend may run: the device verdict plus the live setting. */
    public static boolean isAvailable() {
        return allowedBy(isSupported(), FornaxConfig.get().rayTracing);
    }

    /** Pack dispatch gate. No subscription means no backend work, whatever the device offers. */
    public static boolean isAvailableFor(boolean packSubscribed) {
        return packSubscribed && isAvailable();
    }

    /** Why {@link #isAvailable()} is false, or null when it is true. */
    public static String unavailableReason() {
        // The setting is checked before any device fact: an explicit Off is the reason on its own
        // terms, even on a device whose creation also refused.
        if (FornaxConfig.get().rayTracing == RayTracingMode.OFF) {
            return "ray tracing backend is None";
        }
        VulkanRtRequirements.Verdict verdict = deviceVerdict;
        if (verdict == null) {
            return "no Vulkan device has been created";
        }
        return verdict.enabled() ? null : verdict.reason();
    }

    /**
     * Automatic and explicit selection both follow the device; None always disables. There is no
     * device-family axis here as there is on Metal: a Vulkan device either enabled the features at
     * creation or it did not.
     */
    public static boolean allowedBy(boolean supported, RayTracingMode mode) {
        return switch (mode) {
            case OFF -> false;
            case AUTO, FORCE -> supported;
        };
    }

    /** Test seam: forgets the recorded verdict so each test starts from "no device". */
    static void reset() {
        synchronized (VulkanRtSupport.class) {
            deviceVerdict = null;
        }
    }
}
