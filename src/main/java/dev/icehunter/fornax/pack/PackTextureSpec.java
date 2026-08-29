package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;

/**
 * A pack-shipped static texture asset declared under {@code [textures.NAME]} in {@code graph.toml}.
 * Two shapes share this one record, mirroring {@code TargetSpec}'s own nullable-optional-variant
 * convention rather than a sealed hierarchy:
 *
 * <ul>
 * <li><b>2D (default):</b> {@code depth == null}. {@code file} names a PNG, decoded and uploaded by
 *     {@code PackTextureRegistry}. {@code width}/{@code height}/{@code format} are {@code null} and
 *     ignored.</li>
 * <li><b>Volume:</b> {@code depth != null}. {@code file} names a raw binary volume asset (see
 *     {@code RawVolumeAsset}); {@code width}, {@code height} and {@code format} are then required,
 *     since nothing decodes them from the raw bytes the way a PNG's own header supplies 2D
 *     dimensions.</li>
 * </ul>
 *
 * @param name the declared table key ({@code [textures.NAME]}), also the input-ref string passes use
 * @param file the asset file path, relative to the pack root
 * @param depth {@code null} for the 2D path; the volume's Z extent otherwise
 * @param width required (non-null) iff {@code depth} is non-null
 * @param height required (non-null) iff {@code depth} is non-null
 * @param format required (non-null) iff {@code depth} is non-null; one of {@code RawVolumeAsset}'s
 *               declared format tokens (e.g. {@code "r8"}, {@code "rgba8"})
 */
public record PackTextureSpec(
        String name,
        String file,
        @Nullable Integer depth,
        @Nullable Integer width,
        @Nullable Integer height,
        @Nullable String format) {

    /** Convenience for the common 2D case: {@code depth}/{@code width}/{@code height}/{@code format} null. */
    public static PackTextureSpec texture2D(String name, String file) {
        return new PackTextureSpec(name, file, null, null, null, null);
    }

    public boolean isVolume() {
        return depth != null;
    }
}
