package dev.icehunter.fornax.pass.shadow;

import dev.icehunter.fornax.pipeline.CapturedSamplerState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Constructing the sampler requires a live Vulkan device and applied Minecraft mixins. Reflection
 * pins the custom sampler's own capture provider; source contracts pin its state to the actual
 * native creation call. These checks do not exercise driver creation or live capture replay. */
class ShadowComparisonSamplerCaptureTest {
    @Test
    void comparisonSamplerOwnsItsNativeCaptureStateInsteadOfInheritingTheDiscardedSamplerState() {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(
                "dev.icehunter.fornax.pass.shadow.ShadowComparisonSampler$ComparisonSampler"));
        assertTrue(CapturedSamplerState.class.isAssignableFrom(type),
                "the custom native handle needs an explicit capture-state provider");
        var method = assertDoesNotThrow(() -> type.getDeclaredMethod("fornax$samplerState"),
                "capture must not inherit the superclass's discarded ordinary sampler state");
        assertEquals(type, method.getDeclaringClass());
        assertEquals(Map.class, method.getReturnType());
    }

    @Test
    void capturedStateComesFromTheCreateInfoUsedForTheBoundComparisonHandle() throws IOException {
        String source = source();
        int create = source.indexOf("createComparisonSampler(VulkanDevice device)");
        assertTrue(create >= 0);
        String creation = source.substring(create);
        int snapshot = creation.indexOf("comparisonSamplerState = CaptureSamplerState.snapshot(info);");
        int nativeCreate = creation.indexOf("vkCreateSampler(vkDevice, info, null, out)");
        assertTrue(snapshot >= 0 && nativeCreate > snapshot,
                "snapshot the actual comparison create info before its stack storage expires");
        assertTrue(source.matches("(?s).*public Map<String, Object> fornax\\$samplerState\\(\\) \\{\\s*return comparisonSamplerState;\\s*}.*"),
                "capture must return the state belonging to the overridden native handle");
    }

    @Test
    void captureStateStaysEmptyUnlessCaptureWasConfiguredAtStartup() throws IOException {
        String source = source();
        assertTrue(source.contains("private Map<String, Object> comparisonSamplerState = Map.of();"),
                "uncaptured comparison state must be empty rather than inherited ordinary state");
        assertTrue(source.matches("(?s).*if \\(CaptureBufferUsage.enabled\\(\\)\\) \\{\\s*comparisonSamplerState = CaptureSamplerState.snapshot\\(info\\);\\s*}.*"),
                "comparison metadata follows the same startup allocation gate as ordinary samplers");
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pass/shadow/ShadowComparisonSampler.java"));
    }
}
