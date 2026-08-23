package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.config.AaMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineDefinesComputeTest {
    @Test
    void fxComputeIsZeroWhenBackendUnavailable() {
        var defines = EngineDefines.forMethod(AaMethod.OFF, false);
        assertEquals(0, defines.get("FX_COMPUTE"));
    }

    @Test
    void fxComputeIsOneWhenBackendAvailable() {
        var defines = EngineDefines.forMethod(AaMethod.OFF, true);
        assertEquals(1, defines.get("FX_COMPUTE"));
    }

    @Test
    void fxComputeNeverInteractsWithMethodFacts() {
        var defines = EngineDefines.forMethod(AaMethod.TAAU, true);
        assertEquals(1, defines.get("FX_TAA"));
        assertEquals(1, defines.get("FX_UPSCALE"));
        assertEquals(1, defines.get("FX_COMPUTE"));
    }
}
