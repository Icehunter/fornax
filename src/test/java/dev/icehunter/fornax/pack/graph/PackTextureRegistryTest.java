package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.pack.PackTextureSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the pure bookkeeping/no-GPU-device bits of {@link PackTextureRegistry}'s lifecycle, mirroring
 * {@code OpaqueDepthLifecycleTest}/{@code TargetRegistryBufferTest}'s own doc comments: this suite
 * runs headless with no GPU device ever bound, so {@link PackTextureRegistry#ensureLoaded()} always
 * no-ops via {@code RenderSystem.tryGetDevice()}'s null-device guard and never builds a real
 * texture/view: there is nothing device-backed left to pin without a live {@code GpuDevice}. The
 * real decode-and-upload path (and the corrupt-file log-not-throw path) needs an actual PNG on disk
 * plus a device and is exercised live, not here.
 */
class PackTextureRegistryTest {
    @Test
    void mipLevelCountNeverProducesAZeroSizedMojangTextureLevel() {
        assertEquals(11, PackTextureRegistry.computeMipLevelCount(1254, 1254));
        assertEquals(3, PackTextureRegistry.computeMipLevelCount(7, 4));
        // GpuTexture.getWidth/getHeight use a bare right shift with no max(1), so a non-square
        // chain must stop when its smaller axis reaches one: for a 1040x3120 water atlas, a count
        // driven by the larger axis would request level 11, where 1040 >> 11 == 0 and
        // writeToTexture would reject it.
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
                "waterWaveNormal", PackTextureSpec.texture2D("waterWaveNormal", "textures/water_wave_normal.png"));
        PackTextureRegistry registry = PackTextureRegistry.create(Path.of("."), specs);
        assertDoesNotThrow(registry::ensureLoaded);
        assertNull(registry.getView("waterWaveNormal"));
        assertNull(registry.getTexture("waterWaveNormal"));
    }

    @Test
    void isDeclaredReflectsSpecMapOnly() {
        Map<String, PackTextureSpec> specs = Map.of(
                "waterWaveNormal", PackTextureSpec.texture2D("waterWaveNormal", "textures/water_wave_normal.png"));
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
                Map.of("waterWaveNormal", PackTextureSpec.texture2D("waterWaveNormal", "textures/water_wave_normal.png")));
        assertDoesNotThrow(registry::close);
        // Idempotent: GraphRunner.closeCurrent() must be able to call this safely even on a
        // registry that never got past bookkeeping (no GPU device this session).
        assertDoesNotThrow(registry::close);
    }

    /**
     * The one end-to-end test of the volume branch that needs a real GPU: a raw 2x2x2 R8 asset on
     * disk, declared through a volume {@link PackTextureSpec}, loaded through
     * {@link PackTextureRegistry#ensureLoaded()} exactly the way {@code GraphRunner.prepare()} calls
     * it every frame. Device-gated the same way {@code Volume3DTextureTest}'s own device-dependent
     * tests are: {@link PackTextureRegistry#ensureLoaded()} already no-ops without a device (see
     * {@link #ensureLoadedWithoutDeviceIsNoOp()}), so without this guard the {@code assertNotNull}s
     * below would fail headless rather than skip.
     */
    @Test
    void loadsAVolumeTextureSpec(@TempDir Path packRoot) throws IOException {
        assumeTrue(RenderSystem.tryGetDevice() != null, "no GPU device available in this test run");
        Path texturesDir = packRoot.resolve("textures");
        Files.createDirectories(texturesDir);
        byte[] header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2).putInt(2).putInt(2).putInt(0).array();
        Files.write(texturesDir.resolve("test_volume.bin"),
                concat(header, new byte[2 * 2 * 2]));

        PackTextureSpec spec = new PackTextureSpec("testVolume", "textures/test_volume.bin",
                2, 2, 2, "r8");
        PackTextureRegistry registry = PackTextureRegistry.create(packRoot,
                Map.of("testVolume", spec));

        registry.ensureLoaded();

        assertNotNull(registry.getTexture("testVolume"));
        assertNotNull(registry.getView("testVolume"));
        registry.close();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
