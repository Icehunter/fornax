package dev.icehunter.fornax.mixin.vanilla;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code fornax$pagedStitch} needs a live Minecraft/GPU device to exercise directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that the
 * atlas budget prefers a real device-local VRAM reading and only falls back to the system-memory
 * approximation when that reading is unavailable.
 */
class SpriteLoaderPagedStitchBudgetContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/SpriteLoaderPagedStitchMixin.java");

    @Test
    void atlasBudgetPrefersRealVramOverTheSystemMemoryApproximation() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static long fornax$atlasBudgetBytes(GpuDevice device)");
        assertTrue(methodStart >= 0, "fornax$atlasBudgetBytes must still take the live GpuDevice");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int vramCheck = method.indexOf("GpuMemoryEstimator.detectedVramBytesFromDevice(device)");
        int ramFallback = method.indexOf("FORNAX_RAM_FALLBACK_FRACTION");
        assertTrue(vramCheck >= 0, "must query the real device-local VRAM reading");
        assertTrue(ramFallback >= 0, "must still fall back to the system-memory approximation");
        assertTrue(vramCheck < ramFallback,
                "the real VRAM reading must be tried before the system-memory fallback, not after");
    }
}
