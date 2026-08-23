package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.AnalyticLightListBuffer;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightListStrideContractTest {

    @Test
    void rejectsAShaderMirroringAStaleMaxLights() {
        int stale = AnalyticLightListBuffer.MAX_LIGHTS - 64;
        Map<String, String> sources = Map.of(
                "shaders/compute/light_list_build.comp", "const uint MAX_LIGHTS = " + stale + "u;\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentMaxLights() {
        Map<String, String> sources = Map.of("shaders/compute/light_list_build.comp",
                "const uint MAX_LIGHTS = " + AnalyticLightListBuffer.MAX_LIGHTS + "u;\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void rejectsAShaderMirroringAStaleWordsPerLight() {
        int stale = AnalyticLightListBuffer.WORDS_PER_LIGHT + 2;
        Map<String, String> sources = Map.of(
                "shaders/compute/light_list_build.comp", "const uint WORDS_PER_LIGHT = " + stale + "u;\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentWordsPerLight() {
        Map<String, String> sources = Map.of("shaders/compute/light_list_build.comp",
                "const uint WORDS_PER_LIGHT = " + AnalyticLightListBuffer.WORDS_PER_LIGHT + "u;\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void rejectsAShaderMirroringAStaleSummaryHasEmitter() {
        Map<String, String> sources = Map.of(
                "shaders/compute/light_list_build.comp", "const uint SUMMARY_HAS_EMITTER = 0x4u;\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(sources));
        assertTrue(error.getMessage().contains("0x4u"),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentSummaryHasEmitter() {
        Map<String, String> sources = Map.of("shaders/compute/light_list_build.comp",
                "const uint SUMMARY_HAS_EMITTER = 0x"
                        + Integer.toHexString(BrickGridUpload.SUMMARY_HAS_EMITTER) + "u;\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void rejectsAShaderMirroringAStaleSummaryPending() {
        Map<String, String> sources = Map.of(
                "shaders/compute/light_list_build.comp", "const uint SUMMARY_PENDING = 0x40000000u;\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(sources));
        assertTrue(error.getMessage().contains("0x40000000u"),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentSummaryPending() {
        Map<String, String> sources = Map.of("shaders/compute/light_list_build.comp",
                "const uint SUMMARY_PENDING = 0x"
                        + Integer.toHexString(BrickGridUpload.SUMMARY_PENDING) + "u;\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void rejectsTheAnalyticLightsCompositePassMirroringAStaleMaxLights() {
        // direct_light_analytic.fsh (analytic-lights milestone, M2) is a SECOND, independent hand-mirror
        // of AnalyticLightListBuffer.MAX_LIGHTS/WORDS_PER_LIGHT beyond light_list_build.comp's own copy
        // (see that shader's own comment: it reads the SAME buffer a compute pass wrote, in a different
        // file, so it needs its own copy of the capacity constants to bounds-check its light-index
        // arithmetic). This contract is file-name-agnostic (matches ANY pack source), so it already
        // covers a .fsh consumer with zero new Java code -- this test documents that coverage explicitly
        // rather than leaving it merely implied by the generic-scan tests above.
        int stale = AnalyticLightListBuffer.MAX_LIGHTS - 64;
        Map<String, String> sources = Map.of(
                "shaders/post/direct_light_analytic.fsh", "const uint MAX_LIGHTS = " + stale + "u;\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsTheAnalyticLightsCompositePassMirroringCurrentCapacityConstants() {
        Map<String, String> sources = Map.of("shaders/post/direct_light_analytic.fsh",
                "const uint MAX_LIGHTS = " + AnalyticLightListBuffer.MAX_LIGHTS + "u;\n"
                        + "const uint WORDS_PER_LIGHT = " + AnalyticLightListBuffer.WORDS_PER_LIGHT + "u;\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void ignoresShadersThatDoNotTouchAnyOfTheseConstants() {
        Map<String, String> sources = Map.of(
                "shaders/post/ssao.fsh", "const int SAMPLES = 16;\nvoid main() {}\n");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));
    }

    @Test
    void toleratesTheWhitespaceVariationsRealMirrorsUse() {
        int maxLights = AnalyticLightListBuffer.MAX_LIGHTS;
        Map<String, String> sources = Map.of(
                "a.comp", "const uint MAX_LIGHTS = " + maxLights + "u;",
                "b.comp", "const uint MAX_LIGHTS = " + maxLights + "u;    // == AnalyticLightListBuffer",
                "c.comp", "const  uint   MAX_LIGHTS=" + maxLights + "u ;");
        assertDoesNotThrow(() -> LightListStrideContract.validate(sources));

        Map<String, String> stale = Map.of("c.comp", "const  uint   MAX_LIGHTS=" + (maxLights - 64) + "u ;");
        assertThrows(FornaxPackError.class, () -> LightListStrideContract.validate(stale));
    }
}
