package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RayTracingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Vulkan backend has no probe of its own: the device-creation mixin decides once, at {@code
 * vkCreateDevice} time, and records the verdict here. Pinned is the setting-over-verdict policy,
 * the same one {@code MetalRtSupportTest} pins, plus the one state the Metal side never has, "no
 * verdict yet": a GL backend, or a unit test, never creates a Vulkan device.
 */
class VulkanRtSupportTest {

    @AfterEach
    void forgetTheVerdict() {
        VulkanRtSupport.reset();
    }

    @Test
    void offIsNeverAllowedRegardlessOfTheDevice() {
        assertFalse(VulkanRtSupport.allowedBy(true, RayTracingMode.OFF));
        assertFalse(VulkanRtSupport.allowedBy(false, RayTracingMode.OFF));
    }

    @Test
    void automaticAndForceBothFollowTheDeviceVerdict() {
        assertTrue(VulkanRtSupport.allowedBy(true, RayTracingMode.AUTO));
        assertFalse(VulkanRtSupport.allowedBy(false, RayTracingMode.AUTO));
        assertTrue(VulkanRtSupport.allowedBy(true, RayTracingMode.FORCE));
        assertFalse(VulkanRtSupport.allowedBy(false, RayTracingMode.FORCE));
    }

    @Test
    void withoutADeviceVerdictNothingIsSupportedAndTheReasonSaysSo() {
        RayTracingMode saved = FornaxConfig.get().rayTracing;
        try {
            FornaxConfig.get().rayTracing = RayTracingMode.AUTO;
            assertFalse(VulkanRtSupport.isSupported());
            assertFalse(VulkanRtSupport.isAvailable());
            assertEquals("no Vulkan device has been created", VulkanRtSupport.unavailableReason());
        } finally {
            FornaxConfig.get().rayTracing = saved;
        }
    }

    @Test
    void anEnabledVerdictDrivesIsAvailableThroughTheLiveSetting() {
        RayTracingMode saved = FornaxConfig.get().rayTracing;
        try {
            VulkanRtSupport.recordDeviceVerdict(new VulkanRtRequirements.Verdict(true, ""));
            assertTrue(VulkanRtSupport.isSupported());

            FornaxConfig.get().rayTracing = RayTracingMode.OFF;
            assertFalse(VulkanRtSupport.isAvailable());
            assertEquals("ray tracing backend is None", VulkanRtSupport.unavailableReason(),
                    "an explicit Off is reported on its own terms, ahead of any device fact");

            FornaxConfig.get().rayTracing = RayTracingMode.FORCE;
            assertTrue(VulkanRtSupport.isAvailable());

            FornaxConfig.get().rayTracing = RayTracingMode.AUTO;
            assertTrue(VulkanRtSupport.isAvailable());
            assertNull(VulkanRtSupport.unavailableReason());
        } finally {
            FornaxConfig.get().rayTracing = saved;
        }
    }

    @Test
    void aRefusedVerdictCarriesItsOwnReasonThroughUnavailableReason() {
        RayTracingMode saved = FornaxConfig.get().rayTracing;
        try {
            FornaxConfig.get().rayTracing = RayTracingMode.FORCE;
            VulkanRtSupport.recordDeviceVerdict(
                    new VulkanRtRequirements.Verdict(false, "device does not advertise VK_KHR_ray_query"));
            assertFalse(VulkanRtSupport.isSupported());
            assertFalse(VulkanRtSupport.isAvailable(), "FORCE never overrides a missing capability");
            assertEquals("device does not advertise VK_KHR_ray_query", VulkanRtSupport.unavailableReason());
        } finally {
            FornaxConfig.get().rayTracing = saved;
        }
    }

    @Test
    void noPackSubscriptionMeansNoDispatchForAnyBackendSelection() {
        RayTracingMode saved = FornaxConfig.get().rayTracing;
        try {
            VulkanRtSupport.recordDeviceVerdict(new VulkanRtRequirements.Verdict(true, ""));
            for (RayTracingMode mode : RayTracingMode.values()) {
                FornaxConfig.get().rayTracing = mode;
                assertFalse(VulkanRtSupport.isAvailableFor(false));
            }
        } finally {
            FornaxConfig.get().rayTracing = saved;
        }
    }

    @Test
    void theVerdictIsRecordedOnceAndASecondRecordingIsRefused() {
        // Blaze3D creates one device per process. A second recording means the mixin fired
        // twice, which stops the run rather than updating a field.
        VulkanRtSupport.recordDeviceVerdict(new VulkanRtRequirements.Verdict(true, ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> VulkanRtSupport.recordDeviceVerdict(new VulkanRtRequirements.Verdict(false, "again")));
        assertTrue(VulkanRtSupport.isSupported(), "the first verdict stands");
    }
}
