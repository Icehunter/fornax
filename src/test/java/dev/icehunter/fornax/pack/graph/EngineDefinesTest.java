package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.config.AaMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineDefinesTest {
    @Test
    void offMapsToMethodOffOnly() {
        assertEquals(Map.of(
                "FX_TAA", 0,
                "FX_UPSCALE", 0,
                "FX_METHOD_OFF", 1,
                "FX_METHOD_TAA", 0,
                "FX_METHOD_SSAA", 0,
                "FX_METHOD_TAAU", 0,
                "FX_METHOD_METALFX", 0,
                "FX_COMPUTE", 0
        ), EngineDefines.forMethod(AaMethod.OFF, false));
    }

    @Test
    void taaMapsToFxTaaAndMethodTaaOnly() {
        assertEquals(Map.of(
                "FX_TAA", 1,
                "FX_UPSCALE", 0,
                "FX_METHOD_OFF", 0,
                "FX_METHOD_TAA", 1,
                "FX_METHOD_SSAA", 0,
                "FX_METHOD_TAAU", 0,
                "FX_METHOD_METALFX", 0,
                "FX_COMPUTE", 0
        ), EngineDefines.forMethod(AaMethod.TAA, false));
    }

    @Test
    void ssaaMapsToMethodSsaaOnly() {
        assertEquals(Map.of(
                "FX_TAA", 0,
                "FX_UPSCALE", 0,
                "FX_METHOD_OFF", 0,
                "FX_METHOD_TAA", 0,
                "FX_METHOD_SSAA", 1,
                "FX_METHOD_TAAU", 0,
                "FX_METHOD_METALFX", 0,
                "FX_COMPUTE", 0
        ), EngineDefines.forMethod(AaMethod.SSAA, false));
    }

    @Test
    void taauMapsToFxTaaAndFxUpscaleAndMethodTaauOnly() {
        assertEquals(Map.of(
                "FX_TAA", 1,
                "FX_UPSCALE", 1,
                "FX_METHOD_OFF", 0,
                "FX_METHOD_TAA", 0,
                "FX_METHOD_SSAA", 0,
                "FX_METHOD_TAAU", 1,
                "FX_METHOD_METALFX", 0,
                "FX_COMPUTE", 1
        ), EngineDefines.forMethod(AaMethod.TAAU, true));
    }

    @Test
    void metalfxMapsToFxTaaAndFxUpscaleAndMethodMetalfxOnly() {
        assertEquals(Map.of(
                "FX_TAA", 1,
                "FX_UPSCALE", 1,
                "FX_METHOD_OFF", 0,
                "FX_METHOD_TAA", 0,
                "FX_METHOD_SSAA", 0,
                "FX_METHOD_TAAU", 0,
                "FX_METHOD_METALFX", 1,
                "FX_COMPUTE", 1
        ), EngineDefines.forMethod(AaMethod.METALFX, true));
    }

    @Test
    void taauPreambleDeclaresUpscaleAndMethodTaauButNotMethodTaa() {
        String preamble = EngineDefines.glslPreamble(AaMethod.TAAU, false);
        assertTrue(preamble.contains("#define FX_UPSCALE 1"));
        assertTrue(preamble.contains("#define FX_METHOD_TAAU 1"));
        assertFalse(preamble.contains("FX_METHOD_TAA 1"));
    }
}
