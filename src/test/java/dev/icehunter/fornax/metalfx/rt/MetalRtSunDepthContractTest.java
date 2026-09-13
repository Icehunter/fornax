package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Load and lifecycle contracts are source assertions because resolving views needs a live Vulkan device. */
class MetalRtSunDepthContractTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/" + path));
    }
    @Test void sunDepthIsDeclaredResolvedAndZeroInitializedAtShadowResolution() throws Exception {
        String result = source("java/dev/icehunter/fornax/pass/shadow/RtShadowResult.java");
        assertTrue(result.contains("\"rtSunDepth\""));
        assertTrue(result.contains("GpuFormat.RGBA32_FLOAT"));
        assertTrue(result.contains("clearColorTexture(nextDepthTexture, new Vector4f(0.0f"));
        assertTrue(source("java/dev/icehunter/fornax/pass/shadow/ShadowMapManager.java").contains("RtShadowResult.ensureSunSize(resolution)"));
        assertTrue(source("java/dev/icehunter/fornax/pack/graph/GraphInputResolver.java").contains("case RtShadowResult.DEPTH_TARGET -> RtShadowResult.getDepthView()"));
    }
    @Test void optionalSunDepthAllocatesOnlyForADeclaredConsumer() throws Exception {
        String manager = source("java/dev/icehunter/fornax/pass/shadow/ShadowMapManager.java");
        assertTrue(manager.contains("if (RtShadowResult.sunDepthRequested()) RtShadowResult.ensureSunSize(resolution)"),
                "A pack without rtSunDepth must not allocate a full RGBA32F shadow map");
        dev.icehunter.fornax.pass.shadow.RtShadowResult.setRequestedInputs(java.util.stream.Stream.of("sunShadowMap"));
        org.junit.jupiter.api.Assertions.assertFalse(dev.icehunter.fornax.pass.shadow.RtShadowResult.sunDepthRequested());
        dev.icehunter.fornax.pass.shadow.RtShadowResult.setRequestedInputs(java.util.stream.Stream.of("rtSunDepth"));
        assertTrue(dev.icehunter.fornax.pass.shadow.RtShadowResult.sunDepthRequested());
        dev.icehunter.fornax.pass.shadow.RtShadowResult.setRequestedInputs(java.util.stream.Stream.empty());
    }
    @Test void sunKernelUsesCurrentProjectionAndRayLocalReadiness() throws Exception {
        String pass = source("java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("ShadowFrameState.current()"));
        assertTrue(pass.contains("ShadowFrameState.currentBias()"));
        assertTrue(pass.contains("MetalRtGeometry.sectionReadiness(populatedSlots)"));
        String kernel = source("resources/assets/fornax/shaders_engine/rt_sun_depth.metal");
        assertTrue(kernel.contains("kernel void rt_sun_depth"));
        assertTrue(kernel.contains("rtRaySectionsReady"));
    }
    @Test void atlasAnimationCopiesEveryFrameAndCaptureClearCannotCertifyStaleAlpha() throws Exception {
        String pass = source("java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("atlasIn != null && BlockAtlasView.texture() != null"));
        int copyStart = pass.indexOf("private static void copyBlockAtlasIfChanged");
        int copyEnd = pass.indexOf("private static void ensureCutoutFallbacks", copyStart);
        assertTrue(!pass.substring(copyStart, copyEnd).contains("generation == lastAtlasGeneration"));
        assertTrue(pass.contains("if (!legacyTrace && maskValid) clearLegacyRtTargetsToInvalid()"));
    }
    @Test void rawEntityAliasIsDeclarationBasedAndLegacyTraceIsOptional() throws Exception {
        String manager = source("java/dev/icehunter/fornax/pass/shadow/ShadowMapManager.java");
        assertTrue(manager.contains("\"sunEntityShadowMapRaw\""));
        String graph = source("java/dev/icehunter/fornax/pack/graph/GraphRunner.java");
        assertTrue(graph.contains("ShadowMapManager.ENTITY_RAW_TARGET"));
        assertTrue(graph.contains("RtShadowResult.setRequestedInputs"));
        assertTrue(source("java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java").contains("if (legacyTrace)"));
    }
}
