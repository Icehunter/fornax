package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetFormatTest {
    @Test
    void parsesRgba8() {
        TargetFormat f = TargetFormat.parse("rgba8", "t", "graph.toml");
        assertEquals(TargetFormat.RGBA8, f);
        assertEquals(4, f.bytesPerPixel());
        assertEquals("RGBA8_UNORM", f.gpuFormatName());
    }

    @Test
    void parsesRgba16Snorm() {
        TargetFormat f = TargetFormat.parse("rgba16_snorm", "t", "graph.toml");
        assertEquals(8, f.bytesPerPixel());
        assertEquals("RGBA16_SNORM", f.gpuFormatName());
    }

    @Test
    void parsesRgba16f() {
        TargetFormat f = TargetFormat.parse("rgba16f", "t", "graph.toml");
        assertEquals(8, f.bytesPerPixel());
        assertEquals("RGBA16_FLOAT", f.gpuFormatName());
    }

    @Test
    void rgba32fParsesAsFourFullPrecisionChannels() {
        TargetFormat f = TargetFormat.parse("rgba32f", "t", "graph.toml");
        // Four channels times four bytes per IEEE 754 single-precision value.
        assertEquals(16, f.bytesPerPixel());
        assertEquals("RGBA32_FLOAT", f.gpuFormatName());
        assertEquals(GpuFormat.RGBA32_FLOAT, TargetRegistry.gpuFormat(f));
    }

    @Test
    void parsesR8() {
        TargetFormat f = TargetFormat.parse("r8", "t", "graph.toml");
        assertEquals(1, f.bytesPerPixel());
        assertEquals("R8_UNORM", f.gpuFormatName());
    }

    @Test
    void parsesRg16f() {
        TargetFormat f = TargetFormat.parse("rg16f", "t", "graph.toml");
        assertEquals(4, f.bytesPerPixel());
        assertEquals("RG16_FLOAT", f.gpuFormatName());
    }

    @Test
    void parsesR32f() {
        TargetFormat f = TargetFormat.parse("r32f", "t", "graph.toml");
        assertEquals(4, f.bytesPerPixel());
        assertEquals("R32_FLOAT", f.gpuFormatName());
    }

    @Test
    void unknownFormatThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> TargetFormat.parse("bogus", "myTarget", "graph.toml"));
        assertEquals("targets.myTarget.format", e.key());
    }
}
