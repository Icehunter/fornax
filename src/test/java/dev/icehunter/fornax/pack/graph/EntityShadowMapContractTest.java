package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GPU allocation and mixin execution require a client. Pure name/validation/sampler checks run
 * directly; source contracts pin the independent attachment and exception-safe replay seams. */
class EntityShadowMapContractTest {
    private static final String ROOT = "src/main/java/dev/icehunter/fornax/";

    @Test
    void entityDepthIsAValidatedEngineInputWithNoHistory() {
        assertTrue(ShadowMapManager.isShadowMapRef("sunEntityShadowMap"));
        assertDoesNotThrow(() -> GraphValidatorTestSupport.validateTerrainWithInputs("sunEntityShadowMap"));
        assertThrows(FornaxPackError.class,
                () -> GraphValidatorTestSupport.validateTerrainWithInputs("sunEntityShadowMap.history"));
    }

    @Test
    void entityDepthUsesComparisonSamplingBeforePackTextureRules() {
        assertEquals(FullscreenPassRunner.InputSamplerKind.SHADOW_COMPARISON,
                FullscreenPassRunner.samplerKindFor("sunEntityShadowMap", false, false, TargetFilter.NEAREST));
        assertEquals(FullscreenPassRunner.InputSamplerKind.SHADOW_COMPARISON,
                FullscreenPassRunner.samplerKindFor("sunEntityShadowMap", true, false, TargetFilter.LINEAR));
    }

    @Test
    void resolverBindsIndependentEntityTextureAndView() throws Exception {
        String source = Files.readString(Path.of(ROOT + "pack/graph/GraphInputResolver.java"));
        assertTrue(source.contains("case ShadowMapManager.ENTITY_TARGET -> ShadowMapManager.getEntityView()"));
        assertTrue(source.contains("case ShadowMapManager.ENTITY_TARGET -> ShadowMapManager.getEntityTexture()"));
    }

    @Test
    void entityAllocationIsClearedAndOwnedAcrossResizeAndClose() throws Exception {
        String source = Files.readString(Path.of(ROOT + "pass/shadow/ShadowMapManager.java"));
        assertTrue(source.contains("clearDepthTexture(nextEntityTexture, 1.0f)"), "fresh entity depth must mean no occluder");
        assertTrue(source.contains("if (nextEntityView != null) nextEntityView.close();"), "partial allocation cleanup");
        assertTrue(source.contains("if (nextEntityTexture != null) nextEntityTexture.close();"));
        assertTrue(source.contains("oldEntityTexture.close()"), "resize releases retired entity texture after GPU idle");
        assertTrue(source.contains("currentEntityTexture.close()"), "pack close releases entity texture");
        assertTrue(source.contains("entityMapRequested = false"), "pack close resets optional replay request");
    }

    @Test
    void shadowDrawReusesBuffersWithIndependentDepthAndResetsReplayAfterFailure() throws Exception {
        String source = Files.readString(Path.of(ROOT + "mixin/vanilla/PreparedRenderTypeDeferredMixin.java"));
        assertTrue(source.contains("@WrapMethod(method = \"drawFromBuffer("), "replay the existing GPU draw, not CPU geometry construction");
        assertTrue(source.contains("ShadowMapManager.getEntityView()"), "entity-only replay needs independent depth");
        assertTrue(source.contains("ShadowMapManager.setEntityOnlyPhase(true)"));
        assertTrue(source.contains("finally {\n            ShadowMapManager.setEntityOnlyPhase(false);"));
        String phase = Files.readString(Path.of(ROOT + "mixin/vanilla/FeatureSolidFeaturesGraphMixin.java"));
        assertTrue(phase.contains("&& GraphRunner.shadowsEnabledThisFrame()"),
                "runtime-disabled shadow phases must not replay; evaluate the graph gate once per phase");
    }

    @Test
    void disabledFramesClearEntityDepthBeforeReturning() throws Exception {
        String source = Files.readString(Path.of(ROOT + "mixin/sodium/SodiumWorldRendererOrchestrationMixin.java"));
        assertTrue(source.contains("ShadowMapManager.ensureSize(64);\n            ShadowMapManager.clearEntity();"));
        assertTrue(source.contains("ShadowMapManager.ensureSize(resolution);\n        ShadowMapManager.clearEntity();"));
    }
}
