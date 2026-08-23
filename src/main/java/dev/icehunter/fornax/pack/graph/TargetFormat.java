package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;

/**
 * The closed set of render-target formats a pack may declare, each mapped to the exact {@code
 * com.mojang.blaze3d.GpuFormat} constant name the target allocator uses (see the engine's own G-buffer
 * attachment formats) and its per-pixel byte cost for VRAM accounting. Kept as a plain enum with a string
 * format name so this layer stays off the blaze3d classpath and unit-testable; {@link TargetRegistry}
 * maps the name to the real GpuFormat constant.
 */
public enum TargetFormat {
    RGBA8("rgba8", "RGBA8_UNORM", 4),
    RGBA16_SNORM("rgba16_snorm", "RGBA16_SNORM", 8),
    RGBA16F("rgba16f", "RGBA16_FLOAT", 8),
    RG16F("rg16f", "RG16_FLOAT", 4),
    R8("r8", "R8_UNORM", 1),
    R32F("r32f", "R32_FLOAT", 4);

    private final String token;
    private final String gpuFormatName;
    private final int bytesPerPixel;

    TargetFormat(String token, String gpuFormatName, int bytesPerPixel) {
        this.token = token;
        this.gpuFormatName = gpuFormatName;
        this.bytesPerPixel = bytesPerPixel;
    }

    public String gpuFormatName() { return gpuFormatName; }
    public int bytesPerPixel() { return bytesPerPixel; }

    public static TargetFormat parse(String token, String targetName, String file) {
        for (TargetFormat f : values()) {
            if (f.token.equals(token)) return f;
        }
        throw new FornaxPackError(file, "targets." + targetName + ".format",
                "unknown target format '" + token + "'");
    }
}
