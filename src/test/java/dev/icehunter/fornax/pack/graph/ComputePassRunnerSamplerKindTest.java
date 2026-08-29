package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

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
}
