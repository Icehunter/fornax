package dev.icehunter.fornax.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code detectedVramBytesFromDevice} needs a live Vulkan physical device to exercise directly,
 * the same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins
 * that it queries real device-local heaps rather than falling back to an OS-level approximation,
 * and that a null device or a query failure returns empty rather than throwing or reporting zero
 * as "no VRAM."
 */
class GpuMemoryEstimatorContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/util/GpuMemoryEstimator.java");

    @Test
    void detectedVramBytesFromDeviceSumsOnlyDeviceLocalHeaps() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static OptionalLong detectedVramBytesFromDevice(");
        assertTrue(methodStart >= 0, "detectedVramBytesFromDevice must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("if (device == null)"),
                "a null device must not reach the Vulkan call at all");
        assertTrue(method.contains("instanceof VulkanDevice vulkanDevice"),
                "must narrow to the Vulkan backend before touching any Vulkan API");
        assertTrue(method.contains("VK_MEMORY_HEAP_DEVICE_LOCAL_BIT"),
                "must filter to device-local heaps, not every heap the device reports");
        assertTrue(method.contains("catch (RuntimeException e)"),
                "a query failure must return empty, not propagate and abort the reload");
    }
}
