package dev.icehunter.fornax.pack.graph;

/** A resolution-independent two-dimensional texture extent declared by a shader pack. */
public record TextureSize(int width, int height) {
    public TextureSize {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
    }
}
