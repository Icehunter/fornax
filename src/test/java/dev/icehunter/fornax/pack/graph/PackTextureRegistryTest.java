package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.PackTextureSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure bookkeeping/no-GPU-device bits of {@link PackTextureRegistry}'s lifecycle, mirroring
 * {@code OpaqueDepthLifecycleTest}/{@code TargetRegistryBufferTest}'s own doc comments: this suite
 * runs headless with no GPU device ever bound, so {@link PackTextureRegistry#ensureLoaded()} always
 * no-ops via {@code RenderSystem.tryGetDevice()}'s null-device guard and never builds a real
 * texture/view -- there is nothing device-backed left to pin without a live {@code GpuDevice}. The
 * real decode-and-upload path (and the corrupt-file log-not-throw path) needs an actual PNG on disk
 * plus a device and is exercised live, not here.
 */
class PackTextureRegistryTest {
    @Test
    void mipLevelCountNeverProducesAZeroSizedMojangTextureLevel() {
        assertEquals(11, PackTextureRegistry.computeMipLevelCount(1254, 1254));
        assertEquals(3, PackTextureRegistry.computeMipLevelCount(7, 4));
        // GpuTexture.getWidth/getHeight use a bare right shift with no max(1), so a non-square
        // chain must stop when its smaller axis reaches one. The old max-axis count requested level
        // 11 for Plague's 1040x3120 water atlas: 1040 >> 11 == 0, and writeToTexture rejected it.
        assertEquals(11, PackTextureRegistry.computeMipLevelCount(1040, 3120));
        assertEquals(1, PackTextureRegistry.computeMipLevelCount(1, 19));
    }

    @Test
    void downsampleAveragesEveryChannel() {
        try (var source = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 2, 2, false)) {
            source.setPixel(0, 0, 0x10203040);
            source.setPixel(1, 0, 0x50607080);
            source.setPixel(0, 1, 0x90A0B0C0);
            source.setPixel(1, 1, 0xD0E0F000);
            try (var mip = PackTextureRegistry.downsample(source)) {
                assertEquals(1, mip.getWidth());
                assertEquals(1, mip.getHeight());
                assertEquals(0x70809060, mip.getPixel(0, 0));
            }
        }
    }

    @Test
    void ensureLoadedWithoutDeviceIsNoOp() {
        Map<String, PackTextureSpec> specs = Map.of(
                "waterWaveNormal", new PackTextureSpec("waterWaveNormal", "textures/water_wave_normal.png"));
        PackTextureRegistry registry = PackTextureRegistry.create(Path.of("."), specs);
        assertDoesNotThrow(registry::ensureLoaded);
        assertNull(registry.getView("waterWaveNormal"));
        assertNull(registry.getTexture("waterWaveNormal"));
    }

    @Test
    void isDeclaredReflectsSpecMapOnly() {
        Map<String, PackTextureSpec> specs = Map.of(
                "waterWaveNormal", new PackTextureSpec("waterWaveNormal", "textures/water_wave_normal.png"));
        PackTextureRegistry registry = PackTextureRegistry.create(Path.of("."), specs);
        assertTrue(registry.isDeclared("waterWaveNormal"));
        assertFalse(registry.isDeclared("bogus"));
        assertFalse(registry.isDeclared("builtin.noise"));
    }

    @Test
    void emptySpecsRegistryIsDeclaredAlwaysFalse() {
        PackTextureRegistry registry = PackTextureRegistry.create(Path.of("."), Map.of());
        assertDoesNotThrow(registry::ensureLoaded);
        assertFalse(registry.isDeclared("waterWaveNormal"));
    }

    @Test
    void closeBeforeAnyLoadIsNoOp() {
        PackTextureRegistry registry = PackTextureRegistry.create(Path.of("."),
                Map.of("waterWaveNormal", new PackTextureSpec("waterWaveNormal", "textures/water_wave_normal.png")));
        assertDoesNotThrow(registry::close);
        // Idempotent -- GraphRunner.closeCurrent() must be able to call this safely even on a
        // registry that never got past bookkeeping (no GPU device this session).
        assertDoesNotThrow(registry::close);
    }
}
