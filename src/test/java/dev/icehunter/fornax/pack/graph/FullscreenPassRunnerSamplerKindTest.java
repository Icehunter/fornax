package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import org.junit.jupiter.api.Test;

import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.InputSamplerKind.LINEAR_CLAMP;
import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.InputSamplerKind.NEAREST_CLAMP;
import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.InputSamplerKind.NOISE_REPEAT;
import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.InputSamplerKind.PACK_TEXTURE_REPEAT;
import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.InputSamplerKind.SHADOW_COMPARISON;
import static dev.icehunter.fornax.pack.graph.FullscreenPassRunner.samplerKindFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the sampler-kind split {@link ShadowMapManager#RAW_TARGET}'s own doc comment describes: two
 * pack-visible names for one texture, routed to two different sampler kinds by name alone. This is
 * the "structurally impossible to route the raw alias into the PCF path or vice versa" guarantee --
 * a GPU-free unit test reaches it directly since {@code samplerKindFor} is a pure function.
 */
class FullscreenPassRunnerSamplerKindTest {
    @Test
    void shadowMapTargetGetsTheComparisonSampler() {
        assertEquals(SHADOW_COMPARISON,
                samplerKindFor(ShadowMapManager.TARGET, false, false, TargetFilter.NEAREST));
    }

    @Test
    void shadowMapRawTargetNeverGetsTheComparisonSampler() {
        // The whole point: a different string can never match the ShadowMapManager.TARGET literal
        // equality check inside samplerKindFor, so this falls through to the same plain-sampler
        // default every ordinary input gets -- not a second explicit branch someone could delete.
        assertEquals(NEAREST_CLAMP,
                samplerKindFor(ShadowMapManager.RAW_TARGET, false, false, TargetFilter.NEAREST));
        assertNotEquals(SHADOW_COMPARISON,
                samplerKindFor(ShadowMapManager.RAW_TARGET, false, false, TargetFilter.LINEAR));
    }

    @Test
    void packTextureTakesPrecedenceOverFilterAndNoise() {
        assertEquals(PACK_TEXTURE_REPEAT,
                samplerKindFor("someWaveNormal", true, false, TargetFilter.LINEAR));
    }

    @Test
    void builtinNoiseIsRepeatLinear() {
        assertEquals(NOISE_REPEAT, samplerKindFor("builtin.noise", false, true, TargetFilter.NEAREST));
    }

    @Test
    void linearFilterTargetIsClampedLinear() {
        assertEquals(LINEAR_CLAMP, samplerKindFor("bloomDown3", false, false, TargetFilter.LINEAR));
    }

    @Test
    void plainTargetIsNearestClamp() {
        assertEquals(NEAREST_CLAMP, samplerKindFor("sceneHdr", false, false, TargetFilter.NEAREST));
    }
}
