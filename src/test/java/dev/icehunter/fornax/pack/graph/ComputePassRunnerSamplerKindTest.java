package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.icehunter.fornax.pack.graph.ComputePassRunner.InputSamplerKind.NEAREST_CLAMP;
import static dev.icehunter.fornax.pack.graph.ComputePassRunner.InputSamplerKind.PACK_TEXTURE_REPEAT;
import static dev.icehunter.fornax.pack.graph.ComputePassRunner.InputSamplerKind.PACK_TEXTURE_REPEAT_MIPPED;
import static com.mojang.blaze3d.textures.FilterMode.LINEAR;
import static com.mojang.blaze3d.textures.FilterMode.NEAREST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputePassRunnerSamplerKindTest {

    @Test
    void declaredVolumeTexturesRepeatWithLinearFilteringAndNoMipSampling() {
        ComputePassRunner.InputSamplerKind kind =
                ComputePassRunner.samplerKindFor("cloudDensity", true, true);

        assertEquals(PACK_TEXTURE_REPEAT, kind);
        assertEquals(LINEAR, kind.filter());
        assertTrue(kind.repeat());
        assertFalse(kind.mipmapped());
    }

    @Test
    void graphTargetsAndBuiltinsKeepNearestClampedSamplingInComputePasses() {
        ComputePassRunner.InputSamplerKind kind =
                ComputePassRunner.samplerKindFor("sceneHdr", false, false);

        assertEquals(NEAREST_CLAMP, kind);
        assertEquals(NEAREST, kind.filter());
        assertFalse(kind.repeat());
        assertFalse(kind.mipmapped());
    }

    @Test
    void twoDimensionalPackTexturesKeepTheirUploadedMipChainAvailable() {
        ComputePassRunner.InputSamplerKind kind =
                ComputePassRunner.samplerKindFor("caustics", true, false);

        assertEquals(PACK_TEXTURE_REPEAT_MIPPED, kind);
        assertEquals(LINEAR, kind.filter());
        assertTrue(kind.repeat());
        assertTrue(kind.mipmapped());
    }

    @Test
    void engineBuiltinWinsOverACollidingPackTextureDeclaration() {
        assertEquals(NEAREST_CLAMP,
                ComputePassRunner.samplerKindFor("builtin.gNormal", true, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sunShadowMap", "sunEntityShadowMap"})
    void comparisonShadowAliasesRequireHardwareDepthComparison(String ref) {
        assertEquals("SHADOW_COMPARISON",
                ComputePassRunner.samplerKindFor(ref, false, false).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sunShadowMap", "sunEntityShadowMap"})
    void engineComparisonAliasWinsOverACollidingPackTextureDeclaration(String ref) {
        assertEquals("SHADOW_COMPARISON",
                ComputePassRunner.samplerKindFor(ref, true, true).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sunShadowMapRaw", "sunEntityShadowMapRaw"})
    void rawShadowAliasesKeepUncomparedNearestDepthEvenWithACollidingPackTexture(String ref) {
        assertEquals(NEAREST_CLAMP, ComputePassRunner.samplerKindFor(ref, false, false));
        assertEquals(NEAREST_CLAMP, ComputePassRunner.samplerKindFor(ref, true, true));
    }

    /** Descriptor updates require a live Vulkan device. This source contract pins the path from
     * the classified input to the bound native sampler, including a loud unavailable-sampler
     * failure. It does not prove driver execution, shadow contents or queue synchronization. */
    @Test
    void comparisonDescriptorBindsTheHardwareSamplerWithoutAnOrdinarySamplerFallback() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int start = source.indexOf("InputSamplerKind samplerKind = samplerKinds.get(i);");
        int end = source.indexOf(".sampler(sampler.vkSampler())", start);
        assertTrue(start >= 0 && end > start, "sampled descriptor must bind its selected native sampler");
        String binding = source.substring(start, end);
        assertTrue(binding.contains("samplerKind == InputSamplerKind.SHADOW_COMPARISON"),
                "classification must select a comparison-specific descriptor path");
        assertTrue(binding.contains("ShadowComparisonSampler.get()"),
                "the comparison path must obtain the engine's compare-enabled sampler");
        assertTrue(binding.matches("(?s).*if \\(!\\(comparisonSampler instanceof VulkanGpuSampler vulkanComparison\\)\\) \\{\\s*throw new IllegalStateException\\(.*"),
                "missing or incompatible comparison samplers must fail instead of using ordinary sampling");
        assertTrue(binding.contains("sampler = vulkanComparison;"),
                "the descriptor must receive the checked comparison sampler");
        assertTrue(binding.contains("spec.name()") && binding.contains("+ name +"),
                "an unavailable comparison sampler must name the pass and input");
    }

}
