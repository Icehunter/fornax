package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import static dev.icehunter.fornax.pack.graph.ParticlePassRunner.isTileableSampler;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins which particle inputs get the LINEAR + REPEAT sampler: the engine noise texture and any
 * pack-declared texture, and nothing else. {@code isTileableSampler} is a pure function, so a
 * unit test reaches it directly without a GPU. */
class ParticlePassRunnerSamplerKindTest {
    @Test
    void builtinNoiseIsTileable() {
        assertTrue(isTileableSampler("builtin.noise", false));
    }

    @Test
    void aDeclaredPackTextureIsTileable() {
        assertTrue(isTileableSampler("flakeSprite", true));
    }

    @Test
    void anythingElseIsNotTileable() {
        assertFalse(isTileableSampler("sceneHdr", false));
    }
}
