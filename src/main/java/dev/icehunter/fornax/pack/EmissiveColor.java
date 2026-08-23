package dev.icehunter.fornax.pack;

/**
 * An authored emission hue for a blocks.toml category ({@code emissive.color = [r, g, b]}), each
 * component 0-255. Optional companion to {@link EmissiveSpec#strength()}: when present, the GPU-side
 * emission word carries this exact color (see {@code BrickGridUpload#packEmissionWord}) instead of
 * the shader deriving a tint from the block's own face-color texels -- the fix for categories whose
 * face colors don't carry the intended hue (a torch's pale handle texel, six visually-identical torch
 * variants sharing one category, etc).
 */
public record EmissiveColor(int r, int g, int b) {
    public EmissiveColor {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            throw new IllegalArgumentException("EmissiveColor components must be 0-255, got (" + r + ", " + g + ", " + b + ")");
        }
    }

    /** Packs to {@code 0x00RRGGBB} -- the CPU-side representation {@code MaterialScalars} carries and
     * {@code BrickGridUpload.packEmissionWord} shifts into the emission word's bits 8-31. */
    public int packedRgb() {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
