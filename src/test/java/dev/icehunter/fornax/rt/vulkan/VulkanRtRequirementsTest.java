package dev.icehunter.fornax.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VK12;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VulkanRtRequirements#decide} is a pure function of device facts, so its whole truth table
 * is pinned here without a device. The reason each refusal carries matters most: a device that
 * installs no tier looks the same as one that answers and finds nothing, and the log line is the
 * only place the difference shows.
 */
class VulkanRtRequirementsTest {

    // VK_MAKE_API_VERSION(0, 1, 2, 0): variant 0, major 1 << 22, minor 2 << 12, patch 0.
    private static final int VULKAN_1_2 = (1 << 22) | (2 << 12);
    private static final int VULKAN_1_1 = (1 << 22) | (1 << 12);
    // A 1.2 device reporting a patch level: the minimum must compare major.minor, not the raw int.
    private static final int VULKAN_1_2_PATCH_200 = VULKAN_1_2 | 200;
    private static final int VULKAN_1_4 = (1 << 22) | (4 << 12);

    @Test
    void theThreeExtensionNamesAreTheOnesTheLoaderSpells() {
        // Literal strings in the pure class, so no LWJGL class has to initialise to decide;
        // pinned against the binding's constants so a typo cannot pass as "not advertised".
        assertEquals(List.of(
                KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME,
                KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME),
                VulkanRtRequirements.REQUIRED_EXTENSIONS);
    }

    @Test
    void minimumApiVersionIsVulkan12BecauseRayQueryNeedsSpirv14() {
        assertEquals(VK12.VK_API_VERSION_1_2, VulkanRtRequirements.MINIMUM_API_VERSION);
        assertEquals(VULKAN_1_2, VulkanRtRequirements.MINIMUM_API_VERSION);
    }

    @Test
    void featureNamesMatchTheVulkanSpecFieldNames() {
        // The names VulkanFeature records carry and the log prints: the spec's member names, so
        // a reader can grep the Vulkan headers for them.
        assertEquals("accelerationStructure", VulkanRtRequirements.ACCELERATION_STRUCTURE_FEATURE);
        assertEquals("rayQuery", VulkanRtRequirements.RAY_QUERY_FEATURE);
        assertEquals("bufferDeviceAddress", VulkanRtRequirements.BUFFER_DEVICE_ADDRESS_FEATURE);
    }

    @Test
    void aCapableNonMacDeviceIsEnabledWithAnEmptyReason() {
        VulkanRtRequirements.Verdict verdict =
                VulkanRtRequirements.decide(false, VULKAN_1_4, Set.of(), true, true, true);
        assertTrue(verdict.enabled());
        assertEquals("", verdict.reason());
    }

    @Test
    void patchLevelDoesNotDisqualifyAOneTwoDevice() {
        assertTrue(VulkanRtRequirements.decide(false, VULKAN_1_2_PATCH_200, Set.of(), true, true, true)
                .enabled());
    }

    @Test
    void macIsRefusedBeforeAnyDeviceFactIsConsulted() {
        // Metal owns ray tracing there; even a device that advertised everything is declined.
        VulkanRtRequirements.Verdict verdict =
                VulkanRtRequirements.decide(true, VULKAN_1_4, Set.of(), true, true, true);
        assertFalse(verdict.enabled());
        assertTrue(verdict.reason().contains("macOS"), verdict.reason());
    }

    @Test
    void aOneOneDeviceIsRefusedNamingTheVersionFloor() {
        VulkanRtRequirements.Verdict verdict =
                VulkanRtRequirements.decide(false, VULKAN_1_1, Set.of(), true, true, true);
        assertFalse(verdict.enabled());
        assertTrue(verdict.reason().contains("1.2"), verdict.reason());
        assertTrue(verdict.reason().contains("1.1"), "the reason names what the device reported: " + verdict.reason());
    }

    @Test
    void aMissingExtensionIsRefusedNamingEveryMissingOne() {
        VulkanRtRequirements.Verdict verdict = VulkanRtRequirements.decide(false, VULKAN_1_4,
                Set.of("VK_KHR_ray_query", "VK_KHR_deferred_host_operations"), true, true, true);
        assertFalse(verdict.enabled());
        assertTrue(verdict.reason().contains("VK_KHR_ray_query"), verdict.reason());
        assertTrue(verdict.reason().contains("VK_KHR_deferred_host_operations"), verdict.reason());
        assertFalse(verdict.reason().contains("VK_KHR_acceleration_structure"),
                "an advertised extension is not listed as missing: " + verdict.reason());
    }

    @Test
    void anUnsupportedFeatureIsRefusedNamingIt() {
        assertTrue(VulkanRtRequirements.decide(false, VULKAN_1_4, Set.of(), false, true, true)
                .reason().contains("accelerationStructure"));
        assertTrue(VulkanRtRequirements.decide(false, VULKAN_1_4, Set.of(), true, false, true)
                .reason().contains("rayQuery"));
        assertTrue(VulkanRtRequirements.decide(false, VULKAN_1_4, Set.of(), true, true, false)
                .reason().contains("bufferDeviceAddress"));
    }

    @Test
    void extensionsAreCheckedBeforeFeaturesSoTheReasonNamesTheEarlierGap() {
        // A driver that lacks the extension also reports the feature false. Naming the extension
        // tells a reader to update the driver rather than suspect the feature query.
        VulkanRtRequirements.Verdict verdict = VulkanRtRequirements.decide(false, VULKAN_1_4,
                Set.of("VK_KHR_acceleration_structure"), false, false, true);
        assertTrue(verdict.reason().contains("VK_KHR_acceleration_structure"), verdict.reason());
        assertFalse(verdict.reason().contains("feature"), verdict.reason());
    }

    @Test
    void aVerdictCannotBeEnabledWithAReasonOrRefusedWithout() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VulkanRtRequirements.Verdict(true, "but"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VulkanRtRequirements.Verdict(false, ""));
    }
}
