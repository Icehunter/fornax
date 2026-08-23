package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;

/** A category's albedo-driven smoothness synthesis parameters (blocks.toml inline table).
 * {@code source} is {@code null} when a category declares {@code smoothness} purely to {@code scale}
 * AUTHORED LabPBR {@code _s} data, with no albedo-luma synthesis at all -- {@code curve}/{@code min}
 * are then unused (a {@code null} source keeps {@code MAT_SMOOTHNESS_SRC} at {@code 0} for that
 * category, so terrain.fsh's Tier-2 gap-fill/override branch never runs for it). {@code scale}
 * multiplies the AUTHORED {@code _s} smoothness value directly, independent of source/curve/min --
 * {@code 1.0} is the neutral default every category gets unless it declares otherwise. */
public record SmoothnessSpec(@Nullable String source, double curve, double min, double scale) {}
