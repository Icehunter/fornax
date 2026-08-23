package dev.icehunter.fornax.pack.graph;

/** Fornax extension bit for Mojang graph textures that must also carry VkImage STORAGE usage. */
public final class FornaxTextureUsage {
    /** Mojang currently occupies bits 0..4 for texture usage; bit 5 is the pack storage extension. */
    public static final int STORAGE = 1 << 5;

    private FornaxTextureUsage() {
    }
}
