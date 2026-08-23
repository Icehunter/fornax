package dev.icehunter.fornax.util;

import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.util.List;
import java.util.OptionalLong;

/**
 * Best-effort VRAM estimate for the SSAA settings UI. blaze3d has no reliable, backend-agnostic
 * VRAM query API, so this uses OSHI, the same best-effort OS-hardware-enumeration library
 * Minecraft's own crash report uses for its VRAM line; it is known to report 0/unavailable on some
 * platforms (e.g. Apple Silicon under MoltenVK). Callers MUST treat an empty result as "unknown,"
 * never as "zero risk."
 */
public final class GpuMemoryEstimator {
    private static final int GBUFFER_BYTES_PER_PIXEL = 24; // normal(8) + albedo(4) + material(4) + motion(4) + depth(4)
    private static final int NATIVE_TARGET_BYTES_PER_PIXEL = 8; // RGBA8_UNORM color(4) + D32_FLOAT depth(4)

    private GpuMemoryEstimator() {
    }

    /** Empty if VRAM could not be determined on this platform -- do not assume 0 means "no VRAM." */
    public static OptionalLong detectedVramBytes() {
        List<GraphicsCard> cards = new SystemInfo().getHardware().getGraphicsCards();
        long vram = cards.stream().mapToLong(GraphicsCard::getVRam).filter(v -> v > 0).max().orElse(0L);

        return vram > 0 ? OptionalLong.of(vram) : OptionalLong.empty();
    }

    /** Estimated bytes this preset's scaled G-buffer + native target would need, at a given native resolution. */
    public static long estimateBytes(SsaaPreset preset, int nativeWidth, int nativeHeight) {
        long scaledPixels = Math.round(nativeWidth * nativeHeight * (double) preset.pixelCountMultiplier());
        long nativePixels = (long) nativeWidth * nativeHeight;

        return scaledPixels * GBUFFER_BYTES_PER_PIXEL + nativePixels * NATIVE_TARGET_BYTES_PER_PIXEL;
    }
}
